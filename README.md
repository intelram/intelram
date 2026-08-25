# Thread Protection — Android app

Native Android (Kotlin + Jetpack Compose, Material 3) recreation of the Thread Protection
consumer security app prototype: same 9 screens + hardware alert overlay, same design tokens,
typography, spacing, animations and copy — now backed by a **real scan pipeline** instead of
demo data (see below).

## Build & run

Requires Android Studio (or the command line with the Android SDK installed) — this repo has
no CI-baked SDK, so `sdk.dir` in `local.properties` needs to point at your local SDK.

```
./gradlew :app:assembleDebug
```

Minimum SDK 26, compiled/target SDK 35, Kotlin 2.0, Compose BOM 2024.12.

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
  needed) — every app, every granted dangerous permission, real reasoning.
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
