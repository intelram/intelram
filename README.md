# Thread Protection — Android app

Native Android (Kotlin + Jetpack Compose, Material 3) recreation of the Thread Protection
consumer security app prototype: same 9 screens + hardware alert overlay, same design tokens,
typography, spacing, animations and copy — now backed by a **real scan pipeline** instead of
demo data (see below).

## Location permission — asked on launch, for a real reason

The app now asks for `ACCESS_FINE_LOCATION` right at startup, alongside the existing notification
and Bluetooth prompts (`MainActivity`'s launch `LaunchedEffect`) — this is what actually surfaces
Android's own three-way choice ("While using the app" / "Only this time" / "Don't allow") rather
than a two-option dialog, since the app only ever requests foreground location (never
`ACCESS_BACKGROUND_LOCATION`, which would change that flow and needs a much higher bar of
justification).

It's tied to something real, not asked blind: the Operating System detail screen's new "Network"
card reads the currently-connected Wi-Fi network's name (`scan/WifiInfo.kt`, via
`WifiManager.connectionInfo`) so you can see which network you're on — and Android ties that
specific reading to location permission on every version it supports, a platform rule this app
didn't choose. Deny it (or pick "Only this time" and let it lapse) and that card just says so
honestly instead of showing a fake network name.

## App icon

The launcher icon is a proper Android adaptive icon (foreground + background layers, one PNG
per density from `mdpi` to `xxxhdpi`, plus legacy square/round fallbacks) built from the
silver hexagonal-gem logo the user supplied, with its horizontal glowing scan-line and center
dot. That logo arrived as an inline image in chat, not a file on disk this session could read
byte-for-byte, so it's a faithful vector recreation (`design/app-icon.svg`, rasterized into
`app/src/main/res/mipmap-*/ic_launcher_foreground.png`) rather than the original pixels —
matching shape, facet shading and the green scan-line/dot glow, re-rendered at full resolution
for every density instead of scaling up a single small source image. Background layer is solid
black (`ic_launcher_background.xml`), per the user's request.

## Build & run

Requires Android Studio (or the command line with the Android SDK installed) — this repo has
no CI-baked SDK, so `sdk.dir` in `local.properties` needs to point at your local SDK.

```
./gradlew :app:assembleDebug
```

Minimum SDK 26, compiled/target SDK 35, Kotlin 2.0, Compose BOM 2024.12.

## Chat — offline, device-to-device, post-quantum encrypted

A **Chat** tab on the bottom nav, per the brief: works over Bluetooth with no internet, only
between phones that also have Thread Protection installed, encrypted to resist quantum attacks.

- **Bluetooth mode (real, working today)** — `BluetoothChatManager` uses classic Bluetooth RFCOMM:
  real device discovery (`BluetoothAdapter.startDiscovery()`), a real socket connection on an
  app-specific service UUID, and a handshake that only another instance of this app can complete
  (matching magic bytes, then a real key exchange) — a stray Bluetooth headset or someone else's
  phone without the app never gets past that handshake into a usable connection.
- **The encryption is real, not a label**: every session does a fresh **ML-KEM-768** key exchange
  (FIPS 203, the actual NIST-standardized post-quantum algorithm, via Bouncy Castle's
  implementation — not hand-rolled), derives a session key with HKDF-SHA256, and encrypts every
  message with **AES-256-GCM** (authenticated, so tampering is detected). A new key pair every
  connection means forward secrecy — no stored long-term key to ever leak.
  See `crypto/PqcChatCrypto.kt`.
- **WhatsApp-style thread**: message bubbles, timestamps, single/double delivery ticks (a real
  ACK sent back over the encrypted channel on receipt, not simulated), and a typing indicator.
- **Internet mode is honestly not available.** The toggle is there, but reaching an arbitrary
  other installed device via a random ID over the internet needs a server: something to register
  IDs against devices, resolve which one is currently reachable (phones move between networks and
  sit behind NAT — there's no way to open a direct connection with just a number), and store
  messages for offline recipients. That's a real backend that doesn't exist for this app. Rather
  than fake it, the screen says so plainly and points back to Bluetooth mode.
- **Live nearby scan, radar UI, real signal data** — tapping the radar (pulsing rings, Bluetooth
  glyph, "N devices found" counter) shows every device as Android's own `ACTION_FOUND` broadcast
  reports it, growing the list in real time. Each row shows a device-type icon from the peer's
  actual Bluetooth Class of Device (phone/computer/audio/wearable — read off the OS, not guessed
  from the name), plus a distance estimate and signal bars computed from its real RSSI reading via
  the standard log-distance path-loss model (`chat/SignalEstimate.kt`) — an industry-standard
  technique, honestly still an *estimate* (walls and orientation shift the reading), same caveat
  every commercial Bluetooth-finder app carries.
- **Chat History**: every device you've successfully connected to is remembered — address, name,
  last-chatted time — in `SettingsRepository.chatHistoryFlow` (persisted, newest first, capped at
  30). The Chat screen shows it compressed behind one "History" row; opening it lists every past
  conversation, and tapping one reconnects straight to that address without rescanning.
- **v1 scope, stated plainly**: one active *live* conversation at a time; live message text lives
  in memory only for that connection (nothing is written to disk beyond the History contact list
  and, for the mesh relay below, the encrypted envelopes still waiting for a carrier). The live
  chat socket itself still only runs while the Chat screen is open — but see below, since the mesh
  relay's own listener runs continuously.

### Out-of-range delivery: store-and-forward mesh relay

If you and the person you're messaging aren't in range of each other, but you're each in range of
*some* phone running Thread Protection — even a stranger's, even several hops apart — the message
now hops phone to phone until it reaches them. This is a real delay-tolerant-networking relay
(`chat/MeshRelayManager.kt`), the same technique real offline mesh-chat apps use, not a simulated
"queued" state:

- **Always-on, per your choice of how this should work**: `ProtectionForegroundService` — already
  alive continuously whenever real-time protection is on — starts the relay's accept loop and
  ticks it (brief Bluetooth discovery + a gossip exchange with anything nearby) every 90 seconds,
  independent of whether the Chat screen is even open. This is what actually lets a message hop
  through a phone whose owner isn't using the app at that moment.
- **Real end-to-end encryption, private from every relay**: each device generates a persistent
  ML-KEM-768 identity keypair on first use (`chat/MeshIdentity.kt`), exchanged automatically (over
  the already-encrypted channel) the first time you connect to someone directly. A message to that
  contact is sealed with a *fresh* KEM encapsulation against their long-term public key, then
  AES-256-GCM — so a relay carrying it can decrypt neither the content nor learn who sent it; only
  the addressed device can open it. Stated precisely rather than oversold: a relay *can* see which
  device a message is addressed to (unavoidable for routing without a full onion-routing layer,
  which is out of scope), and there's no delivery receipt across relay hops — only a live direct
  connection gets those. Envelopes expire after 24h if no carrier reaches the recipient.
- **From Chat History**: tapping a contact now opens the conversation immediately whether or not
  they're currently in range — a live connection is still attempted in the background (upgrading
  to instant 2-way chat if they happen to be nearby), and if that doesn't succeed, sending falls
  back to queuing via the mesh, shown honestly in the thread as "via nearby relay — no delivery
  confirmation" rather than a fake delivered tick.

## Scheduled scan — actually customizable

"Scheduled scans" in Settings used to be a toggle with a hardcoded, non-functional caption
("every day at 3:00 AM"). It's now real: tap "Change time" for a Material3 time picker plus
Daily/Weekly frequency (with a day-of-week picker for Weekly). `ScheduledScanWorker` runs the
real `DeviceScanner` pipeline in the background at that time and posts a notification only if it
finds something, then reschedules its own next run (WorkManager has no built-in "run at this
exact clock time" primitive). All Protection toggles plus the schedule now persist across
restarts via DataStore — previously none of them did.

## Fits every phone, doesn't overlap system UI

`enableEdgeToEdge()` was already being called (so the app can paint behind the status/nav bars
for a modern look), but nothing was actually padding content to account for that — every screen
was drawing straight under the status bar, the notch/cutout, and the navigation bar. Fixed:

- The root content `Box` in `MainActivity` now applies `WindowInsets.safeDrawing` (status bar +
  navigation bar + display cutout + IME) as padding, so no screen's content, back button, or
  bottom nav bar can ever sit underneath system UI — on any device, any cutout shape, any
  gesture-vs-3-button nav mode. The app's background colour still paints edge-to-edge behind
  those bars; only the actual content is inset.
- Status/navigation bar icon contrast now reactively follows the app's own day/night toggle
  (`SystemBarStyle.dark`/`.light`, updated via a `SideEffect` on theme change) instead of the
  phone's system theme — previously a user on "Night" mode with a light system theme would get
  dark-on-dark status bar icons.
- Locked to portrait (`android:screenOrientation="portrait"`): every screen here is a
  single-column phone layout ported from the design spec, and it fits correctly (no squish, no
  overlap) at every portrait resolution/density this way, rather than stretching a
  portrait-tuned design into landscape.
- Layout code already used `fillMaxWidth()`/`weight()`-based responsive rows throughout, with
  only a handful of `widthIn(max = ...)` caps (never fixed absolute widths) — so this is a
  correctness fix for system-bar overlap, not a rewrite of the layout system.

## What's real

- **Sign-in**: "Create an account" is a real, fully working local account (PBKDF2-SHA256
  hashed password, stored only on-device via DataStore — no backend). Google Sign-In is wired
  to Credential Manager for real; it needs a Google Cloud **web** OAuth client ID
  (`BuildConfig.GOOGLE_WEB_CLIENT_ID` in `app/build.gradle.kts`, currently blank) before it'll
  do anything — Google's own APIs require that to exist first, so until it's set the button
  uses a clearly-labelled demo account instead of pretending to authenticate.
- **"Scan now"**: `scan/DeviceScanner.kt` runs a real, deep device audit — **every** installed
  app via `PackageManager` (not a capped sample), real granted dangerous-permission audit per
  app with a plain-language "why" (cross-referenced with `UsageStatsManager` recency once you
  grant Usage Access), real install-source/sideload detection, real listening-socket
  enumeration (`/proc/net/tcp[6]`, where the OS allows it), real `Build.VERSION.SECURITY_PATCH`
  age, real USB/Bluetooth/charging state, and a live NVD CVE lookup for Android's WebView
  component. Nothing here is canned — on a clean phone it can legitimately report zero findings.
- **App permissions screen**: pulls the same real per-app audit independently (no full scan
  needed) — every app, every granted dangerous permission, real reasoning. Tapping an app opens a
  full detail screen: its real launcher icon, package name, and install source
  (`PackageManager.getInstallSourceInfo` mapped to a friendly label — Google Play Store, Amazon
  Appstore, "Unknown source (sideloaded)", etc.), a 0-100 safety rating computed from that app's
  own risky-permission count and install source (the same style of on-device score the dashboard
  already uses), a description for every single permission (not just a short label), and two
  action buttons. Both buttons are honest about the platform: "Manage access" and "Uninstall" both
  launch the real system confirmation screen (`ACTION_APPLICATION_DETAILS_SETTINGS` /
  `ACTION_DELETE`, the latter needing `REQUEST_DELETE_PACKAGES`) because no third-party app —
  this one included — can silently revoke another app's permission or delete it; that's reserved
  for the OS itself.
- **QR scanner**: real camera preview (CameraX) with on-device ML Kit barcode decoding — point
  it at a code and it decodes automatically, no fake staging. Every decoded (or sample) URL runs
  through a live reputation pipeline: on-device heuristics (punycode/homograph, IP-literal
  hosts, brand-impersonation, shorteners, suspicious TLDs — always on, no key needed) plus
  whichever free threat-intel sources you've configured.
- **Threat intelligence**: see Settings → *Threat intelligence sources*. Paste a free API key
  for any of these and it lights up immediately, used by both the QR checker and the scan:

  | Source | Free? | Used for |
  |---|---|---|
  | NVD (NIST) | Yes, no key needed (rate-limited harder without one) | CVE lookups |
  | Google Safe Browsing | Free key (Google Cloud Console) | Malware/phishing URL verdicts |
  | VirusTotal | Free key, 4 req/min | Multi-engine URL reputation |
  | AbuseIPDB | Free key, 1,000 checks/day | Malicious-host IP reputation |
  | URLhaus (abuse.ch) | Free key (`auth.abuse.ch`) | Malware-distribution URL database |
  | ThreatFox (abuse.ch) | Free key (`auth.abuse.ch`) | IOC (host/IP) reputation |
  | PhishTank | **Fully keyless** (verified live) — a free key just raises the rate limit | Phishing URL database |

  Have I Been Pwned is **not** integrated: its breach-lookup API stopped being free in 2024
  (paid from ~$4.39/mo). The free, keyless **XposedOrNot** breach-analytics API is used instead
  — see *Data breach security* below.
- **Scan a website — technical proof, not just a verdict**: every check runs the full pipeline
  above (PhishTank always, the keyed sources whenever configured) plus four more live, fully
  keyless lookups that need no API key at all — the same ones power the QR scanner's result
  screen:
  - **DNS + IP hosting** — resolves the real IP(s) on-device, then `ipwho.is` (free, keyless,
    HTTPS) for the hosting org/ISP/ASN/country. A "safe-looking" domain hosted by an unrelated
    org on the other side of the world is a real signal.
  - **Domain registration age (RDAP)** — `rdap.org`, the IETF/ICANN-mandated free successor to
    WHOIS, gives the real registrar and registration date. A domain registered days ago is one
    of the most reliable phishing signals there is, and factors directly into the verdict.
  - **Live TLS certificate** — the app actually connects to the site on port 443 and reads the
    real certificate off the handshake: issuer, validity dates, and whether Android's own trust
    store considers it valid. An untrusted/invalid certificate is treated as malicious.
  - **Live HTTP trace** — a real request to the URL, following redirects: final URL, status
    code, `Server` header, and the full redirect chain — cloaked or hop-through links show up
    here even when the pasted link itself looks clean.

  All of this is real-time and connection-based — nothing here is a database lookup pretending
  to be a live check.
- **System audit tiles are drill-downs, not just status dots**: tap *Installed software*
  (→ App Permissions), *Connected hardware*, *Open ports*, or *Operating system* on the
  dashboard and it opens a real detail screen for that category instead of doing nothing:
  - **Connected hardware** — every device `HardwareWatcher` found this scan, with status.
  - **Open ports** — every listening TCP socket from `/proc/net/tcp[6]`, now resolved to the
    real app that owns it via `PackageManager.getPackagesForUid()` (previously shown as a bare
    "uid 1000" — genuinely more useful now, not just wired up).
  - **Operating system** — real `Build.*` fields (manufacturer, model, Android version, security
    patch, kernel version, build fingerprint) plus a "Check for system updates" shortcut.
- **App Permissions**: (renamed from "What apps are allowed to do"). Every app's granted
  dangerous permissions, real reasoning, and — since Android does not let one app revoke
  another app's permissions (there's no such API, by design) — a "Change in system settings ›"
  row per app that deep-links straight to that app's real permission page
  (`ACTION_APPLICATION_DETAILS_SETTINGS`) so you can flip it yourself in one tap. Also available
  as an Android **Quick Settings tile** (Settings → *Add Quick Settings tile*, or drag it in
  from the notification shade's tile editor) that jumps straight to this screen.
- **OTP security**: lists every installed app holding `READ_SMS`/`RECEIVE_SMS`/`SEND_SMS` — the
  permissions an app would need to intercept one-time codes — with the same one-tap deep-link
  to review/revoke each in system settings.
- **Data breach security**: checks your signed-in email against the free **XposedOrNot**
  breach-analytics API (`api.xposedornot.com`, no key, no cost) and lists which breaches it
  appeared in, when, and what data was exposed.
- **Scan a website**: paste any URL and it runs through the same live reputation pipeline as
  the QR scanner (on-device heuristics + whichever free threat-intel sources you've configured)
  and returns a plain verdict.
- **Runs in the background**: a foreground service keeps watching for new USB/Bluetooth
  hardware and posts an alert notification the moment something connects, even after you close
  the app — persistent low-priority notification, survives a reboot (restarts itself via a boot
  receiver if you left real-time protection on), toggled by the same *real-time protection*
  switch on the dashboard.
- **2-step verification reminder**: Android has no way to see whether another app (your bank,
  email, etc.) has 2FA turned on — that state lives on that service's own server, not on the
  phone. Rather than fake a detector, a background job posts a periodic reminder notification
  every two weeks nudging you to check your important accounts.

## What's still demo/illustrative

- "Show me what an alert looks like" on the dashboard is an explicitly-labelled demo trigger
  for the hardware-alert overlay (the design's own intent), not a claim of a live detection.
- The AI brain screen's "learning loop" narrative and audit/certification claims are unchanged
  marketing copy from the original design — see its own handoff README for the warning not to
  ship those claims unless true.

## Project layout

- `ui/theme/`, `ui/components/`, `ui/screens/` — design system + all 10 screens (unchanged
  structure from the first build).
- `data/` — models + what's left of the seed data (ticker copy, brain-loop copy, QR sample
  URLs, hardware-alert demo scenarios).
- `state/` — `AppUiState`/`AppViewModel` (now holds real scan results, live hardware state, API
  keys) and `Derived.kt` (score/status/audit logic, now computed from real scan output).
- `scan/` — the real device-audit pipeline: `DeviceScanner`, `PermissionAudit`,
  `PermissionCatalog`, `UsageAccess`, `PortScanner`, `HardwareWatcher`, `OsPatchChecker`.
- `network/` — `ThreatIntelRepository` (aggregates every configured source into one verdict),
  `UrlHeuristics` (on-device, always-on checks), and one Retrofit interface per API.
- `auth/GoogleAuthClient.kt` — Credential Manager wiring, demo fallback when unconfigured.

## Note on this build

Built and verified with `./gradlew :app:assembleDebug` end-to-end (compiles clean, produces a
real installable `app-debug.apk`) in a sandbox with no hardware-accelerated virtualization, so
none of this could be visually spot-checked on an emulator or camera hardware here — install
the APK on a real device to see it running.
