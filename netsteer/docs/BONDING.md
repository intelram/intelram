# Internet Bonding: feasibility, design and verification

This document covers the *Internet Bonding* feature (NetSteer 0.5): Wi-Fi discovery,
up to four user-chosen Wi-Fi sources, optional mobile data, and the strongest
connectivity improvement that is genuinely achievable on a standard,
non-rooted Android phone **without a remote server**.

## 1. Feasibility report

### 1.1 What "bonding" can mean

| Technique | What it does | Possible here? |
|---|---|---|
| **Failover** | One network carries traffic; another takes over when it fails | ✅ Yes |
| **Per-connection load balancing** | Each *new* connection goes to the best network; many parallel connections use several networks at once | ✅ Yes (this is what NetSteer does) |
| **Session/packet-level bonding** | One connection's packets are split over several networks and reassembled | ❌ Not without a remote endpoint |

### 1.2 Why true bonding needs a server

A TCP or QUIC connection is identified by its addresses. Wi-Fi and mobile have
different public IPs (different NATs), so packets of one connection sent over a
second network arrive at the website from an unknown address and are dropped.
Splitting one connection therefore needs a cooperating endpoint that reassembles
it: an MPTCP-capable server or a bonding relay. Most websites don't speak MPTCP,
stock Android doesn't expose MPTCP to apps, and adding MPTCP subflows needs
`CAP_NET_ADMIN`, which apps don't have. Without root and without a server,
**packet-level bonding is not possible**. NetSteer never reports it as achieved.

*Optional alternative (not implemented, needs your approval):* a self-hosted
bonding endpoint (e.g. an MPTCP proxy or a bonding VPN server on a VPS) would
allow single-connection aggregation. It costs a server, adds latency, and puts all
traffic through a third machine.

### 1.3 Several Wi-Fi networks at the same time

| Capability | Standard Android (app, no root) |
|---|---|
| Scan nearby networks (SSID, signal, band, security) | ✅ `WifiManager.startScan/getScanResults`. Needs location (and *Nearby devices* on 13+); throttled to 4 scans / 2 min in the foreground |
| Add a network the user authorises | ✅ `WifiNetworkSuggestion`: user enters the password, approves NetSteer's suggestions once; Android reports wrong passwords |
| Internet Wi-Fi connections at once | **1** on almost all phones. **2** only on Android 13+ phones whose Wi-Fi chip supports *multi-internet* (`isStaConcurrencyForMultiInternetSupported()`); the second is requested by band and **chosen by Android**, not by the app |
| Join a *specific* second SSID with internet | ❌ The specifier API for a chosen SSID creates a **local-only** connection with no internet, and on most phones it disconnects the main Wi-Fi |
| Force Android to switch the main Wi-Fi | ❌ Since Android 10 apps can't; NetSteer opens the system Wi-Fi panel instead |
| **Four Wi-Fi networks active simultaneously** | ❌ **Not possible on any standard phone.** The four added networks are a pool: the current one (and a second on multi-internet phones) is used; the others are standby/failover sources Android switches to |

Other independent links that *do* run simultaneously and are combined: **mobile
data**, a **USB Ethernet adapter**, **Bluetooth tethering**.

### 1.4 Works where

| Feature | Standard Android | Needs OEM / privileges / server |
|---|---|---|
| Wi-Fi scan, add up to 4 sources, status, wrong-password detection | ✅ | |
| Wi-Fi + mobile (+ Ethernet/Bluetooth) per-connection load balancing, failover, health | ✅ | |
| 2 internet Wi-Fi networks at once | Android 13+ multi-internet hardware only | OEM hardware/firmware |
| 3–4 internet Wi-Fi networks at once | | Not available on phones (multiple radios + root/OS changes) |
| Choosing which SSID becomes the second Wi-Fi | | System privileges (`NETWORK_SETTINGS`) |
| Single-connection (packet-level) bonding | | Remote bonding server, or root + MPTCP-capable endpoints |
| Second SIM data at the same time | | Carrier/system privileges (DSDA) |

## 2. Architecture and traffic flow

```
 apps ─▶ TUN (VpnService) ─▶ hev-socks5-tunnel (user-space TCP/IP) ─▶ ProxyServer (SOCKS5, 127.0.0.1)
                                                                          │  ad filter (unchanged)
                                                                          ▼
                       LinkScheduler: per NEW connection, lowest (throughput + ramp) / capacity
                       among eligible links  ◀── HealthBook (latency, loss)  ◀── BoostController / mobile toggle
                                                                          ▼
                   socket bound to android.net.Network:  Wi-Fi │ Wi-Fi 2* │ Mobile │ Ethernet │ Bluetooth
                                                         (* multi-internet phones only)
```

| Component | File | Role |
|---|---|---|
| Wi-Fi scanning & discovery | `app/.../WifiScanner.kt` | Permissions, throttling, location-off handling, strongest AP per SSID |
| Connection selection & management | `app/.../WifiConnectionManager.kt`, `core/.../Bonding.kt` (`WifiSelection`) | Max 4, duplicates/unsupported rejected, suggestions, connect/disconnect/retry/remove, failure listener |
| Cellular monitoring | `app/.../LinkRegistry.kt`, `NetworkMonitor.kt` | Mobile state & reasons (data off, no SIM, connecting…), keep-alive request |
| Concurrency capability detection | `CombineVpnService.manageDualWifi`, `BondingActivity` | `isStaConcurrencyForMultiInternetSupported`, second-Wi-Fi request |
| Traffic scheduling & routing | `core/.../LinkScheduler.kt`, `ProxyServer.kt` | Per-connection placement, retry on another link |
| Throughput measurement | `core/.../TrafficMeter.kt`, `app/.../SpeedTest.kt` | Real bytes per link; per-link and combined speed test |
| Health monitoring & failure recovery | `core/.../Bonding.kt` (`HealthBook`), service health job | Latency/loss probes; failing links held back; cool-down after connect failures |
| Permissions & consent | `WifiScanner`, `BondingActivity`, `MainActivity`, `QuickTile` | Runtime permissions only on Scan; mobile-data cost consent |
| UI & status | `BondingActivity.kt`, `MainActivity.kt`, `StatusNotifier.kt` | Mode, measured throughput, per-link speed/health, warnings |

## 3. Files changed for this feature

New: `BondingActivity.kt`, `WifiScanner.kt`, `WifiConnectionManager.kt`,
`core/Bonding.kt`, `core/BondingTest.kt`, `docs/BONDING.md`.
Modified: `CombineVpnService.kt` (health job, mobile toggle, mode), `LinkRegistry.kt`
(held-back reasons, RTT/loss), `Settings.kt` (`useMobile`, `mobileConsent`),
`MainActivity.kt` (bonding entry, consent, debug hooks), `QuickTile.kt` (consent),
`NetSteerApp.kt`, `AndroidManifest.xml`, `ci/e2e.sh`. Existing features (combining,
ad blocking, battery saver, speed test, exclusions, tile) are unchanged.

## 4. Permissions and dependencies

| Permission | Why | When asked |
|---|---|---|
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Android only returns Wi-Fi scan results / current SSID with location access | Only when the user taps **Scan Wi-Fi** |
| `NEARBY_WIFI_DEVICES` (Android 13+) | Wi-Fi scanning on Android 13+ | Same |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Scan, network suggestions, dual Wi-Fi request | Install-time (normal) |
| Existing: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`, VPN consent | | |

NetSteer never reads or stores the device location. No new Gradle dependencies.

## 5. Build

```bash
git clone --recurse-submodules <repo> && cd netsteer
./gradlew :core:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. Real-device test plan

| # | Scenario | Steps | Expected |
|---|---|---|---|
| 1 | No Wi-Fi nearby | Scan in a shielded room / Wi-Fi off | "No Wi-Fi networks found" / "Wi-Fi is turned off" |
| 2–5 | 1, 2, 3, 4 added networks | Add each, entering passwords | Each listed; current one "Connected · internet verified"; others "standby"/"not in range" |
| 6 | Fifth network | Add another | Rejected: "at most 4" |
| 7 | Wrong password | Add your network with a wrong password, then *Switch now* to it | Status becomes **Wrong password**; *Retry* asks again |
| 8 | Wi-Fi without internet | Router with WAN unplugged | "Connected · no internet"; traffic moves to mobile |
| 9 | Mobile data off / no SIM | Android settings | Mobile shows the exact reason; Wi-Fi only |
| 10 | Wi-Fi drops during use | Walk away / turn router off | New connections move to mobile within seconds |
| 11 | Mobile drops | Airplane mode with Wi-Fi on | Continues on Wi-Fi |
| 12 | Permission denied | Deny location on Scan | Explanation; "Add a network by name" still works |
| 13 | Different Android versions | Android 10/12/13+/multi-internet phone | Capability line shows 1 or 2 simultaneous Wi-Fi |
| 14 | Background / restart | Leave app, reboot with Always-on VPN | Service keeps running / restarts |
| 15 | Throughput vs single | *Run speed test* | Per-link and "Together" figures from real downloads |
| 16 | Multiple links really used | Large multi-connection download | Both rows show traffic; "Measured now" ≈ sum of active rows |

## 7. Known limitations

- Not packet-level bonding (see §1.2). A single download stream gets one network's speed.
- At most 1 internet Wi-Fi (2 on multi-internet phones) at a time; extra added networks are standby.
- Android decides when to join a suggested network; NetSteer can't force a switch.
- Android may ask the user once to allow NetSteer's suggested networks (notification).
- WEP and Enterprise (802.1X) networks can't be added from this screen.
- Scan results need location access (an Android rule) and are throttled.
- Mobile data costs money on many plans: the app asks once and offers *Wi-Fi only*.

## 8. Verification report

See the section appended below from the CI run on an Android 14 emulator
(which has one Wi-Fi network and one mobile network). Real-device results
(multi-internet hardware, wrong password, router without internet, throughput
gains over separate physical links) are **not verified** by CI and must be
checked on a phone using §6.
