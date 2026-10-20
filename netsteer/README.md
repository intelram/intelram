# NetSteer: Wi-Fi + mobile data together, and ad blocking (Android, no server)

NetSteer is an on-device VPN that spreads your phone's connections across
**Wi-Fi and mobile data simultaneously**, so traffic that uses several
connections gets the combined speed of both. It runs entirely on the phone:
no remote server and no root.

While it is active you see the **VPN key** and NetSteer's **live-speed icon**
(e.g. `24` over `Mb/s`) in the status bar. The notification shows per-link
speeds and has a **Stop** button.

## What gets faster (and what doesn't)

| Traffic | Result |
|---|---|
| Web pages, app/game stores, social feeds (many connections) | Both links used at once → faster |
| Multi-thread download managers (e.g. ADM, IDM+), torrents | Both links used at once → faster |
| Speed tests in **multi-connection** mode | Shows the combined speed |
| One large download over a **single** connection, many video streams | One link's speed only (still benefits from failover) |
| Wi-Fi drops or mobile signal dies | New connections go to the surviving link automatically |

Why single connections can't be split: a TCP/QUIC connection is tied to one
source IP address. Wi-Fi and mobile have different public IPs, so splitting
one connection's packets across both requires a cooperating server
(MPTCP / bonding server). This project's constraint is *no server*, so it
combines **per connection**, which is the maximum possible on a stock phone.

## Ad & tracker blocking (AdGuard-style DNS filtering)

NetSteer blocks ads and trackers for **all apps** the way AdGuard's DNS
filtering does, with an extra network-level layer:

| Layer | What it catches |
|---|---|
| **Domain filter on every new connection** (names come from NetSteer's own DNS) | Ad/tracker servers in apps and browsers |
| **TLS SNI / HTTP Host check** for connections made to a bare IP address | Apps that use their own DNS, DNS-over-HTTPS, or hardcoded IPs |
| **DNS answers** for queries sent to other resolvers (e.g. `8.8.8.8:53`) | Ad domains get NXDOMAIN |
| **QUIC/UDP** to blocked names | Dropped; the app falls back to TCP and is blocked there too |

- Filter lists (downloaded on the phone over HTTPS, refreshed every 4 days, on Wi-Fi when possible):
  **AdGuard DNS filter** (~188k rules) and **Steven Black unified hosts** (~71k rules), plus a small built-in list
  so blocking works before the first download. AdGuard rule syntax supported: `||domain^`, `@@` exceptions,
  `$important`, `$badfilter`, hosts and plain-domain formats. Cosmetic, path and regex rules need page access
  and are skipped (they are <0.3 % of the AdGuard DNS filter).
- Compiled to sorted 64-bit hashes: ~1.6 MB RAM for ~240k rules, ~1 µs per lookup, cached on disk so it is not re-parsed on each start.
- **Allowlist** in the app for anything that breaks. Stats: blocked today/total and most-blocked domains.
- **No HTTPS decryption.** AdGuard's optional "HTTPS filtering" installs a certificate and decrypts traffic
  (man-in-the-middle). NetSteer deliberately does not: it is a security risk, and since Android 7 most apps
  ignore user certificates anyway. Consequence: ads served from the *same* servers as content (e.g. YouTube
  video ads) cannot be blocked, by NetSteer or by AdGuard without root.

## Battery

- **Smart combining (default on):** while Wi-Fi is fine, everything stays on Wi-Fi and mobile data is not
  even requested. Only when traffic stays above ~2 Mb/s for 3 s (a download, update, video loading) is
  mobile brought up and new connections spread over both; after 30 s of light traffic mobile stops taking
  new connections and is released 60 s later. Short connections never wake the mobile radio, which is what
  drains batteries.
- Screen off: stats sampled every 5 s and no notification redraws; screen on: redraw only when the text changes.
- Idle connections (push notifications) are kept for up to 2 h instead of being cut and re-established.
- No wakelocks, no polling timers in the relay; small thread stacks.

## How it works

```
apps ──▶ TUN interface (VpnService, 198.18.0.1, all IPv4)
     ──▶ hev-socks5-tunnel  (native user-space TCP/IP stack, MIT, pinned 2.18.0)
     ──▶ ProxyServer        (local SOCKS5 on 127.0.0.1, random per-session password)
     ──▶ LinkScheduler      picks Wi-Fi or mobile for each NEW connection
     ──▶ socket bound to that android.net.Network ──▶ internet
```

- **LinkScheduler** places each new connection on the link with the lowest
  `(active connections + 1) / measured peak speed`, never letting a link drop
  below 25 % of the best link's weight, so an unused link keeps getting tested.
  Two failed connects in a row put a link in a 15 s cool-down; a refused
  connection is not retried elsewhere because the destination said no.
- **DNS** is answered inside hev with mapped addresses (198.19.0.0/16), so the
  real name is resolved by NetSteer *on the link chosen for that connection*
  (correct CDN answers per link, no DNS leaks via the wrong network).
- **Mobile data stays up** while Wi-Fi is connected: NetSteer holds a
  `requestNetwork(TRANSPORT_CELLULAR)` request while active, and releases it on stop.
- NetSteer excludes itself from its own VPN, and every outbound socket is
  explicitly bound to its network with `Network.bindSocket`.
- **Compatibility mode** (toggle in the app) keeps each website on one link
  for 10 minutes, for sites that log you out when your IP changes.

## Source layout

```
netsteer/
├── core/            pure Kotlin, JVM-tested (no Android)
│   ├── Socks5.kt          SOCKS5 + hev UDP-in-TCP framing
│   ├── ProxyServer.kt     per-connection link choice, relay, failover, byte accounting
│   ├── LinkScheduler.kt   weighted least-load scheduling, cool-down, sticky mode
│   ├── TrafficMeter.kt    per-link rates and peaks
│   └── Diagnosis.kt       network fault classification
├── app/
│   ├── CombineVpnService.kt  VPN + tunnel lifecycle, live stats
│   ├── StatusNotifier.kt     notification + status-bar speed icon
│   ├── AndroidUplink.kt      Network-bound sockets
│   ├── NetworkMonitor.kt     Wi-Fi/mobile callbacks (+ mobile keep-alive request)
│   ├── tunnel/TProxy.kt      JNI binding to hev-socks5-tunnel
│   ├── MainActivity.kt       UI
│   └── src/main/jni/hev-socks5-tunnel   git submodule (pinned d9dca26 = 2.18.0)
└── ci/e2e.sh        emulator end-to-end test
```

## Build

```bash
git clone --recurse-submodules <repo>      # or: git submodule update --init --recursive
cd netsteer
./gradlew :core:test :app:assembleDebug     # needs Android SDK + NDK (AGP installs the NDK)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/netsteer-apk.yml`) runs the core tests, builds the APK
for arm64-v8a / armeabi-v7a / x86_64, runs `ci/e2e.sh` on an Android 14
emulator and only then publishes the APK as a GitHub pre-release.

## Verification

- `core` tests (JUnit): SOCKS5 codec, scheduler policy, and a proxy
  integration suite that speaks to `ProxyServer` exactly like hev does
  (auth, CONNECT by name/IP, UDP-in-TCP by IP/name, 1 MiB integrity with
  half-close, 10 parallel connections split 5/5, failover when a link is
  down, refused-not-retried, tunnel-range blocking, teardown).
- Emulator E2E: VPN comes up, 16 parallel HTTP requests succeed, **both
  Wi-Fi and mobile carry bytes**, notification present, stop removes it,
  restart in the same process works, Wi-Fi turned off → traffic continues
  on mobile, no crash in the crash buffer.

## Limitations and costs

- Uses mobile data while active (counted by your carrier as normal usage).
- More battery than normal: two radios stay active and traffic is relayed in user space.
- Takes Android's single VPN slot: it cannot run together with another VPN.
- IPv4 tunnel: apps get IPv4 answers. IPv6-only destinations are unreachable while active (rare).
- `ping` doesn't work while active (ICMP isn't relayed).
- Existing connections on a link that drops are cut; apps reconnect on the other link.
- Some apps refuse to work over any VPN (some banking/streaming apps).
- Debug-signed builds from CI. For a stable signature see "Release signing" below.

## Release signing

```bash
keytool -genkeypair -v -keystore netsteer-release.jks -alias netsteer -keyalg RSA -keysize 4096 -validity 10000
printf 'storeFile=netsteer-release.jks\nstorePassword=…\nkeyAlias=netsteer\nkeyPassword=…\n' > keystore.properties
./gradlew :app:assembleRelease
```

## Permissions

INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, plus the user's VPN consent.
No location, contacts, storage, phone-state or accessibility access.

Third-party code: hev-socks5-tunnel and its submodules (MIT/BSD), kotlinx-coroutines (Apache-2.0).
Filter lists are downloaded by the device from their publishers and are not bundled: AdGuard DNS filter (GPL-3.0), Steven Black hosts (MIT).
