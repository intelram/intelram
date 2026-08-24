# Thread Protection — Android app

Native Android (Kotlin + Jetpack Compose, Material 3) recreation of the Thread Protection
consumer security app prototype, built to the handoff spec: same 9 screens + hardware alert
overlay, same design tokens, typography, spacing, animations, copy and demo data.

## Build & run

Requires Android Studio (or the command line with the Android SDK installed) — this repo has
no CI-baked SDK, so `sdk.dir` in `local.properties` needs to point at your local SDK.

```
./gradlew :app:assembleDebug
```

Minimum SDK 26, compiled/target SDK 35, Kotlin 2.0, Compose BOM 2024.12.

## Project layout

- `ui/theme/` — `TpPalette` (the full day/night token table from the design), typography (bundled
  Space Grotesk, `res/font/`), shapes/spacing.
- `data/` — `Models.kt` (Finding, HwDevice, Feed, PermApp, …) and `DemoData.kt`, which holds every
  finding, breach, feed, permission and phase **verbatim** from the design's `MASTER_THREATS`,
  `HW_SIM`, `emailFinding()`, etc.
- `state/` — `AppUiState` (mirrors the prototype's `state` object 1:1), `AppViewModel` (actions +
  the ambient timers: blocked/learned counters, sign-in ticker, scan/QR progress), `Derived.kt`
  (pure functions ported from the prototype's `renderVals()` — score, status, audit areas, …).
- `ui/components/` — shared pieces: pill buttons, the 62×36 toggle switch, conic progress rings,
  blink/pulse/orbit/radar/sweep-line animations, severity badges, bottom nav.
- `ui/screens/` — one file per screen (Sign in, Onboarding, Dashboard, Scanning, Results, Threat
  detail, QR scanner, AI brain, App permissions, Settings) plus the hardware alert overlay.
- `auth/GoogleAuthClient.kt` — real Google Sign-In via Credential Manager, gated behind
  `BuildConfig.GOOGLE_WEB_CLIENT_ID`; the Sign in screen falls back to the same demo account the
  prototype uses whenever that's blank.

## What's demo data vs. what's real

Every number and finding shown is the prototype's demo data — this app is a faithful UI/UX and
interaction clone, not a security product. Real: theme + signed-in account persist via DataStore
across launches (matching the prototype's `localStorage` use), Google Sign-In is wired to
Credential Manager, day/night theming is fully live. Not real (same as the prototype — see its
own handoff README's "Backend requirements" section for what production wiring each one needs):
malware/app scanning, port probing, breach lookups, hardware-watch detection, and app-permission
enforcement (Android doesn't allow revoking another app's permission programmatically — the
switch here flips local demo state; production needs `ACTION_APPLICATION_DETAILS_SETTINGS`
deep-links per app, per the design spec).

## Note on this build

This was built and verified with `./gradlew :app:assembleDebug` end-to-end (compiles clean,
produces a real installable `app-debug.apk`) in a sandbox without hardware-accelerated
virtualization, so it could not be visually spot-checked on an emulator here — install the APK
on a device to see it running.
