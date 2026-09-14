# Threat Protection

An on-device Android security app matching the published "Threat Protection"
design (see the app's design canvas). It scans installed apps, device
network posture, and QR codes using local heuristics — no data leaves the
device unless you sign in with Google, and no paid third-party APIs are
called. A branded splash screen shows on every cold start.

## Screens

Onboarding → Sign In (Google) → Home / Dashboard → Scanning (animated) →
Results → Threat Detail (with a real Fix action) → QR Code Scanner →
Nearby Chat (device list → conversation) → Settings. Home, Nearby Chat,
Results, and Settings share a bottom nav bar.

## What it checks — for real

- **Installed apps** (`scan/AppScanner.kt`): known-malware package name / APK
  SHA-256 matches against `ThreatIntel` (a small illustrative sample list —
  swap in a real, regularly-updated feed for production use), dangerous
  permission exposure, risky permission *combinations* (SMS + boot
  persistence, overlay + accessibility — common banking-trojan patterns),
  sideloaded apps with sensitive permissions.
- **Network posture** (`scan/DeviceScanner.kt`): a no-VPN-on-Wi-Fi advisory
  via `ConnectivityManager` — real, not simulated.
- **QR codes** (`qr/`): CameraX + ZXing decode entirely on-device (no Play
  Services model download). See "QR link inspection" below for how a
  decoded link is actually judged.

Each finding carries a category, a plain-language explanation, and — where
one exists — a real **Fix Now** action: `Intent.ACTION_DELETE` for an
uninstall, or a deep link into the matching system Settings screen. Nothing
claims to be "resolved" until you actually complete that system flow.

Device checks that used to live under a "System & Software Audit" tile
(root, ADB, unknown sources, screen lock, security-patch age) were removed
by request — that whole option is gone, not just hidden.

### What's intentionally left out

Data-breach checking, a generic phishing/link scanner, and file/download
scanning are **not** wired to fabricated results — they'd need a real paid
API or backend this repo doesn't have credentials for. The dashboard only
advertises detection categories the code actually implements.

## Google Sign-In

`auth/AuthViewModel.kt` uses Android's real Credential Manager / Google
Identity Services APIs. It needs an OAuth 2.0 **Web** client ID from a
Google Cloud project you control, with this app's SHA-1 fingerprint
registered — see the comment in `res/values/strings.xml`
(`google_web_client_id`). Until that placeholder is replaced, Sign In runs
in a clearly-labeled offline demo mode instead of failing.

## Nearby Chat (Bluetooth)

`bluetooth/BluetoothChatManager.kt` is classic Bluetooth (RFCOMM), built to
avoid the standard "phone A finds phone B, but B never finds A" bug: **every**
device that opens Nearby Chat simultaneously (1) requests to be discoverable,
(2) actively scans (`startDiscovery()`), and (3) runs a listening server
socket — a symmetric, dual-role peer, not a fixed client/server split. Two
platform-specific pitfalls that commonly cause exactly that asymmetric
symptom are both handled:

- **Android 12+ (API 31+)**: uses the new `BLUETOOTH_SCAN` (declared with
  `neverForLocation`, since discovered devices are never used to infer
  location) / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` permissions.
- **Android 8–11 (API < 31)**: Bluetooth discovery silently returns nothing
  unless the app holds `ACCESS_FINE_LOCATION` **and** the system Location
  toggle is on — a very common cause of "it works on my phone but not
  theirs" when one tester's phone already had Location on. The screen
  detects this and shows a fix-it banner rather than failing silently.

Connections use secure (paired) RFCOMM sockets — consistent with a security
app's own brand — so the OS pairing dialog appears on first connect between
two devices. Bluetooth resources (the server socket, the discovery receiver)
are only held while a Nearby Chat screen is actually visible and are
released the moment you navigate away, so discovery never keeps running,
and battery draining, in the background.

## QR link inspection

A QR code's payload is judged **without ever rendering it** — no WebView, no
page load, no JavaScript execution. Opening a suspicious page even in an
embedded WebView isn't a sandbox: the device's real browser engine would
still run the page's script and could still be exploited by it, which
defeats the point of checking first. `qr/LinkInspector.kt` instead inspects
only network-level metadata, the same technique real browsers use for their
own link warnings:

1. `QrLinkHeuristic` — structural red flags in the URL text itself (raw-IP
   hosts, punycode, link shorteners, http instead of https, risky TLDs).
2. The link's actual redirect chain, resolved via response headers only —
   no response body is ever read, so nothing downloads.
3. Whether the final destination's TLS certificate is valid.
4. Google Safe Browsing's live threat-list lookup — the same database
   Chrome and Firefox check — **if** a free API key is configured (Google
   Cloud Console → enable "Safe Browsing API" → Credentials; see
   `safe_browsing_api_key` in `res/values/strings.xml`). Skipped, never
   faked, when the placeholder is still in place.

These signals combine into a **Safe / Caution / Unsafe** verdict shown
before the link ever opens, with the specific reasons and the final
destination displayed — not just a bare judgment.

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
  scan/        ThreatIntel, AppScanner, DeviceScanner, ScanViewModel, ScanModels
  auth/        AuthViewModel (real Google Sign-In + demo-mode fallback)
  qr/          QrAnalyzer (CameraX + ZXing), QrLinkHeuristic
  bluetooth/   BluetoothChatManager, BluetoothChatViewModel, BluetoothModels
  ui/theme/    Color, Type (Plus Jakarta Sans), Theme
  ui/components/  Shared buttons, toggle, score ring, bottom nav, badges
  ui/screens/  One file per screen
  MainActivity.kt  Navigation graph + splash screen wiring
```
