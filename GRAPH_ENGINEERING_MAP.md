# Thread Protection — Graph Engineering Map

**Purpose.** This is the navigation layer for this codebase. Before making a change, consult this
file to identify the affected components, then read only those files. Do not re-survey the codebase
from scratch — this map is kept current (see §11, maintenance rule).

**Last verified against:** the commit adding live Wi-Fi network security assessment to the scan
(§7.28), 2026-09-05. ~110 Kotlin files, 170 JVM unit tests (all passing, all offline — no
device/emulator/`adb` exists in this environment; nothing in this app has ever been run on real
hardware).

**Stack.** Kotlin, Jetpack Compose (Material3), single-Activity MVVM. `minSdk 26 / targetSdk 35 /
compileSdk 35`. No backend server for the core app — every consumer-facing feature is on-device or
talks directly to a third-party API from the client. `applicationId com.threadprotection.app`.

**Two architectures coexist on purpose.** The 22 original screens (§3) use one hand-wired
`AppViewModel`/`AppUiState` with manual DI (`by lazy`, `getInstance()` singletons) and DataStore
persistence — untouched, and the intended pattern for anything that's a personal
device-security feature. **Analyst Mode** (`analyst/` package, §2) is a separate SOC-analyst
vulnerability-intelligence add-on with its own Hilt DI graph and Room database, reached only via
Settings → "Analyst Tools", added because a request to build a full IOC/ATT&CK/dark-web/case-
management platform was scoped down to "add it as a separate mode, keep everything else
untouched" rather than replacing the app. Do not blur these two: a change to a §3 screen never
needs Hilt; a change inside `analyst/` never touches `AppUiState`.

---

## 1. Architecture at a glance

```
MainActivity (single Activity, Compose NavHost-by-when)
    │
    ▼
AppViewModel  ──observes/updates──▶  AppUiState (one big immutable data class, StateFlow)
    │  (all screens read this one state object; all user actions call one ViewModel function)
    │
    ├─▶ SettingsRepository (DataStore Preferences) ── persistence for everything durable
    ├─▶ DeviceScanner ─┬─▶ PermissionAudit, HardwareWatcher, OsPatchChecker, PortScanner
    │                  └─▶ ThreatIntelRepository (NVD CVE lookup during scan)
    ├─▶ ThreatIntelRepository ── URL reputation aggregator (QR + Scan Website features)
    ├─▶ QrContentClassifier ── pure on-device payload classifier (no state, no I/O)
    ├─▶ BluetoothChatManager (process singleton) ── BLE discovery + RFCOMM chat + PQC crypto
    ├─▶ MeshRelayManager (process singleton) ── store-and-forward relay over a 2nd RFCOMM channel
    └─▶ ExternalDeviceMonitor (process singleton) ── USB/Bluetooth ACL connection broadcasts

ProtectionForegroundService — owns BluetoothChatManager + MeshRelayManager in the background
                              (keeps chat reachable after the user leaves the Chat screens)
```

**The one rule that explains most of the ViewModel:** every screen is a pure function of
`AppUiState`; every user action is a ViewModel method that updates that one `MutableStateFlow`.
There is no per-screen state holder. `Derived` (state/Derived.kt) holds pure computed-from-state
functions (score, active threats, grouping) — screens call `Derived.x(state)` rather than
recomputing inline, and the ViewModel calls the same functions, so UI and business logic can never
disagree about e.g. what counts as an "active threat".

---

## 2. Package map

| Package | Role | Key files |
|---|---|---|
| `state/` | The MVVM core | `AppViewModel.kt` (1354 lines — all actions), `AppUiState.kt` (the state shape + `Screen` enum), `Derived.kt` (pure computed views), `BackStackRules.kt` |
| `ui/screens/` | One Composable per `Screen` enum value, plus overlays | 30 files, see §4 |
| `ui/components/` | Shared widgets (buttons, rings, camera preview, animations) | `QrCameraPreview.kt` is the only one with real device I/O (CameraX) |
| `ui/theme/` | Colors, type, shapes, day/night palette | `TpPalette.kt`, `Theme.kt` |
| `data/` | Core domain models + persistence | `Models.kt` (Finding, Account, PermApp…), `SettingsRepository.kt` (DataStore), `FindingIdentity.kt` (resolved-threat fingerprinting), `DemoData.kt` |
| `scan/` | The "Scan Now" pipeline | `DeviceScanner.kt` (orchestrator), `PermissionAudit.kt`, `HardwareWatcher.kt`, `OsPatchChecker.kt`, `PortScanner.kt`, `PermissionCatalog.kt`, `PermissionState.kt`, `UsageAccess.kt` |
| `network/` | URL/breach reputation | `ThreatIntelRepository.kt` (aggregator), one file per API client (NVD, VirusTotal, SafeBrowsing, URLhaus, ThreatFox, PhishTank, AbuseIPDB, RDAP, IPInfo, XposedOrNot), `UrlHeuristics.kt` (on-device signals), `TechnicalInspector.kt` |
| `qr/` | QR payload understanding | `QrContentClassifier.kt` (13-format classifier, pure function), `QrContent.kt` (models) |
| `chat/` | Bluetooth chat feature | `BluetoothChatManager.kt` (1215 lines — BLE+RFCOMM+state machine), `ChatModels.kt`, `ChatStateRules.kt` (pure rules, unit-testable), `MeshRelayManager.kt`, `MeshEnvelope.kt`, `MeshIdentity.kt`, `MeshPayload.kt`, `SignalEstimate.kt` |
| `crypto/` | `PqcChatCrypto.kt` — ML-KEM-768 + HKDF + AES-256-GCM for chat |
| `hardware/` | Real external-device watching | `ExternalDeviceMonitor.kt` (USB/BT broadcast receiver, singleton), `ExternalDevice.kt` (models + risk assessor) |
| `service/` | Background components | `ProtectionForegroundService.kt`, `ChatRequestActionReceiver.kt`, `BootReceiver.kt`, `NotificationHelper.kt`, `ScheduledScanWorker.kt` / `TwoFactorReminderWorker.kt` (WorkManager) |
| `tile/` | `AppPermissionsTileService.kt` — Quick Settings tile |
| `auth/` | `GoogleAuthClient.kt` — Credential Manager sign-in (demo mode if no web client ID) |
| `analyst/` | **Analyst Mode** — SOC-analyst CVE/EPSS/KEV lookup, own Hilt graph + Room DB. `di/AnalystModule.kt` (DI), `data/remote/EpssApi.kt` (new client), `data/local/` (Room: `AnalystDatabase`, `CveDao`, `WatchedCveEntity`), `data/repository/CveRepository.kt`, `data/CveWatchlistSyncWorker.kt` (daily KEV re-check, plain `CoroutineWorker` like the rest of `service/`), `domain/PriorityScoring.kt` (pure composite-risk formula, unit-tested), `domain/model/CveModels.kt`, `domain/usecase/CveUseCases.kt`, `presentation/cve/CveViewModel.kt` (`@HiltViewModel`) + `presentation/cve/ui/` (2 Compose screens). Reuses the existing `network/NvdApi.kt` (extended additively — see §7.10) and `network/NetworkModule`'s shared OkHttp client; does **not** duplicate them. |

---

## 3. Screen graph (`Screen` enum ↔ MainActivity `when`)

`Screen` enum (state/AppUiState.kt:25): `SPLASH, SIGNIN, CREATE_ACCOUNT, ONBOARDING, DASHBOARD,
SCANNING, RESULTS, DETAIL, QR, BRAIN, PERMS, SETTINGS, OTP_SECURITY, DATA_BREACH, SCAN_WEBSITE,
HARDWARE_DETAIL, PORTS_DETAIL, OS_DETAIL, CHAT, CHAT_CONVERSATION, CHAT_HISTORY, CHAT_SESSION,
APP_PERMISSION_DETAIL`.

`Screen.isChatFeature` = CHAT / CHAT_CONVERSATION / CHAT_HISTORY / CHAT_SESSION — leaving all four
tears down the BLE radio (`releaseChatRadioIfLeaving` in AppViewModel).

Navigation is **not** Jetpack Navigation — it's a `when(state.screen)` in `MainActivity.kt` (line
~215–494) plus a manual `backStack: List<Screen>` in `AppUiState`, governed by `BackStackRules.kt`
(pure, unit-tested: `push`, `peek`, `pop`, `isBackStackable`, `MAX_DEPTH`).

| Screen | Composable file | ViewModel entry point |
|---|---|---|
| SPLASH | `SplashScreen.kt` | `finishSplash()` |
| SIGNIN | `SignInScreen.kt` | `signInWithGoogle()`, `goCreateAccount()` |
| CREATE_ACCOUNT | `CreateAccountScreen.kt` | `createAccount()`, `backToSignIn()` |
| ONBOARDING | `OnboardingScreen.kt` | `completeOnboarding()` |
| DASHBOARD | `DashboardScreen.kt` | hub — routes to nearly everything below |
| SCANNING | `ScanningScreen.kt` | `startScan()` (screen just observes `scanPhase`/`scanFeed`) |
| RESULTS | `ResultsScreen.kt` | `Derived.fixProgress/threatsBySeverity`, `startFixing()`, `openFinding()` |
| DETAIL | `ThreatDetailScreen.kt` | `fixSelected()`, `beginFix()`, `ignoreSelectedFinding()`, `unresolveFinding()`, `voteUp/Down()` |
| QR | `QrScannerScreen.kt` (+ `QrCameraPreview.kt`) | `startQr()`, `analyzeScannedPayload()`, `toggleQrTorch()` |
| BRAIN | `AiBrainScreen.kt` | mostly `DemoData` display |
| PERMS | `AppPermissionsScreen.kt` | `refreshPermissions()`, `openAppPermissionDetail()` |
| APP_PERMISSION_DETAIL | `AppPermissionDetailScreen.kt` | reads `PermApp` from `scanData.permApps` |
| SETTINGS | `SettingsScreen.kt` | `toggleTheme`, `toggleProtectionSetting`, `setScheduledScan*`, `setApiKey` |
| OTP_SECURITY | `OtpSecurityScreen.kt` | `goOtpSecurity()` |
| DATA_BREACH | `DataBreachScreen.kt` | `checkMyBreaches()` → `ThreatIntelRepository.checkEmailBreaches` |
| SCAN_WEBSITE | `ScanWebsiteScreen.kt` | `checkWebsite()` → `ThreatIntelRepository.checkUrl` |
| HARDWARE_DETAIL | `HardwareDetailScreen.kt` | `state.liveHwDevices` + `state.externalDevices` (real connection log) |
| PORTS_DETAIL | `OpenPortsScreen.kt` | `state.scanData.ports` |
| OS_DETAIL | `OperatingSystemScreen.kt` | `state.scanData.osPatchLabel` |
| CHAT | `ChatScreen.kt` (608 lines) | `goChat`, `startBtDiscovery`, `connectToBtDevice`, `retryBtConnect` |
| CHAT_CONVERSATION | `ChatConversationScreen.kt` | `sendChatMessage`, `setChatDraft`, `disconnectChatPeer` |
| CHAT_HISTORY | `ChatHistoryScreen.kt` | `messageFromHistory`, `openStoredSession`, `clearStoredSessions` |
| CHAT_SESSION | `ChatSessionScreen.kt` | read-only transcript viewer, `closeStoredSession` |

**Global overlays** (rendered above the current screen in `MainActivity`, not part of the `when`):
`IncomingChatRequestOverlay.kt` (state.incomingChatRequest), `ExternalDeviceAlertOverlay.kt`
(state.deviceAlert), `HardwareAlertOverlay.kt` (state.hwAlert — demo-only canned data).

**Analyst Mode screens** (added on top of the 22 above, reached only from Settings → "Analyst
Tools" — see §2). Routed through the same `Screen` enum/`when` in `MainActivity` so the existing
back-stack (`BackStackRules`) works for them for free, but each Composable takes its own Hilt
`CveViewModel` instead of the outer `AppViewModel`:

| Screen | Composable file | Backed by |
|---|---|---|
| ANALYST_CVE_SEARCH | `analyst/presentation/cve/ui/CveSearchScreen.kt` | `CveViewModel.searchState` |
| ANALYST_CVE_DETAIL | `analyst/presentation/cve/ui/CveDetailScreen.kt` | `CveViewModel.detailState` |

Both screens share one `CveViewModel` instance, obtained once in `MainActivity` (`val
cveDetailViewModel = hiltViewModel()`, declared right above the screen `when`) and passed to both
— **not** two independent `hiltViewModel()` calls. Search results and the open CVE's detail are
deliberately two separate `StateFlow`s inside that one ViewModel (`searchState`/`detailState`, not
one shared `uiState`) specifically so opening a CVE's detail can't blank out the search results
still sitting behind it when the analyst navigates back — see §7.11.

---

## 4. `AppUiState` field map (state/AppUiState.kt)

One big immutable data class, grouped by feature (grep for the field name in AppViewModel to find
every writer):

- **Navigation:** `screen`, `backStack`
- **Auth:** `account`, `gsiError`, `createAccountError`
- **Scan pipeline:** `progress`, `scannedCount`, `scanData` (ScanData: findings/permApps/hwDevices/
  ports/osPatchLabel/feeds), `scanPhase`, `scanFeed`, `hasScanned`, `liveHwDevices`. `scanData`
  (minus `permApps`) + `hasScanned` are seeded from disk at cold start — see §7.5b — so the
  Dashboard's score/status is the user's real last scan, not "scan needed", on every launch.
- **Threat resolution (persisted):** `fixed`, `resolvedRecords`, `resolvedCategories`,
  `everResolvedIds`, `reEmergedIds`, `fixInProgressId`, `ignoredFindings`, `ignoredRecords`,
  `ignoredCategories`, `everIgnoredIds` — "Ignore for now" mirrors "Fixed" exactly (persisted via
  `SettingsRepository.ignoredFindingsFlow`/`tp_ignored_findings`, fingerprint-matched by
  `FindingIdentity`, reactivates only if the underlying finding actually changes). See §7.5.
- **Settings:** `realtime`, `settings: ProtectionSettings`, `theme`, `apiKeys` (`ApiKeys`/`ApiKeyId`
  defined in `data/SettingsRepository.kt`, not `Models.kt`)
- **QR:** `qrPhase`, `qrIndex`, `qrProgress`, `qrVerdict` (network reputation result, URL-only),
  `qrAnalysis` (on-device classification, always populated), `qrTorchOn`
- **Data breach / website check:** `breachResult`, `breachChecking`, `websiteUrl`,
  `websiteVerdict`, `websiteChecking`
- **Hardware demo overlay (canned):** `hwAlert`, `hwIdx`, `hwHandled`, `hwOpen`
- **External devices (real):** `externalDevices`, `deviceAlert`, `deviceTrust`,
  `bluetoothWatchBlind`
- **Chat — connection:** `chatMode`, `btConnState`, `btDiscoveredDevices`, `btScanning`,
  `btFailureReason`, `btCanAdvertise`, `btAdvertisePermissionMissing`, `btConnectingAddress`,
  `chatError`, `incomingChatRequest`
- **Chat — conversation:** `chatPeerName`, `chatMessages`, `chatSafetyCode`, `chatPeerTyping`,
  `chatDraft`, `chatMeshPeer`
- **Chat — persistence:** `chatHistory`, `chatSessions`, `viewingSession`, `activeSessionId`,
  `activeSessionStartedAtMs`
- **Misc display:** `selectedId`, `selectedPermApp`, `learned`, `votes`, `blocked`, `tickIdx`

---

## 5. Data flow: the four real pipelines

### 5a. Scan Now (Dashboard → Scanning → Results)
```
AppViewModel.startScan()
  → DeviceScanner.scan(apiKeys, onPhase)          [scan/DeviceScanner.kt:56, 7 phases]
      ├─ PermissionAudit.audit()                  → sideload + risky-permission findings
      ├─ HardwareWatcher.scan()                    → HwDevice list
      ├─ PortScanner (via DeviceScanner)            → PortFinding list
      ├─ OsPatchChecker.current()/finding()         → patch-age finding
      └─ ThreatIntelRepository.searchCves() (NVD)   → WebView CVE finding
  → result: ScanResult { findings, permApps, hwDevices, ports, osPatchLabel,
                         coveredCategories }        ◀── coveredCategories gates §5b
  → AppUiState.scanData updated, then .withResolvedApplied()
```

### 5b. Threat resolution persistence (the "don't show it again" system)
```
FindingIdentity.fingerprintOf(Finding)              = severity band + type line ONLY
  (deliberately excludes Finding.risk — it drifts on its own, see §7 pitfall)

fixSelected() → SettingsRepository.markFindingResolved(id, fingerprint, category)
  → DataStore key RESOLVED_FINDINGS → StoredResolvedFinding{id, fingerprint, resolvedAtMs,
    category, cleared}

Every scan completion:
  FindingIdentity.stillResolved(findings, resolvedRecords)   → AppUiState.fixed
  FindingIdentity.clearedRecords(...)      → records confirmed gone (only for
                                              Category ∈ ScanResult.coveredCategories)
                                              → SettingsRepository.markFindingsCleared()
  FindingIdentity.reEmerged(...)           → AppUiState.reEmergedIds (shown as "⟳ came back")
  FindingIdentity.supersededRecords(...)   → escalated findings → clearResolvedFindings()
```
Tests: `data/FindingIdentityTest.kt` (31 cases). **This is the most-amended logic in the app —
read §7 before touching it.**

### 5c. QR scan (QR screen)
```
QrCameraPreview (CameraX, off-main-thread ImageAnalysis) → ML Kit Barcode → raw string
  → AppViewModel.analyzeScannedPayload(raw)
      → QrContentClassifier.classify(raw)   [pure, on-device, no I/O]
          → QrAnalysis { type, parsedFields, explanation, risk, urlToCheck: String? }
      → AppUiState.qrAnalysis = result                       (always set)
      → if (urlToCheck != null): ThreatIntelRepository.checkUrl(urlToCheck)
          → AppUiState.qrVerdict = result                    (only for URL payloads)
```
**Invariant, enforced by `QrContentClassifierTest`:** `urlToCheck` is null for every one of 13
non-web formats (Wi-Fi, vCard, otpauth://, tel:, sms:, geo:, payment, etc.) — nothing but a genuine
web URL is ever sent to a network reputation API.

### 5d. URL reputation aggregation (used by QR + Scan Website)
```
ThreatIntelRepository.checkUrl(url, apiKeys)   [network/ThreatIntelRepository.kt:74]
  parallel fan-out (coroutineScope) to whichever of these have a configured key:
    safeBrowsingSignal / virusTotalSignal / urlhausSignal / threatFoxSignal /
    phishTankSignal / abuseIpdbSignal    (each wrapped in withGuard — failures don't crash)
  + on-device: UrlHeuristics.analyze() (typosquatting, punycode, shorteners, brand lookalikes)
  + TechnicalInspector (TLS/domain-age/redirect-chain/DNSSEC) via RdapApi / IpInfoApi / DnsApi —
    always runs, no key needed
  + ContentInspector (page-content phishing heuristics) — fetches the page the link actually lands
    on (post-redirect), always runs, no key needed
  → UrlVerdict { overall, confidence, signals: List<UrlSignal>, onDeviceFlags, technical, content }
```
Every API client in `network/` is a thin Retrofit/OkHttp wrapper, one file each; `ApiKeys` (user's
own free-tier keys, entered in Settings) gates which of the *reputation-list* sources fire — the
technical/content checks below need no key at all and always run.

The verdict itself is computed by **`UrlVerdictScoring.evaluate()`** (pure, unit-tested), not by
counting signals — see §7.27 for the false-positive epidemic that replaced.

**What each technical/content signal actually checks — all free, all keyless, all real (§7.24):**
- **Cloudflare security DNS** (`threatDnsSignal` + `ThreatDnsApi`) — the *only* real
  threat-intelligence source here that needs no API key, and therefore the only one most users
  ever have working. Cloudflare's malware/phishing resolver answers `0.0.0.0`/`::` with an RFC 8914
  Extended DNS Error 16 ("Censored") for domains it classifies as malicious. `DEFINITIVE` in both
  directions: a block ends the verdict at MALICIOUS, a clean answer is real positive evidence.
- **Domain age** (`domainAgeSignal`) — RDAP registration date; a domain registered days ago is one
  of the strongest, most standard phishing signals.
- **TLS certificate** (`tlsSignal`) — live handshake on port 443; trust, issuer, and (via
  `TechnicalDetailsCard`) days until expiry.
- **Redirect chain** (`redirectChainSignal` + `TechnicalDetailsCard`'s "Redirect chain" row) — the
  actual hop-by-hop hosts a shortened/tracking link passes through before landing, from a real HEAD
  request with redirects followed; this is literally "what page is hidden behind this QR code."
  Neutral (`Verdict.UNKNOWN`) by itself — most redirects are ordinary — shown so the user sees the
  real destination before deciding, not to accuse a link of anything on its own.
- **DNS security / DNSSEC** (`dnsSecuritySignal` + `DnsApi`) — reads the `AD` (Authenticated Data)
  flag off Google's own DoH resolver, which already validates DNSSEC on every query; this app never
  re-implements DNSSEC validation itself. Absence is common and never counted against a site —
  only presence ever contributes (`Verdict.SAFE`), same asymmetry as the TLS/domain checks avoid
  false positives for ordinary sites that just don't sign their zone.
- **Page content check** (`contentSignal` + `ContentInspector`) — a real GET of the *final*
  post-redirect page (capped at 200KB), checked for: a brand name from `UrlHeuristics.
  IMPERSONATED_BRANDS` appearing in the page content while the host isn't that brand's own domain
  (content-level impersonation, catching cases the domain-string heuristic alone misses); scam-kit
  pressure phrases ("verify your account immediately", etc.); a password field paired with brand
  impersonation; a missing `<title>`. This is **not** a spell-checker — the app bundles no
  dictionary and makes no claim to catch every typo — it's the same handful of concrete tells a
  careful human looks for, applied to bytes actually fetched just now. Only ever elevates to
  `SUSPICIOUS` on its own, never `MALICIOUS` — corroborating signals push it further through the
  normal aggregation in `checkUrl`.

### 5e. Bluetooth chat (Chat screens)
```
BluetoothChatManager (singleton, process-wide — survives navigating away from Chat because
ProtectionForegroundService holds a reference too)
  BLE advertise (own service UUID) + BLE scan (filtered on that UUID) → btDiscoveredDevices
  connect → RFCOMM socket → PqcChatCrypto handshake (ML-KEM-768 → HKDF → AES-256-GCM,
            direction-separated i2r/r2i keys) → ChatWireMessage frames
  Connection state machine: BtChatConnState (IDLE→DISCOVERING→CONNECTING→HANDSHAKING→
    REQUEST_SENT/REQUEST_RECEIVED→CONNECTED, or →FAILED/DENIED/REQUEST_TIMEOUT)
  Scanning state (isScanning) is DELIBERATELY separate from btConnState — see ChatStateRules.kt
    (a scan event must never overwrite a live connection state; this was a real shipped bug,
    fixed and pinned by ConnectionStateSyncTest)
  No live socket? → MeshRelayManager: store-and-forward gossip over a 2nd RFCOMM UUID,
    using ChatHistoryEntry.meshReachable contacts (long-term ML-KEM identity captured on first
    direct handshake — MeshIdentity.kt)
Persistence: SettingsRepository.chatSessionsFlow / chatHistoryFlow (DataStore, JSON via
  kotlinx.serialization)
```

---

### 5g. Bluetooth voice calling (same connection as 5e's chat)
```
No second connection, no codec. A call reuses the SAME RFCOMM socket and the SAME ML-KEM/AES-GCM
session key that 5e's text chat already established — CallRequest/Accept/Decline/End/Audio are just
five more ChatWireMessage frame types (ChatModels.kt, TYPE_CALL_REQUEST..TYPE_CALL_AUDIO) sharing the
one encrypted pipe. Audio is raw 16 kHz mono 16-bit PCM, uncompressed — see CallAudioEngine.kt for
why (bandwidth headroom on RFCOMM made a codec not worth the complexity).

BluetoothChatManager owns a second state machine, BtCallState (IDLE→CALLING→IN_CALL on the caller
side, IDLE→RINGING→IN_CALL on the receiver side, →IDLE on End/Decline/timeout/disconnect):
  startCall() → send CallRequest → CALLING, CALL_RING_TIMEOUT_MS (30s) auto-ends if unanswered
  (receiver) CallRequest in readLoop() → RINGING → NotificationHelper.postIncomingCall()
    (same "singleton posts it directly" pattern as postChatRequest — see §7.17; a call can arrive
    while the app isn't foregrounded, same as a chat request can)
  acceptCall()/CallAccept → IN_CALL, beginAudioStreaming() starts CallAudioEngine capture+playback
    on both ends
  endCall()/declineCall()/CallEnd/disconnect()/onSessionEnded() → endCallState() → IDLE, tears down
    CallAudioEngine on both ends. A live call can never outlive its chat connection: both
    onSessionEnded() and disconnect() unconditionally end any in-progress call first.
UI: ChatConversationScreen.kt's call button (visible only when CONNECTED && IDLE) + CallStatusBar
  (shown for all three non-IDLE states, since the user is already looking at this exact
  conversation); IncomingCallOverlay.kt (global, RINGING only — mirrors IncomingChatRequestOverlay,
  since a call can arrive while the user is anywhere else in the app, same reasoning as 5e's request
  overlay).
```

---

### 5f. CVE / EPSS / KEV lookup (Analyst Mode)
```
CveViewModel.search(keyword) / .openDetail(cveId)
  → CveRepository.search()/getDetail()           [analyst/data/repository/CveRepository.kt]
      ├─ NvdApi.searchCves()/getCveById()          — full record: description, CVSS vector+score,
      │                                              CWE (weaknesses[].description[].value),
      │                                              references, affected-product CPE criteria,
      │                                              AND cisaExploitAdd/cisaActionDue/
      │                                              cisaRequiredAction/cisaVulnerabilityName
      │                                              (KEV status — NVD ingests this from CISA
      │                                              directly, so there is no separate CISA feed
      │                                              client; see §7.10)
      ├─ EpssApi.getScore()                        — exploitation probability, best-effort
      │                                              (failure/absence → epssScore = null, does
      │                                              NOT fail the whole lookup)
      └─ CveDao (Room)                             — is this CVE already watched?
  → PriorityScoring.calculate(cvssScore, epssScore, isKev)   [pure, domain/PriorityScoring.kt]
  → CveDetail { …, priority: CvePriority }
```
Watching a CVE (`CveRepository.setWatched(id, true)`) writes to Room and calls
`CveWatchlistSyncWorker.ensureScheduled()` (idempotent — `KEEP` policy). That worker runs daily,
re-fetches NVD+EPSS for every watched CVE, and fires a real notification (`NotificationHelper.
postAlert`, target `MainActivity.TARGET_ANALYST_CVE`) only on the KEV-added transition (not on
every day it's still in KEV) — see `WatchedCveEntity.kevNotifiedAtMs`.

## 6. State management ↔ persistence map

`SettingsRepository` (data/SettingsRepository.kt, DataStore Preferences, JSON-encoded blobs for
structured data) is the only persistence layer — no SQL database anywhere in this app.

| DataStore key | Flow exposed | Written by | Read into AppUiState field |
|---|---|---|---|
| `tp_theme` | `themeFlow` | `setTheme` | `theme` |
| `tp_google_account` | `accountFlow` | `setAccount` | `account` |
| `tp_local_credential` | `hasLocalCredentialFlow` | `setLocalCredential` | (gates sign-in, not surfaced directly) |
| `tp_realtime` | `realtimeFlow` | `setRealtime` | `realtime` |
| `tp_protection_settings` | `protectionSettingsFlow` | `setProtectionSettings` | `settings` |
| `tp_chat_history` | `chatHistoryFlow` | `recordChatHistory` | `chatHistory` |
| `tp_chat_sessions` | `chatSessionsFlow` | `saveChatSession`/`deleteChatSession`/`clearChatSessions` | `chatSessions` |
| `tp_mesh_identity` | (one-shot `ensureIdentity`) | — | (used internally by MeshRelayManager) |
| `tp_mesh_outbox` | (one-shot `meshOutboxOnce`) | `mergeMeshEnvelopes`/`removeMeshEnvelope` | (mesh relay only) |
| `tp_resolved_findings` | `resolvedFindingsFlow` | `markFindingResolved`/`markFindingsCleared`/`clearResolvedFindings` | `fixed`, `resolvedRecords`, `resolvedCategories`, `everResolvedIds` (via `withResolvedApplied()`) |
| per-`ApiKeyId` | `apiKeysFlow` | `setApiKey` | `apiKeys` |

`prefs: Flow<Preferences>` (line 191) wraps `context.dataStore.data` in a `.catch {}` that recovers
from `IOException` to `emptyPreferences()` — a deliberate crash-guard (a corrupt prefs file used to
kill the process on every launch).

**Analyst Mode has its own, separate persistence layer**: a Room database (`analyst.db`, table
`cve_watchlist`, entity `WatchedCveEntity`) rather than another DataStore blob — a relational store
fits indexed per-CVE lookups better, and it's what the rest of the master spec's schema (future
`attack_techniques`/`threat_actors`/`cases` tables) will extend. It does **not** replace or migrate
anything in the table above. `AnalystDatabase.getInstance(context)` is a manual singleton (same
pattern as `BluetoothChatManager.getInstance`), shared between the Hilt-provided path and
`CveWatchlistSyncWorker` (a plain, non-Hilt `CoroutineWorker`, matching `ScheduledScanWorker`).
Analyst Mode's NVD API key is read from the *same* `apiKeysFlow`/`ApiKeyId.NVD` as the rest of the
app (a second `SettingsRepository` instance provided via Hilt, backed by the same on-disk
`preferencesDataStore` delegate — see `analyst/di/AnalystModule.kt`), so it's entered once in
Settings and used by both the consumer scan pipeline and Analyst Mode.

---

## 7. Known pitfalls / assumptions — read before touching these areas

1. **`FindingIdentity.fingerprintOf` must NOT include `Finding.risk`.** Risk is a drifting 0–100
   heuristic (recalculated every scan from things like "permission unused for 90+ days" or
   "months since patch") — including it in the fingerprint caused resolved threats to reappear on
   every rescan of an unchanged phone. Fixed in commit `3ac7db5`. Fingerprint = severity band +
   type line, deliberately excluding prose and the raw score.
2. **Scanning state vs. connection state (chat) must stay separate StateFlows.** They were once
   merged into one `btConnState`, which let a scan event on the receiving phone overwrite a live
   `CONNECTED` state with `SCAN_FAILED` — the reported "sender sees Connected, recipient sees
   Connection Failed" bug. Fixed in `0d3eba4`. Don't re-merge them.
3. **`QrAnalysis.urlToCheck` is the sole privacy gate for QR.** Any new payload format added to
   `QrContentClassifier` must explicitly decide this field — default should be `null` (nothing
   transmitted) unless the payload is genuinely a web URL the user is about to open.
4. **External device "Block" cannot actually sever a connection.** No Android app without root can
   cut a live USB or Bluetooth ACL connection. `ExternalDeviceMonitor.block()` only records the
   decision and flags the device; the UI (`ExternalDeviceAlertOverlay.kt`) states this limitation
   explicitly. Do not "fix" this by claiming stronger enforcement than exists.
5. **`ignoredFindings` is persisted, exactly like `fixed`** (reversed from the original design,
   which cleared it every `startScan()` — users reported previously-ignored threats reappearing on
   every rescan and wanted the same durability "Fixed" already had). "Ignore for now" and "Fixed"
   remain different user intents with separate on-disk records (`tp_ignored_findings` vs.
   `tp_resolved_findings`, `ignoredRecords` vs. `resolvedRecords`) and separate UI treatment — don't
   conflate the two — but both now survive rescans and app restarts, and both reactivate only via
   `FindingIdentity`'s fingerprint mismatch (the situation genuinely changed/worsened), never on a
   timer or a plain rescan. `AppViewModel.ignoreSelectedFinding`/`unignoreFinding` mirror
   `fixSelected`/`unresolveFinding` structurally.
5a. **Cold-start scan race on persisted records.** `resolvedRecords`/`ignoredRecords` start empty in
    `AppUiState`'s default constructor; real values only land once the DataStore flows collected in
    `AppViewModel.init` emit for the first time (a suspending disk read). A scan launched
    immediately after process start could otherwise race that first emission and briefly show every
    previously fixed/ignored threat as active again. Fixed by `resolvedRecordsReady`/
    `ignoredRecordsReady` (`CompletableDeferred`, completed on each flow's first emission);
    `startScan()` awaits both before scanning when `settingsRepository != null`. Don't remove this
    await when touching `startScan()`.
5b. **The last-scan snapshot (`SettingsRepository.lastScanFlow`/`tp_last_scan`,
    `StoredScanData`/`StoredFinding`/`StoredHwDevice`/`StoredPortFinding`/`StoredBreach`/
    `StoredRemedy`) deliberately excludes `permApps` and never touches `liveHwDevices`.** Both of
    those are re-read live from the OS on their own schedule
    (`AppViewModel.ensurePermissionsLoaded`/`refreshPermissions` re-reads PackageManager on every
    Permissions-screen visit; `refreshHardwareStatus` re-reads hardware once from `init` on every
    launch) specifically because a permission or a plugged-in device can change without this app
    knowing, and "no cached state, OS is the only source of truth" is the whole point. Restoring
    either from yesterday's scan would show a possibly-stale grant/device as if current — don't add
    them to `StoredScanData` to "complete" the snapshot. If you need last-scan `Finding`s to survive
    a restart faithfully, extend `StoredFinding`/`toStored()`/`toDomain()` in
    `AppViewModel.kt` (mirrors `ProtectionSettings.toStored()`/`toState()`'s pattern) — not the
    domain `Finding`/`Remedy`/`Breach` classes themselves, which stay persistence-agnostic.
6. **`HardwareAlertOverlay` / `state.hwAlert` is demo-only canned data** (`DemoData.hwSim`),
   separate from the real `ExternalDeviceMonitor` pipeline. Don't assume `hwAlert` reflects real
   hardware — check `externalDevices`/`deviceAlert` for that.
7. **This app has never run on a physical device or emulator in this environment** — no `adb`, no
   AVD. All verification is via `./gradlew :app:testDebugUnitTest` (124 pure-logic unit tests) and
   `:app:assembleDebug`/`assembleRelease` compiling cleanly. Any claim about on-device behavior
   (camera, Bluetooth radio timing, permission dialogs) is reasoned from API contracts, not
   observed.
8. **GitHub push is blocked for this session** — `git push` to `intelram/intelram` returns 403
   (Claude has no GitHub App access to this org). Work ships as local commits + APK sent via
   `SendUserFile`. Don't assume a prior commit made it to the remote.
9. **Release APK signing:** `app/build.gradle.kts`'s `release` block has no signing config by
   default; building an installable release APK requires temporarily adding
   `signingConfig = signingConfigs.getByName("debug")`, building, then reverting the file before
   commit (never commit that line).
10. **`network/NvdApi.kt` is shared** between the original app's WebView-CVE scan finding and
    Analyst Mode's full CVE detail — it was extended additively (new DTO fields with defaults, new
    `getCveById` function) rather than duplicated. Verified against a **live** NVD API 2.0 response
    (not assumed from docs) before writing the DTOs: `weaknesses[].description[].value` for CWE,
    `references[].url`, `configurations[].nodes[].cpeMatch[].criteria` for affected products,
    `cvssMetricV31[].cvssData.vectorString`, and `cisaExploitAdd`/`cisaActionDue`/
    `cisaRequiredAction`/`cisaVulnerabilityName` directly on the CVE object for KEV status (NVD
    ingests this from CISA, so there's no separate CISA KEV feed client). If NVD ever changes this
    schema, re-verify with a live `curl` before trusting a doc/blog example — the "affected"
    CNA-format block seen alongside "configurations" in a real response was not documented
    anywhere found during this work.
11. **Hilt is pinned to 2.55, not the latest.** The Hilt Gradle plugin requires AGP 9.0+ starting
    at Hilt 2.59 (this project is on AGP 8.7.2, deliberately not bumped — see below), and separately,
    `hilt-android:2.58`'s own POM declares a `kotlin-stdlib` dependency (2.2.20) newer than this
    project's Kotlin plugin (2.0.21) can read, which fails at `kspDebugKotlin` with a metadata
    version mismatch. `2.55` is the version whose declared kotlin-stdlib (2.0.21) exactly matches.
    Before bumping Hilt, Kotlin, or AGP here, check each candidate version's actual POM/plugin
    requirements rather than assuming latest-is-safe.
12. **CveViewModel keeps search and detail state in two separate `StateFlow`s**
    (`searchState`/`detailState`), not one shared `uiState`. An earlier draft shared one state
    object between the two screens; opening a CVE's detail overwrote it, so pressing back from
    detail to search rendered a blank screen (the search screen's `when` had no case for `Detail`).
    Any new Analyst Mode screen that can be reached both directly and from another screen in the
    same feature needs this same separation, not a single combined state.
13. **BLE scanning can go silently blind below API 31 if system Location is off** — no exception,
    no callback error, `BluetoothLeScanner.startScan()` just never reports a result, permission
    grant or not. This was the root cause of "my phone can't find anyone nearby, but everyone else
    can find me" (advertising doesn't need Location; scanning below API 31 does).
    `BluetoothChatManager.isLocationEnabled()`/`startDiscovery()` checks this explicitly and
    publishes `BtChatConnState.LOCATION_DISABLED`, with a `LocationManager.MODE_CHANGED_ACTION`
    receiver (in `registerAdapterStateReceiver()`) that auto-resumes discovery once the user turns
    it back on. Deliberately **not** gated on API 31+: this app's `BLUETOOTH_SCAN` declares
    `neverForLocation`, which by Android's contract exempts a compliant scan stack from this on 31+
    — flagging it there would tell a user to fix something that isn't broken. Some OEM stacks are
    known to ignore that exemption anyway; there's no public API to detect that non-compliance, so
    it's called out in `isLocationEnabled()`'s doc as a manual troubleshooting step instead of code.
14. **Background BLE advertising uses `ADVERTISE_MODE_BALANCED`, not `LOW_LATENCY`, on purpose.**
    `ProtectionForegroundService` keeps `BluetoothChatManager`'s advertiser running for as long as
    real-time protection is on — effectively 24/7, not just while Chat is open — so a battery-optimal
    interval there matters far more than shaving discovery latency the user isn't waiting on.
    Scanning (`startDiscovery`) stays `SCAN_MODE_LOW_LATENCY` since it only ever runs while the
    user is actively on the Chat screen. Don't "fix slow discovery" by bumping the advertiser back
    to LOW_LATENCY without re-deriving this tradeoff.
15. **`MeshRelayManager.tick()` skips its classic-Bluetooth-inquiry cycle when `PowerManager
    .isPowerSaveMode` is true.** That inquiry is one of the most power-hungry radio operations on
    the phone and this tick already runs unconditionally every 90s all day; respecting Battery
    Saver here is the same call Android's own background-job scheduling makes, and mesh relay is
    opportunistic store-and-forward by design, so a skipped cycle costs nothing but a slightly
    later hop. Don't add other unconditional radio-heavy periodic work without the same check.
16. **`MainActivity.handleTargetScreenIntent()` (Quick Settings Tile / notification deep links)
    must never run before the splash screen's first transition off `Screen.SPLASH`.** It used to
    run directly in `onCreate()`, before `setContent` — jumping the ViewModel straight to the
    target screen before Compose ever composed a frame, so a cold start via the tile skipped the
    splash animation entirely. It now runs from a one-shot `LaunchedEffect(Unit)` that awaits
    `state.screen != Screen.SPLASH` via `snapshotFlow` first. `onNewIntent()` (already-running app)
    still calls it directly — there's no splash to skip in that case.
17. **The incoming-chat-request notification/overlay must never depend on `AppViewModel` being
    alive.** `BluetoothChatManager._events` is a `MutableSharedFlow` with no replay buffer — if
    nothing is actively collecting it when `ChatEvent.ChatRequested` is emitted, that event is
    dropped, gone forever, no error. `AppViewModel`'s collector of it lives in `viewModelScope`,
    which is cancelled the moment the Activity is destroyed (e.g. the user swipes the app from
    Recents) — but `ProtectionForegroundService` keeps the `BluetoothChatManager` singleton itself
    alive and listening independent of any Activity. Root cause this fixes: a request arriving
    while the app was fully closed (not just backgrounded) produced no notification and no overlay
    at all, silently, even though the RFCOMM handshake completed correctly at the protocol level.
    `NotificationHelper.postChatRequest`/`cancelChatRequest` are now called directly from inside
    `BluetoothChatManager` itself (at the point it receives `ChatWireMessage.ChatRequest`, and at
    every place `incomingRequestId` is cleared — `acceptChatRequest`, `denyChatRequest`,
    `onSessionEnded`, `disconnect`) rather than only from `AppViewModel`'s event collector, since
    `BluetoothChatManager` holds `applicationContext` and needs no ViewModel to do this reliably.
    `AppViewModel`'s own (now-redundant-but-harmless) calls are left in place — both hit the same
    fixed notification ID, so calling twice just re-posts/re-cancels the same notification, not two.
    Any other event that must reach the user **regardless of whether the app is open** needs this
    same "the singleton posts it directly" treatment, not a ViewModel-side collector as the only path.
18. **`SplashScreen.kt`'s radar sweep / breathing halo / cycling phase text are purely decorative**
    (user-requested "make it more graphical" polish on the existing splash, not a functional
    change). `SPLASH_PHASES` is a **copy** of `DeviceScanner`'s real phase strings, kept in sync by
    hand — if the real scan's phase labels change, update `SPLASH_PHASES` too, or the splash will
    preview phases that no longer match the scan that follows it. The splash's own progress/"checks"
    counter is still a simulated warm-up, not a real scan — see the file's own doc.
19. **A real scan now runs automatically every time the user opens the app, not just on manual
    "Scan Now."** User-requested, repeatedly and explicitly: opening the app should scan the real
    environment every time — first launch, a fresh cold start, or reopening after the app was
    merely backgrounded (not killed) — never just display whatever was cached from the last scan.
    `AppViewModel.triggerAutoScanOnce(flow)` runs it from any of three call sites: the `accountFlow`
    collector's "landing" transition (returning user, persisted account, true cold start),
    `completeOnboarding()` (brand-new account, true cold start), or `finishSplash()` when
    `replayLaunchExperience()` (§7.25) has just replayed the splash for a later reopen. Which of the
    two `AutoScanFlow` values each site passes decides which *screen experience* the scan runs
    behind — see §7.26 — but `autoScanTriggeredThisLaunch` makes every one of them a true one-shot
    regardless: don't wire this into `goDashboard()` or any other `setScreen(Screen.DASHBOARD)` call
    (`cancelScan()`, `leaveChat()`) — those are ordinary in-session navigation, not "the app was just
    opened," and would re-trigger a full scan on an unrelated screen change if hooked. This is a
    real, network-calling, permission-auditing, port-probing scan — it takes several seconds and a
    small amount of data/battery on every open now, not just when the user asks for it; that
    trade-off was explicit in the request, not an oversight.
20. **Leaving the chat conversation screen (Back) must never disconnect the call** —
    `AppViewModel.leaveChatConversation()` is pure navigation to `Screen.CHAT`; the live socket,
    `chatPeerName`, `chatMeshPeer` and `chatMessages` are left exactly as they are.
    `resumeChatConversation()` (a no-op if the connection has since ended) is how the user gets back
    into it, surfaced as a "Still connected to X — Resume chat" banner on the Chat list screen
    whenever `state.chatPeerName != null`. Root cause this fixed: Back used to call the same
    function as the explicit "Exit Chat" button (`exitChat()`, which does disconnect and belongs
    only on that button). A second latent bug this surfaced: `leaveChat()` (the Chat-list screen's
    own Back, to Dashboard) used to unconditionally null out `chatPeerName`/`chatMessages`/
    `chatMeshPeer` — harmless under the old design (you could never reach that screen with a live
    call), but would have silently orphaned a real background connection under this one, so it's
    now pure navigation too. Never reintroduce state-clearing on a screen-leave function without
    checking whether a live connection can legitimately still be up.
21. **Nearby-device names arrive via Service Data, not a separate Service UUID + scan response.**
    Root cause of "every nearby device shows the placeholder name": the identifying UUID and the
    display name used to travel in two different BLE packets (a Service UUID declaration in the
    main advertisement, the name in a separate scan-response packet), which required the scanning
    radio to complete an active-scan round trip and Android to merge the two before
    `recordSighting()` ever saw a name — a step several chipsets don't reliably deliver for a
    non-connectable advertiser. Both now live in one Service Data AD structure
    (`BluetoothChatManager.startAdvertising`'s `data` builder), so every `onScanResult()` already
    carries the name — nothing to merge, nothing that can fail to arrive. Consequences to respect
    if this code is touched again: `MAX_ADVERTISED_NAME_BYTES` is 10, not 13 (see the byte-budget
    comment on `startAdvertising`) — the 31-byte legacy BLE budget is now spent by one Service Data
    structure instead of splitting it across two packets; `ScanFilter` in `startDiscovery()` matches
    on `setServiceData(uuid, emptyArray, emptyArray)`, not `setServiceUuid(uuid)`; and
    `startAdvertising` no longer passes a scan-response `AdvertiseData` to
    `BluetoothLeAdvertiser.startAdvertising()` at all.
22. **`org.bouncycastle.**` must carry an explicit `-keep` in `app/proguard-rules.pro`, full stop.**
    Root cause of the real "NoSuchMethodError — something went wrong" crash reported only on the
    chat **responder's** phone (never the initiator's, never in debug builds): with no keep rule at
    all, R8 (`isMinifyEnabled = true` on release) was free to strip/rename PQC crypto members it
    judged unreachable from its own static call-graph analysis — and that analysis differs between
    the initiator's code path (`decapsulate`/`MLKEMExtractor`) and the responder's
    (`encapsulate`/`MLKEMGenerator`), so R8 could keep what one side needed while discarding what the
    other needed, entirely deterministically per-build but asymmetric between the two phones. Every
    release APK built before this fix carried this bug latent — it only manifested depending on
    which role (initiator vs. responder) a given phone happened to play in a given chat session.
    Crypto code is exactly the wrong place to let an optimizer guess member-reachability for, so the
    fix keeps the whole library rather than trying to enumerate exactly which members are used.
    Verified via R8's own `app/build/outputs/mapping/release/seeds.txt`/`usage.txt` reports (272
    `org.bouncycastle.pqc.crypto.mlkem` entries now kept, including both `generateEncapsulated` and
    `extractSecret`) — there is no device/emulator in this environment to reproduce the crash
    directly. Never let this rule regress (e.g. during a proguard-rules cleanup pass) without
    re-checking both `generateEncapsulated` and `extractSecret` still appear in `seeds.txt`.
23. **Voice calling shares 5e's connection and session key — it has no connection state machine of
    its own to keep in sync with `BtChatConnState`.** `BtCallState` only means anything while
    `btConnState == CONNECTED`; a call cannot outlive its chat connection, so both
    `BluetoothChatManager.onSessionEnded()` and `disconnect()` unconditionally call `endCallState()`
    first — if a future change adds another path that ends the chat connection, it must do the same,
    or a stale `IN_CALL`/`RINGING` state (and a still-open mic/speaker via `CallAudioEngine`) will
    survive past the disconnect that should have ended it. `CallAudioEngine`'s microphone/speaker
    permission (`RECORD_AUDIO`) is requested at the point the user actually taps the call button
    (`ChatConversationScreen`'s `requestCall`), matching how Chat's own Bluetooth permissions are
    requested at the point of scanning rather than up front at app launch — do not move it to a
    startup/onboarding prompt.
24. **The QR/website verdict never hard-blocks opening a link — it's evidence, not a lock.** Only
    `Verdict.SAFE` used to get an "Open link" button at all; every other verdict had no way to
    proceed short of leaving the screen. User-requested reversal: the app's job ends at showing the
    evidence, and whether to open a flagged link is explicitly the user's call. Non-safe verdicts
    still default to "Don't open — go back" (the safe path stays the path of least resistance), but
    `QrScannerScreen`'s "Open anyway" text link (deliberately not a button — same treatment a
    browser gives its own unsafe-site interstitial) reaches the same `onOpenLink` after one
    confirmation dialog that repeats the specific risk. Never remove that link to "protect" the
    user harder than they asked to be protected — that was the exact behavior this reversed.
    `ScanWebsiteScreen` never had an open action at all (it's a "check before I go there myself"
    tool) and is intentionally untouched by this — there's nothing to unblock there.
    New keyless technical/content checks (§5d) feed the *same* `signals` list every existing verdict
    UI already iterates generically (`QrResultDetails`, `WebsiteVerdictCard`) — adding a new
    `UrlSignal` source needs no per-screen UI change, only a real reason for it to exist in
    `ThreatIntelRepository.checkUrl`'s `signals` list.
25. **The branded splash (§7.18) only ever played once per process before this — reopening the app
    from the background (not killed) skipped straight to whatever screen was last shown, with no
    splash and no fresh scan.** Root cause: `AppUiState.screen` defaults to `Screen.SPLASH` only
    when a *new* `AppViewModel`/state instance is created (a true cold start, or the process was
    actually killed and relaunched) — the ViewModel survives an ordinary background/foreground round
    trip, so `screen` just stays wherever the user left it. User-requested, repeatedly and
    explicitly: this exact splash — and the real scan behind it — every time the app is opened, not
    only the first time per process. Fixed with `AppViewModel.replayLaunchExperience()`, wired to
    `ProcessLifecycleOwner`'s `ON_START` in `MainActivity` (a `DisposableEffect`, not an
    Activity-level `onResume` — the latter also fires for incidental in-app blips like a permission
    dialog or an orientation change, which must NOT replay the splash over whatever the user is
    doing). `replayLaunchExperience()` resets `autoScanTriggeredThisLaunch` and sets
    `screen = Screen.SPLASH` + `splashPhase = SplashPhase.BRANDING` (§7.26 — the second field matters
    too, or a reopen would resume mid-real-scan-phase instead of replaying the branded intro first).
    Both guard on `account == null` (still mid sign-in/onboarding — leave that flow alone) and on
    `screen == Screen.SPLASH` already (no double-replay). Never wire this to Activity-level lifecycle
    callbacks instead of `ProcessLifecycleOwner` — that reintroduces replaying the splash over live
    screens (Chat, Settings, mid-QR-scan) on every trivial pause/resume blip, not just a genuine app
    reopen.
26. **Opening the app shows exactly one scanning screen, then lands straight on the homepage — never
    a splash followed by a second, separately-styled "now scanning" screen followed by a results
    list.** User-requested, explicitly and repeatedly, after the two-screens version of §7.19/§7.25
    read as redundant: "once it's scanned, then directly land on the homepage without doing second
    scanning." `Screen.SPLASH` now has two phases (`AppUiState.splashPhase: SplashPhase`):
    `BRANDING` (the original ~1.85s simulated-counter intro, `SplashScreen.kt`, unchanged) and
    `REAL_SCAN` (`RealScanSplashScreen` — the *identical visual* — hexagon, radar sweep, "Threat
    Intelligence" title, progress bar — but driven by the live `AppUiState.progress`/`scanPhase`/
    `scannedCount` the real scan is actually producing, with no internal timer of its own).
    `AppViewModel.runAutoScanOnSplash()` runs the real scan while staying in the `REAL_SCAN` phase —
    never touching `Screen.SCANNING` — and lands directly on `Screen.DASHBOARD` when done — never
    `Screen.RESULTS`. `finishSplash()` and the `accountFlow` collector's landing branch move
    `splashPhase` to `REAL_SCAN` (staying on `Screen.SPLASH`) instead of jumping to `Screen.DASHBOARD`
    immediately, specifically for the "returning user reopening an already set-up app" case.
    **Deliberately NOT changed:** `completeOnboarding()` (finishing onboarding for a brand-new
    account) and the `accountFlow` collector's `Screen.SIGNIN`/`Screen.CREATE_ACCOUNT` sub-case (a
    fresh Google sign-in) still use the older `Screen.SCANNING` → `Screen.RESULTS` flow via
    `AutoScanFlow.SCANNING_TO_RESULTS` — those are first-time, one-shot "here's what we found"
    moments, not a *reopen* of the app, which is what this request was actually about; the manual
    "Scan Now" button (`startScan()`) is untouched for the same reason. `runRealScan(scanner,
    landingScreen)` is the one real scan pipeline shared by both flows (`startScan` passes
    `Screen.RESULTS`, `runAutoScanOnSplash` passes `Screen.DASHBOARD`) — the actual scan, its
    persistence, and its resolved/ignored-finding reconciliation are identical either way; only the
    landing screen (and whether `Screen.SCANNING` is ever shown) differs. If a future change needs a
    third landing screen, add another `AutoScanFlow` case rather than duplicating `runRealScan`.
27. **Every website — `google.com` included — used to come back SUSPICIOUS.** Two independent
    defects compounded, and both fixes are load-bearing:
    - **Substring brand matching.** `UrlHeuristics` and `ContentInspector` both asked
      `text.contains(brand)` against a flat brand list. In page HTML `"irs"` matches the CSS
      `:first-child`, `"ups"` matches `groups`/`backups`, `"chase"` matches `purchase`, and
      `"microsoft"` matches the `"microsoft edge"` user-agent sniffing in ordinary JavaScript.
      Measured on real pages: `google.com` tripped 2 severity-3 "brand impersonation" findings,
      `wikipedia.org` 4, `bbc.com` 6. In host names `purchase.com` was Chase and `backups.io` was
      UPS. Replaced by `BrandRegistry`: official-domain allowlists checked first, whole-host-label
      matching for short tokens (substring only for tokens ≥ 6 chars), leetspeak normalisation for
      look-alikes (`paypa1`, `g00gle`), and — for page content — only `visibleText()` (scripts,
      styles and markup stripped) matched on whole-word boundaries. Page content additionally now
      requires the whole phishing shape — brand named **and** a password field **and** a non-official
      host — because merely naming a company is what news articles and documentation do.
    - **A verdict cliff with no way back.** `suspiciousCount > 0 || worstHeuristic >= 1 →
      SUSPICIOUS` meant one low-severity note (a `.xyz` ending, four hyphens, plain HTTP) condemned
      a site, and no amount of positive evidence could pull it back. Replaced by
      `UrlVerdictScoring`, which weighs risk against trust: `SignalWeight.DEFINITIVE` signals (a
      blocklist naming this exact URL, a certificate that fails validation) decide alone, everything
      else scores, and established registration / trusted TLS / a clean Cloudflare answer subtract
      points. **Positive credit from TLS and DNSSEC is suppressed when a severity-3 deception flag
      fired** — a free certificate proves control of a domain, not honesty, so a look-alike can't
      buy its way back to safe with one.
    Also fixed here: `parseRdapInstant` only accepted the `Z` date form, so every registry that
    publishes a numeric offset (`1997-09-15T04:00:00-04:00`) silently lost the domain age — the
    strongest positive signal there is. **Verified two ways** (no device exists in this
    environment): `UrlVerdictAccuracyTest` pins the logic offline against the exact markup that
    caused the false positives, and the scoring rules were mirrored and run against live data for
    8 real domains — `google.com`, `wikipedia.org`, `github.com`, `bbc.co.uk`, `stackoverflow.com`,
    `paypal.com` all SAFE, and Cloudflare's own `malware.testcategory.com` /
    `phishing.testcategory.com` both MALICIOUS. Re-run that check before touching the weights.
28. **Wi-Fi network security is assessed by the scan, and adds no screen of its own.** Added after a
    feature-by-feature comparison against MobiArmour (a shipping Android threat-intelligence app the
    user asked to benchmark against): of its nine advertised capabilities this app already had eight
    — website scanning, QR scanning, app risk/permissions, data-breach alerts, OTP protection,
    threat-pattern heuristics, and security guidance — and **Wi-Fi network analysis was the one real
    gap**. The pre-existing `scan/WifiInfo.kt` only read the connected network's *name* for display
    and never judged it.
    `scan/WifiSecurityScanner.kt` assesses the live connection: the cipher actually protecting it
    (open / OWE / WEP / WPA / WPA2 / WPA3 / enterprise), whether a captive portal is intercepting
    traffic, whether a VPN or Private DNS is active, the DNS servers handed out, and a live DNS
    hijack probe.
    Two design rules to preserve if this is touched:
    - **It emits `Finding`s, not a screen.** That is what keeps the UI unchanged — the user's
      explicit constraint — since `ResultsScreen`/`ThreatDetailScreen` already render findings
      generically. `Category.NETWORK` was added for them, which needs the label in
      `ThreatDetailScreen.label()` (an exhaustive `when`) but deliberately gets **no**
      `Derived.systemAuditAreas` entry, so no new Dashboard tile appears.
    - **Unknown is never reported as a problem.** Android only exposes the cipher directly from
      API 31 (`WifiInfo.currentSecurityType`); below that it must be read out of the scan-results
      table, which additionally needs location services actually switched on. When it can't be read
      the result is `Encryption.UNKNOWN` and *no finding at all* — never an assumed weakness. Same
      for `dnsHijack == null`.
    The DNS hijack probe resolves a random name under `.invalid`, which RFC 6761 reserves so that it
    can never exist: any address in the answer means the network rewrites DNS failures. Chosen over
    comparing a real domain against a trusted resolver, which would false-alarm every time a CDN
    legitimately answered differently. `Category.NETWORK` is only added to `coveredCategories` when
    actually on Wi-Fi, so off Wi-Fi the absence of a finding never counts as "the problem is fixed"
    (see §5b). `SPLASH_PHASES` in `SplashScreen.kt` is a hand-kept copy of the scanner's phase list
    (§7.18) and was updated with the new phase — scan phases went 7 → 8.

---

## 8. Test inventory (app/src/test/java/com/threadprotection/app/)

| File | Covers |
|---|---|
| `chat/ConnectionStateSyncTest.kt` | `ChatStateRules` — scan/connection state separation, accept flow, terminal states |
| `chat/NearbyDeviceListTest.kt` | Device list merge/dedupe/stale-sweep logic |
| `data/FindingIdentityTest.kt` (31) | Fingerprinting, resolve/clear/re-emerge lifecycle, drift immunity |
| `hardware/DeviceRiskTest.kt` | `DeviceRiskAssessor` — risk-by-capability, honest naming, stable ids |
| `qr/QrContentClassifierTest.kt` (29) | All 13 payload formats, the `urlToCheck` privacy invariant |
| `state/BackStackRulesTest.kt` | Navigation stack push/pop/cap/no-loop-back |
| `state/ResultsSyncTest.kt` | Severity grouping, `FixProgress`/score/button agreement |
| `analyst/domain/PriorityScoringTest.kt` | Composite CVE priority formula — KEV/EPSS/CVSS precedence, boundary values, missing-score handling |

All are pure-JVM (`org.junit.Test`), no Robolectric/instrumentation — they test extracted rule
objects (`ChatStateRules`, `FindingIdentity`, `BackStackRules`, `QrContentClassifier`,
`DeviceRiskAssessor`), not Compose UI or Android framework classes. Run via
`./gradlew :app:testDebugUnitTest`.

---

## 9. Build & config dependencies

- `app/build.gradle.kts`: `compileSdk 35`, `minSdk 26`, `targetSdk 35`, Compose BOM, CameraX 1.4.1,
  ML Kit barcode-scanning 17.3.0, Retrofit + OkHttp, kotlinx.serialization, DataStore Preferences,
  WorkManager, Credential Manager + Google ID, Bouncy Castle (PQC crypto).
- `gradle/libs.versions.toml`: version catalog — check here first when bumping any dependency.
- `AndroidManifest.xml`: declares `usb.host` feature (optional), Bluetooth permissions
  (`BLUETOOTH_SCAN`/`CONNECT`/`ADVERTISE`), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
  `FOREGROUND_SERVICE`; registers `ProtectionForegroundService`, `BootReceiver`,
  `ChatRequestActionReceiver`, `AppPermissionsTileService`.
- `GOOGLE_WEB_CLIENT_ID` (BuildConfig field, blank by default) — sign-in falls back to demo mode
  without it; see `auth/GoogleAuthClient.kt`.
- No `.env`, no backend URL config — every network client's base URL is a public, free-tier API
  (NVD, Google Safe Browsing, VirusTotal, URLhaus, ThreatFox, PhishTank, AbuseIPDB, RDAP, IPInfo,
  XposedOrNot), each gated behind the user's own key entered in Settings (except NVD and
  XposedOrNot, which work keyless).

---

## 10. Change → Affected Systems lookup (common request shapes)

| If the request is about… | Affected systems | Files to open first | Also verify |
|---|---|---|---|
| A specific screen's layout/copy | That one screen | `ui/screens/<Screen>Screen.kt` | Its ViewModel entry points (§3 table) |
| The security score / threat list | Scoring & grouping logic | `state/Derived.kt` | `ResultsScreen.kt`, `ResultsSyncTest.kt` |
| "Resolved threat reappeared/didn't reappear" | Fingerprinting & persistence | `data/FindingIdentity.kt` | §7.1, `FindingIdentityTest.kt`, `AppViewModel.withResolvedApplied()` |
| "Ignored threat reappeared/didn't reappear", "ignore doesn't stick after rescan/restart" | Fingerprinting & persistence (mirrors Fixed) | `data/SettingsRepository.kt` (`ignoredFindingsFlow`/`tp_ignored_findings`), `state/AppViewModel.kt` (`ignoreSelectedFinding`/`unignoreFinding`) | §7.5, §7.5a (cold-start race), `data/FindingIdentity.kt` |
| QR "Open link" doesn't open a browser | `MainActivity`'s `openUrlInBrowser` wiring | `MainActivity.kt` (`openUrlInBrowser`, `Intent.ACTION_VIEW`), `ui/screens/QrScannerScreen.kt` (`onOpenLink` param) | The button previously called `onRescan` by mistake — verify it's still wired to `onOpenLink`, not `onRescan` |
| "Score/status resets after restart", "Dashboard shows scan needed even though I already scanned" | Last-scan snapshot persistence | `data/SettingsRepository.kt` (`lastScanFlow`/`tp_last_scan`, `StoredScanData`), `state/AppViewModel.kt` (`toStored()`/`toDomain()` mapping, the `lastScanFlow` collector in `init`, the `saveLastScan` call at the end of `startScan()`) | §7.5b (why `permApps`/`liveHwDevices` are excluded), `state/Derived.kt` (`securityScore`/`scanStatus` — both gated on `hasScanned`) |
| Scan pipeline (new check, new finding type) | `DeviceScanner` orchestration | `scan/DeviceScanner.kt` | `ScanResult.coveredCategories` (§5b gate), `data/Models.kt` (Finding/Category) |
| "App doesn't auto-scan on open", "auto-scan fires more than once" | Launch-time auto-scan trigger | `state/AppViewModel.kt` (`triggerAutoScanOnce`, `autoScanTriggeredThisLaunch`, its call sites in the `accountFlow` collector, `completeOnboarding()`, and `finishSplash()`) | §7.19 — never wire this into `goDashboard()` or another `setScreen(Screen.DASHBOARD)` call |
| "Splash doesn't show every time the app is opened", "reopening the app skips straight to the last screen" | Splash replay on foreground | `state/AppViewModel.kt` (`replayLaunchExperience`, `finishSplash`), `MainActivity.kt` (the `ProcessLifecycleOwner`/`DisposableEffect` wiring) | §7.25 — must stay on `ProcessLifecycleOwner`, never an Activity-level `onResume` |
| "I see two scanning screens in a row when I open the app", "it lands on a results list instead of the homepage" | Which screen the on-open scan runs behind | `state/AppViewModel.kt` (`AutoScanFlow`, `runAutoScanOnSplash`, `runRealScan`), `ui/screens/SplashScreen.kt` (`RealScanSplashScreen`), `state/AppUiState.kt` (`SplashPhase`) | §7.26 — the reopen path must stay on `Screen.SPLASH`/`SplashPhase.REAL_SCAN` and land on `Screen.DASHBOARD`; don't let it drift back to `Screen.SCANNING`/`Screen.RESULTS` |
| QR: new payload format | Classifier only | `qr/QrContentClassifier.kt` | §7.3 privacy gate, `QrContentClassifierTest.kt` |
| QR: camera/scan speed/UX | Camera pipeline | `ui/components/QrCameraPreview.kt` | `ui/screens/QrScannerScreen.kt` |
| URL/website reputation | Aggregator + one API client | `network/ThreatIntelRepository.kt` + relevant `network/*Api.kt` | `data/SettingsRepository.kt` (`ApiKeys`/`ApiKeyId` defined here, not in `Models.kt`), Settings screen key entry |
| "Check domain age/registration date", "SSL certificate expiry", "hidden pages behind a shortened link", "DNSSEC/DNS security" | Keyless technical checks (no API key involved at all) | `network/TechnicalInspector.kt` (`fetchDomainRegistration`/`fetchTlsDetails`/`fetchHttpTrace`/`fetchDnssecStatus`), `network/DnsApi.kt` | §5d, §7.24 — these always run regardless of configured keys; shown via `ui/components/Misc.kt`'s `TechnicalDetailsCard` |
| "Detect a fake/phishing page's content", "spelling/design red flags on the scanned page" | Page-content heuristics (keyless) | `network/ContentInspector.kt`, `network/BrandRegistry.kt` (brands + official domains, shared with `UrlHeuristics`, never duplicated) | §5d, §7.27 — not a spell-checker; matches only `visibleText()` on whole words, and needs brand + password field + non-official host |
| "Wi-Fi/network safety", "is this public network safe", "open network warning", "DNS hijacking" | Live network assessment | `scan/WifiSecurityScanner.kt` (cipher, captive portal, VPN/Private DNS, DNS hijack probe), `scan/DeviceScanner.kt` (its scan phase) | §7.28 — emits findings only, never a screen; unknown must never be reported as a weakness |
| "A good website is reported suspicious", "every site says suspicious", "verdict is wrong" | Brand matching and verdict scoring | `network/BrandRegistry.kt`, `network/UrlVerdictScoring.kt`, `network/ContentInspector.kt` | §7.27 — run `UrlVerdictAccuracyTest` first; never reintroduce raw `contains(brand)` or a "one flag = suspicious" rule |
| "QR/website verdict blocks opening a link", "no way to open a flagged link" | Verdict-to-action policy | `ui/screens/QrScannerScreen.kt` (`showOpenAnywayConfirm`, the "Open anyway" text link, the confirm `AlertDialog`) | §7.24 — the verdict must stay informational; never reintroduce a hard block |
| Bluetooth chat connection bugs | State machine | `chat/BluetoothChatManager.kt`, `chat/ChatStateRules.kt` | §7.2, `ConnectionStateSyncTest.kt`, `state/AppViewModel.kt` chat section (line ~1079+) |
| "Chat request doesn't notify/pop up (especially when the app is closed)" | Notification lifecycle vs. app lifecycle | `chat/BluetoothChatManager.kt` (`postChatRequest`/`cancelChatRequest` call sites), `service/NotificationHelper.kt` | §7.17 — must not depend on `AppViewModel`/`viewModelScope` being alive; `MainActivity.kt`'s `IncomingChatRequestOverlay` for the in-app side |
| "Going back during a chat disconnects it" | Screen-leave vs. connection lifecycle | `state/AppViewModel.kt` (`leaveChatConversation`, `resumeChatConversation`, `leaveChat`) | §7.20 — `exitChat()` is the only function that should ever disconnect; `ui/screens/ChatScreen.kt`'s "Resume chat" banner |
| "Nearby device shows the placeholder/fake name instead of their real name" | BLE advertisement packet layout | `chat/BluetoothChatManager.kt` (`startAdvertising`'s `data`/`MAX_ADVERTISED_NAME_BYTES`, `startDiscovery`'s `ScanFilter`, `recordSighting`) | §7.21 — name and identifying UUID must stay in one Service Data structure, not split across a UUID declaration + scan response |
| "One phone can't find any nearby device (but others can find it)" | BLE scan blindness | `chat/BluetoothChatManager.kt` (`isLocationEnabled()`, `startDiscovery()`) | §7.13, `BtChatConnState.LOCATION_DISABLED`, `ui/screens/ChatScreen.kt`'s banner for it |
| Chat/mesh battery drain | Radio duty-cycle tuning | `chat/BluetoothChatManager.kt` (`startAdvertising`'s `AdvertiseSettings`), `chat/MeshRelayManager.kt` (`tick()`'s Battery Saver check) | §7.14, §7.15 — don't revert either without re-deriving the tradeoff |
| Chat message/history persistence | Repository + models | `data/SettingsRepository.kt` (chat keys), `chat/ChatModels.kt` | `ui/screens/ChatHistoryScreen.kt`/`ChatSessionScreen.kt` |
| Mesh relay (offline messaging) | `MeshRelayManager` | `chat/MeshRelayManager.kt`, `chat/MeshEnvelope.kt`, `chat/MeshIdentity.kt` | `service/ProtectionForegroundService.kt` (who owns the singleton) |
| "NoSuchMethodError"/"something went wrong" crash in chat (usually only on one phone) | Missing R8 keep rule on crypto | `app/proguard-rules.pro` (`org.bouncycastle.**`) | §7.22 — verify via a fresh `assembleRelease`'s `seeds.txt`, not by guessing; never ship a release with this rule removed |
| Voice calling bugs (can't call, call doesn't ring, audio one-way/silent, call survives a disconnect) | Call state machine + audio engine | `chat/BluetoothChatManager.kt` (`startCall`/`acceptCall`/`declineCall`/`endCall`/`beginAudioStreaming`/`endCallState`), `chat/CallAudioEngine.kt` | §5g, §7.23 — a call must never outlive its chat connection; `chat/ChatModels.kt` for the wire frames if the protocol itself is suspect |
| Incoming call doesn't pop up / doesn't notify when app is closed | Notification lifecycle vs. app lifecycle | `chat/BluetoothChatManager.kt` (`postIncomingCall`/`cancelIncomingCall` call sites), `service/NotificationHelper.kt`, `service/CallActionReceiver.kt` | §7.17 (same pattern as the chat-request notification) applied to calling; `MainActivity.kt`'s `IncomingCallOverlay` for the in-app side |
| App doesn't show the splash/loading screen on launch | Cold-start ordering | `MainActivity.kt` (`onCreate`'s `LaunchedEffect(Unit)` gating `handleTargetScreenIntent`) | §7.16 — a deep-link intent (Quick Settings Tile) must never navigate before the first move off `Screen.SPLASH` |
| USB/Bluetooth device alerts | `ExternalDeviceMonitor` | `hardware/ExternalDeviceMonitor.kt`, `hardware/ExternalDevice.kt` | §7.4 (Block limitation), `DeviceRiskTest.kt`, `ui/screens/ExternalDeviceAlertOverlay.kt` |
| Navigation / back button behavior | `BackStackRules` | `state/BackStackRules.kt` | `AppViewModel.setScreen/navigateBack`, `BackStackRulesTest.kt` |
| Settings / scheduled scan / notifications | Settings + WorkManager | `ui/screens/SettingsScreen.kt`, `state/AppViewModel.kt` (settings section), `service/ScheduledScanWorker.kt` | `service/NotificationHelper.kt` |
| Sign-in / account | Auth | `auth/GoogleAuthClient.kt`, `ui/screens/SignInScreen.kt`/`CreateAccountScreen.kt` | `data/SettingsRepository.kt` (account/credential keys) |
| App permissions audit | Permission scanning | `scan/PermissionAudit.kt`, `scan/PermissionCatalog.kt`, `scan/PermissionState.kt` | `ui/screens/AppPermissionsScreen.kt`/`AppPermissionDetailScreen.kt` |
| Theming / dark-light | Palette | `ui/theme/TpPalette.kt`, `ui/theme/Theme.kt` | Any screen using `LocalTpPalette.current` |
| Build/dependency changes | Gradle | `app/build.gradle.kts`, `gradle/libs.versions.toml` | §9, §7.9 (release signing caveat), §7.11 (Hilt/AGP/Kotlin version coupling) |
| CVE/EPSS/KEV lookup or the watchlist | Analyst Mode's own layer | `analyst/data/repository/CveRepository.kt`, `analyst/domain/PriorityScoring.kt` | §5f, §7.10 (NvdApi is shared — verify schema live before extending), `PriorityScoringTest.kt` |
| A genuinely new SOC-analyst feature (ATT&CK, dark web, cases, AI) | New Analyst Mode module | Follow the `analyst/` package's di/data/domain/presentation layering (§2); do **not** touch `AppUiState`/`AppViewModel` or any of the 22 original screens | Needs a backend for anything requiring a hidden API key or OPSEC-proxied lookups (VirusTotal, Shodan, dark-web monitoring, AI) — flag this to the user before building rather than assuming one exists |

**Procedure for any change:** find the row above (or the nearest package in §2) → open only the
listed files → check the relevant §7 pitfall → make the change → run
`./gradlew :app:testDebugUnitTest` → update this map if the change altered architecture (new
package, new persisted key, new singleton, new pipeline, new screen) per §11.

---

## 11. Maintenance rule for this file

Update this map, in the same commit as the code change, when a change:
- adds/removes a `Screen` enum value or a top-level package,
- adds/removes a `SettingsRepository` DataStore key or persisted model,
- adds/removes a process singleton (`getInstance` pattern) or background service,
- changes a pipeline's shape (new stage, new gating field like `coveredCategories`),
- fixes a bug subtle enough to be worth a §7 pitfall entry (i.e. cost real debugging and would
  plausibly be reintroduced by a naive future change).

Do **not** update this file for: copy/wording changes, styling-only edits, adding a test, adding a
new `Finding`/`HwSim` instance to `DemoData`, or any change confined to one screen's internal
layout. Those don't change the graph.
