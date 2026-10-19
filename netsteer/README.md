# NetSteer — Wi-Fi / Cellular Failover & Network Management for Android

> **What this is:** on-device network monitoring, per-app network selection,
> automatic failover, and connectivity diagnosis, plus one narrow, opt-in
> feature that *does* aggregate bandwidth (multi-network HTTP range downloads
> made by this app).
>
> **What this is not:** transparent Wi-Fi + cellular bandwidth bonding for
> arbitrary traffic. On a stock, non-rooted Android phone with no remote
> server, that cannot be done. Section 1 explains why.

---

## 1. Feasibility assessment

### 1.1 Verdict

| Capability | Feasible without root and without a server? | How / why not |
|---|---|---|
| Monitor Wi-Fi + cellular state, validation, captive portal, metering, link estimates | **Yes** | `ConnectivityManager.NetworkCallback`, `NetworkCapabilities`, `LinkProperties` |
| Keep cellular up while Wi-Fi is the default (hot standby) | **Yes** | `requestNetwork(TRANSPORT_CELLULAR)` holds the radio while the request is alive |
| Choose which network *this app's* traffic uses | **Yes** | `bindProcessToNetwork()`, or per socket via `Network.socketFactory` / `Network.bindSocket()` / `Network.openConnection()` |
| Automatic failover for this app's traffic | **Yes** | Policy loop + re-bind; existing sockets must reconnect |
| Choose the network for **other** apps' traffic | **Only via `VpnService`** (Mode B, §2.4) | No public API lets an app steer another app's sockets. The OS default-network choice is not app-controllable. |
| Aggregate bandwidth for **one download inside this app** | **Yes, for Range-capable HTTP servers** | Fetch disjoint byte ranges over each network in parallel (`MultiNetworkDownloader`) |
| Spread **many flows** from all apps across both links (per-flow load balancing) | **Technically yes, with big caveats** (Mode B) | `VpnService` + userspace TCP/IP stack re-originating each flow on a chosen network. Aggregates *only across flows*, not within one. |
| **True bonding**: one TCP/QUIC flow using both links' bandwidth at once, for arbitrary apps and servers | **No** | See 1.2 |

### 1.2 Why true single-flow bonding is impossible here

1. **Transport identity is bound to an address pair.** A TCP connection is identified by its 5-tuple. Wi-Fi and cellular egress have different source IPs (different NATs: home CGN/NAT vs. carrier CGNAT). Packets from the cellular IP are, to the remote server, a different connection and get a RST. Splitting one flow across two paths needs the **remote endpoint to understand multipath**. That is the definition of needing a cooperating server.
2. **MPTCP needs a cooperating peer and kernel support.** Multipath TCP (RFC 8684) does exactly this, but (a) the server must speak MPTCP (very few public servers do; Apple uses it for its own services); (b) as far as we know, stock Android GKI kernels do not ship with MPTCP enabled, and there is no SDK API for it; (c) adding subflows needs a kernel path manager configured over netlink, which needs `CAP_NET_ADMIN`, unavailable to apps. Where OEMs shipped MPTCP-style "boosters", they used either their own proxy servers or the HTTP-range trick below.
3. **QUIC multipath** (IETF draft) has the same requirement: both endpoints must negotiate it. QUIC *connection migration* (RFC 9000) moves a connection from one path to another. That is failover, not aggregation.
4. **A VPN cannot fix it either.** `VpnService` sees every packet but must still egress each packet through a socket on one real network. Splitting one flow's packets across Wi-Fi and cellular means the server sees two source IPs → same problem as (1). Bonding VPNs (Speedify, OpenMPTCProuter, etc.) work only because a **remote bonding server** reassembles the flow, which this design rules out.
5. **No root.** Without root you cannot change routing tables, policy routing (`ip rule`), iptables, or kernel modules, so OS-level schemes such as ECMP or kernel bonding are off the table.

**Conclusion:** true bandwidth bonding with no server, on a non-rooted stock device, is **not feasible**. This project delivers failover and network management, labelled as such, plus two forms of aggregation that are honest about their scope: per-download range splitting, and (optionally) per-flow distribution.

### 1.3 When (some) bonding becomes feasible — prerequisites

| Path | Prerequisites | Limitations |
|---|---|---|
| In-app multi-range download (implemented) | Server supports `Range` → `206`, strong `ETag` or `Last-Modified`; both networks up (hot standby) | Only this app's downloads; no uploads/streams; per-chunk request overhead; costs cellular data |
| MPTCP | Root or an OEM kernel with `CONFIG_MPTCP` + path manager, **and** MPTCP-capable server | Not available on stock devices; server requirement violates the no-server constraint for arbitrary destinations |
| Per-flow distribution (Mode B, design only) | `VpnService` consent; userspace TCP/IP stack (e.g. gVisor netstack / lwIP via tun2socks) | Aggregates only across *multiple* flows; occupies the device's single VPN slot; CPU/battery cost; breaks apps that refuse VPNs; Play VPN policy |
| Bonding VPN | A remote aggregation server | Violates the stated constraint; listed only for completeness |

---

## 2. Architecture

### 2.1 Mode A (implemented): app-scoped failover & network management

```
┌──────────────────────────────── NetSteer process ───────────────────────────────┐
│                                                                                 │
│  MainActivity (Views)  ◀── StateFlows ──  NetSteerApp (process singletons)      │
│     │  preference, hot standby,                │                                │
│     │  diagnose, aggregated-download test      │                                │
│     ▼                                          ▼                                │
│  MonitorService (FGS, specialUse) ──start──▶ FailoverController (5 s loop)      │
│                                                │      ▲ immediate "kick" on     │
│                       ┌────────────────────────┤      │ bound-network onLost    │
│                       ▼                        ▼      │                         │
│               Prober (per-network          NetworkMonitor ◀── NetworkCallbacks  │
│               DNS → TCP → HTTPS)           (Wi-Fi: register, Cell: register or  │
│                       │                     request for hot standby, default)   │
│                       ▼                        │                                │
│     :core  FailoverPolicy (score + hysteresis) │  DiagnosisClassifier           │
│            ChunkQueue (work stealing)          │  (pure Kotlin, unit-tested)    │
│                       │                        │                                │
│                       ▼                        ▼                                │
│               NetworkSelector ─── bindProcessToNetwork(network) / null          │
│               MultiNetworkDownloader ─── Network.openConnection() per link      │
└─────────────────────────────────────────────────────────────────────────────────┘
                      │ Wi-Fi socket                    │ Cellular socket
                      ▼                                 ▼
                 Wi-Fi AP / ISP                   Carrier (CGNAT)
```

**Key design decisions**

- **Pure-Kotlin `:core` module.** Scoring, hysteresis, diagnosis classification and the chunk queue have no Android dependency, so they are deterministic and JVM unit-tested. The `:app` module only maps framework objects to `NetworkSnapshot` / `ProbeResult`.
- **Every probe is pinned to its network.** `Network.getAllByName`, `Network.socketFactory` and `Network.openConnection` mean a Wi-Fi probe can never silently succeed over cellular. This matters for diagnosis integrity.
- **Hysteresis, not greedy switching.** A healthy current network is replaced only if a candidate beats it by `hysteresisMargin` for `confirmRounds` consecutive rounds after `minDwellMs`. Loss, validation loss, captive portal or blocked status trigger an **immediate** failover. Network loss triggers an out-of-band re-decision without waiting for probes.
- **HTTPS-only probes with system CAs only** (`network_security_config.xml`). A TLS-intercepting middlebox shows up as `TLS_OR_HTTP_INTERCEPTED` instead of being trusted through a user-installed CA.
- **Least privilege.** No location permission (so no SSID/BSSID), no VPN, no privileged permissions.

### 2.2 Scoring (core/FailoverPolicy.kt)

```
usable  = validated ∧ ¬captivePortal ∧ ¬blocked ∧ (fresh probe loss ≤ 0.5)
score   = 100
        + 40  if transport matches PREFER_* preference
        − 10  if AUTO and metered
        + min(20, 5·ln(downKbps/1000 + 1))        # OS estimate, deliberately capped
        − min(40, rtt_ms/10) − 60·loss             # from our own fresh probes
*_ONLY preferences filter transports before scoring.
```

### 2.3 Diagnosis ladder (core/Diagnosis.kt)

`BLOCKED_BY_POLICY → CAPTIVE_PORTAL → DNS_FAILURE → NO_ROUTE → TLS_OR_HTTP_INTERCEPTED → UNEXPECTED_HTTP → NOT_VALIDATED_BUT_WORKING → HIGH_LATENCY → HEALTHY`.
Each verdict has a human-readable detail and a remedy. The layer order is what separates "DNS resolver broken but routing fine" from "no upstream at all".

### 2.4 Mode B (design only, not implemented): device-wide per-flow steering

For users who need steering or failover for *other* apps:

1. `VpnService.Builder` with `addRoute("0.0.0.0", 0)` / `("::", 0)`, `addDisallowedApplication(ownPackage)` so the app's own egress sockets bypass the tunnel, and `setUnderlyingNetworks([wifi, cell])` so the OS accounts metering correctly.
2. Feed the TUN fd into a userspace TCP/IP stack (gVisor netstack or lwIP, via a tun2socks-style bridge). The stack **terminates** each TCP flow locally and opens a new socket for it on the chosen `Network` (`network.bindSocket(socket)`). UDP is relayed per 5-tuple.
3. A per-flow scheduler (weighted by live throughput/RTT from the same `:core` scorer) assigns **new** flows to Wi-Fi or cellular. Existing flows stay pinned, so no reordering occurs.
4. On failure, new flows go to the surviving link. Existing flows on the dead link break and apps reconnect. This is still not seamless.

**What Mode B gives:** device-wide failover for new connections, and aggregate throughput for *multi-connection* workloads (parallel downloads, browsers, app stores).
**What it does not give:** more bandwidth for any single connection.
**Costs and risks:** takes the device's only VPN slot (conflicts with corporate or always-on VPN); some banking and streaming apps refuse to run over a VPN; userspace TCP costs CPU and battery and needs MTU/MSS handling; DNS must be intercepted and resolved on the chosen network; and Google Play requires a VPN declaration, with VPN as core functionality. **Security note:** the app sits on-path for all traffic. It sees connection metadata and plaintext of any non-TLS traffic, so it needs a threat model and code review comparable to any VPN client. TLS is not intercepted.

---

## 3. Implementation plan

| Phase | Deliverable | Exit criterion |
|---|---|---|
| 0 | Gradle multi-module skeleton (`:core` JVM, `:app` Android), CI running `:core:test` | Green CI |
| 1 | `NetworkMonitor`: Wi-Fi/cellular/default callbacks → `StateFlow` | UI reflects add/lose/caps changes on emulator toggles |
| 2 | `Prober` (DNS/TCP/HTTPS pinned per network) + `DiagnosisClassifier` | Each fault in the test matrix yields the expected verdict |
| 3 | `FailoverPolicy` with hysteresis + unit tests | Policy tests pass (flap, dwell, emergency, preference) |
| 4 | `NetworkSelector` + `FailoverController` + `MonitorService` (FGS) | Bound network switches on Wi-Fi loss; log shows reason |
| 5 | Hot standby toggle (`requestNetwork` cellular) | Cellular stays listed while Wi-Fi validated |
| 6 | `MultiNetworkDownloader` + `ChunkQueue` | SHA-256 matches single-link download; both links contribute bytes |
| 7 | Hardening: OEM battery-kill guidance, configurable probe host, export log, persist preference | Test matrix pass on ≥3 OEMs |
| 8 (optional) | Mode B prototype behind a feature flag | Separate security review before shipping |

---

## 4. Source layout

```
netsteer/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/libs.versions.toml, gradle/wrapper/…, gradlew, gradlew.bat
├── core/                                   # pure Kotlin, no Android
│   └── src/main/kotlin/com/intelram/netsteer/core/
│       ├── Model.kt            # Transport, NetworkSnapshot, ProbeStats, Preference
│       ├── FailoverPolicy.kt   # Scorer + hysteresis state machine
│       ├── Diagnosis.kt        # ProbeResult → Verdict/remedy
│       └── ChunkQueue.kt       # work-stealing byte ranges
│   └── src/test/kotlin/…       # 15 JUnit tests
└── app/
    ├── src/main/AndroidManifest.xml
    ├── src/main/res/xml/network_security_config.xml
    └── src/main/kotlin/com/intelram/netsteer/
        ├── NetSteerApp.kt              # process singletons
        ├── NetworkMonitor.kt           # NetworkCallbacks → StateFlow
        ├── Prober.kt                   # per-network DNS/TCP/HTTPS probes
        ├── NetworkSelector.kt          # bindProcessToNetwork, reportNetworkConnectivity
        ├── FailoverController.kt       # policy loop + audit log
        ├── MultiNetworkDownloader.kt   # range-split download over N networks
        ├── MonitorService.kt           # foreground service (specialUse)
        └── MainActivity.kt             # framework-Views UI, no AndroidX
```

Core snippet: binding the process and keeping cellular alive:

```kotlin
// Keep cellular attached while Wi-Fi is default (hot standby)
cm.requestNetwork(
    NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build(),
    callback,
)
// Steer THIS process's new sockets + DNS to the chosen network (null = system default)
cm.bindProcessToNetwork(chosenNetwork)
```

---

## 5. Build, sign, install

### 5.1 Prerequisites
- Android Studio Meerkat (2024.3) or newer, or command-line tools + JDK 17+.
- Android SDK Platform 35 and Build-Tools 35.x (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`).
- A device with Android 10+ (minSdk 29) and USB debugging enabled.

### 5.2 Build & test
```bash
cd netsteer
./gradlew :core:test            # policy, diagnosis, chunk-queue unit tests
./gradlew :app:assembleDebug    # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Android Studio: *File → Open → `netsteer/`*, let Gradle sync, then run the `app` configuration.

### 5.3 Release signing
```bash
keytool -genkeypair -v -keystore netsteer-release.jks -alias netsteer \
        -keyalg RSA -keysize 4096 -validity 10000
cat > keystore.properties <<'EOF'
storeFile=netsteer-release.jks
storePassword=…
keyAlias=netsteer
keyPassword=…
EOF
./gradlew :app:assembleRelease        # signed + R8-minified APK
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs \
        app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease          # .aab for Google Play
```
`keystore.properties` and `*.jks` are git-ignored. Keep the keystore offline and backed up: losing it means you can never ship an update under the same package name. For Play, enrol in Play App Signing and keep this key as the upload key.

### 5.4 Useful adb commands for testing
```bash
adb shell svc wifi disable|enable
adb shell svc data disable|enable
adb shell cmd netpolicy set restrict-background true      # Data Saver → BLOCKED_BY_POLICY
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier invalid.example   # breaks DNS/validation
adb shell dumpsys connectivity | grep -A3 "NetworkAgentInfo"
adb shell dumpsys deviceidle force-idle                   # Doze behaviour
adb logcat | grep -i netsteer
```

---

## 6. APK delivery: limitations and considerations

- **Sideloading:** the user must allow "Install unknown apps" for the installing source. Play Protect may scan or warn. Google's developer-verification requirement for apps installed on certified devices started rolling out in selected countries from September 2026 and is planned to go global in 2027, so register the signing identity before distributing outside Play. Check the current status.
- **Google Play:** `FOREGROUND_SERVICE_SPECIAL_USE` requires a justification in the Play Console declaration. Play's target-API rule (as of 31 Aug 2026, Android 16 / API 36 for new apps and updates) means bumping `compileSdk`/`targetSdk` and AGP before submission. Mode B would also require the VPN declaration.
- **OEM background killing:** Xiaomi/HyperOS, Huawei, Oppo/ColorOS and some Samsung settings stop foreground services. Guide users to exclude the app from battery optimisation. Failover stops if the process dies.
- **Scope must be stated in the listing:** "failover and network management for this app's traffic; not bandwidth bonding". Implying bonding would be inaccurate and may fall foul of Play's misleading-claims policy.
- **Data cost:** hot standby and aggregated downloads use cellular data. Show this in the UI (done) and the listing.
- **Enterprise devices:** work profiles, MDM network restrictions, and always-on VPN can block `requestNetwork` or override routing. Diagnostics then report `BLOCKED_BY_POLICY` or the networks are simply not visible.
- **Dual SIM:** only the default data subscription is reachable through `TRANSPORT_CELLULAR` requests. The non-default SIM cannot be used for data by third-party apps.

---

## 7. Test matrix

**Devices / configurations** (minimum set)

| ID | Device class | Why |
|---|---|---|
| D1 | Emulator API 29 and API 35 | Baseline; scripted `svc` toggles |
| D2 | Pixel, latest Android | Reference AOSP connectivity stack |
| D3 | Samsung One UI | OEM connectivity tweaks ("Switch to mobile data"), battery management |
| D4 | Xiaomi/HyperOS or Oppo/ColorOS | Aggressive FGS killing |
| D5 | Dual-SIM phone | Default-data-SIM behaviour |
| D6 | Device with work profile / always-on VPN | Policy interaction |
| D7 | Wi-Fi-only tablet | Single-transport degenerate case |

**Scenarios** (run on D1–D4 at minimum; ✓ = expected result)

| # | Scenario | Injection | Expected |
|---|---|---|---|
| S1 | Wi-Fi lost while bound to Wi-Fi | `svc wifi disable` | Switch to CELLULAR, reason `CURRENT_LOST`, ≤1 s after `onLost` with hot standby on |
| S2 | Wi-Fi up but no upstream | Unplug AP WAN | Probe loss → `CURRENT_DEGRADED` switch within 2 rounds; diagnosis `NO_ROUTE` or `DNS_FAILURE` |
| S3 | Captive portal | Hotel/guest SSID or portal test AP | Wi-Fi never selected; diagnosis `CAPTIVE_PORTAL` |
| S4 | Broken DNS | Private DNS → invalid hostname | `DNS_FAILURE` (or OS validation loss) |
| S5 | TLS interception | AP with transparent HTTPS proxy | `TLS_OR_HTTP_INTERCEPTED` |
| S6 | High latency | `tc qdisc … netem delay 400ms` on AP | `HIGH_LATENCY`; switch to cellular only if it beats Wi-Fi by margin for 3 rounds |
| S7 | Oscillating quality | Alternate netem 50↔80 ms every 5 s | **No** switches (hysteresis) |
| S8 | Wi-Fi returns after failover | `svc wifi enable` | Switch back after dwell + 3 confirm rounds (`BETTER_CANDIDATE`) |
| S9 | Preference change | Select CELLULAR_ONLY | Immediate switch, `PREFERENCE_CHANGED`; Wi-Fi never bound after that |
| S10 | Data Saver | `netpolicy set restrict-background true` | `BLOCKED_BY_POLICY` on metered networks |
| S11 | Doze / screen off 30 min | `deviceidle force-idle` | FGS survives; record and document probe cadence degradation |
| S12 | Aggregated download, Range server | 100 MB file, both links up | Both links contribute bytes; SHA-256 equals single-link; throughput ≥1.3× best single link when the slower link is ≥30% of the faster |
| S13 | Aggregated download, no Range | Server returning 200 | Clean failure "server answered 200 to a Range request" |
| S14 | Resource changes mid-download | Replace file on server | `If-Range` mismatch → clean failure, no silent corruption |
| S15 | One link dies mid-download | Disable data mid-transfer | Its chunks are re-queued; download completes on Wi-Fi |
| S16 | Probe isolation | tcpdump on AP while probing cellular | Zero cellular-probe packets seen on Wi-Fi |

---

## 8. Acceptance criteria

1. **Honest labelling:** the UI header, the notification text and this README all state "failover / network management, not bandwidth bonding". The only aggregation claim is the in-app range download, labelled "this app only, Range-capable servers".
2. **Monitoring:** every Wi-Fi/cellular network with `NET_CAPABILITY_INTERNET` appears in the UI with validated / metered / captive / blocked / bandwidth-estimate / DNS servers, and updates within 2 s of the corresponding callback.
3. **Failover latency:** loss of the bound network → re-bind to the surviving network within 1 s of `onLost` (hot standby on). Degradation (probe loss > 50 %) → re-bind within 2 probe rounds.
4. **Stability:** with scores differing by less than `hysteresisMargin`, zero switches over 10 minutes. Never more than one non-emergency switch per `minDwellMs`.
5. **Preference fidelity:** `WIFI_ONLY` / `CELLULAR_ONLY` never bind the other transport. With no allowed network, the app unbinds and logs it.
6. **Diagnosis accuracy:** S2–S6 and S10 produce the expected verdict on D1–D4.
7. **Aggregation correctness:** S12–S15 behave as specified. Output is byte-identical to a single-link download or the operation fails explicitly.
8. **Traffic isolation:** S16 passes. Probes and range chunks use only their assigned network.
9. **Least privilege:** the merged manifest contains only INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE and POST_NOTIFICATIONS. Cleartext traffic is disabled, and user CAs are not trusted.
10. **Build:** `./gradlew :core:test :app:assembleRelease` succeeds, and `apksigner verify` passes on the release APK.

---

## 9. Known limitations of this implementation

- `bindProcessToNetwork` affects only sockets created **after** the call. Long-lived connections in the app must reconnect after a switch.
- Probe host defaults to `www.google.com/generate_204`. It may be blocked in some regions; make it configurable (Phase 7).
- `linkDownstreamBandwidthKbps` is an OS estimate, often a static value per radio technology on cellular, so it is capped in scoring.
- Preference and hot-standby settings are not persisted yet (Phase 7).
- No SSID display (location permission deliberately not requested).
