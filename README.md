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
  | PhishTank | Free key | Phishing URL database |

  Have I Been Pwned is **not** integrated: its breach-lookup API stopped being free in 2024
  (paid from ~$4.39/mo). Rather than fake it, the email-breach finding from the original
  prototype was dropped entirely — nothing here claims to check your email against breaches.

## What's still demo/illustrative

- Revoking another app's permission isn't possible on Android without being that app — the
  toggle here flips local "turned off by you" state (matches the design spec); wiring it to
  `ACTION_APPLICATION_DETAILS_SETTINGS` deep-links per real package is the natural next step.
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
