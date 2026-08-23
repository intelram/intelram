# IntelRAM Shield

An on-device Android threat-scanning app. It enumerates installed apps and flags
risky ones using local heuristics — no data leaves the device and no backend is
required.

## What it checks

- **Installed apps** (`app/src/main/java/com/intelram/shield/scan/AppScanner.kt`)
  - Known-malware package name / APK SHA-256 matches against
    `ThreatIntel` (a small illustrative sample list — swap in a real,
    regularly-updated feed for production use).
  - Dangerous permission exposure and risky permission *combinations*
    (e.g. SMS + boot-persistence, overlay + accessibility-service — common
    banking-trojan / SMS-fraud patterns).
  - Sideloaded apps (unknown installer) requesting sensitive permissions.
- **Device posture** (`.../scan/DeviceScanner.kt`): root indicators, USB
  debugging, "install from unknown sources", missing screen lock.

Each app gets a 0-100 risk score and a `CRITICAL`/`HIGH`/`MEDIUM`/`LOW`/`CLEAN`
level; the dashboard rolls these up into an overall device score.

## Building

Requires JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0).

```bash
export ANDROID_HOME=/opt/android-sdk   # wherever the SDK is installed
./gradlew :app:assembleDebug           # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease         # unsigned release build
```

## Project layout

```
app/src/main/java/com/intelram/shield/
  scan/    ThreatIntel, AppScanner, DeviceScanner, ScanViewModel, ScanModels
  ui/      Compose screens (Dashboard, App detail) + theme
  MainActivity.kt
```
