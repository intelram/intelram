# Thread Protection — Graph Engineering Map

**Purpose.** This is the navigation layer for this codebase. Before making a change, consult this
file to identify the affected components, then read only those files. Do not re-survey the codebase
from scratch — this map is kept current (see §11, maintenance rule).

**Last verified against:** commit `fb94c94` (2026-09-01), adding Analyst Mode. ~100 Kotlin files,
136 JVM unit tests (all passing, all offline — no device/emulator/`adb` exists in this environment;
nothing in this app has ever been run on real hardware).

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
  + TechnicalInspector (TLS/domain-age signals) via RdapApi / IpInfoApi
  → UrlVerdict { overall, confidence, signals: List<UrlSignal>, onDeviceFlags, technical }
```
Every API client in `network/` is a thin Retrofit/OkHttp wrapper, one file each; `ApiKeys` (user's
own free-tier keys, entered in Settings) gates which ones fire — no key bundled with the app.

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
| QR: new payload format | Classifier only | `qr/QrContentClassifier.kt` | §7.3 privacy gate, `QrContentClassifierTest.kt` |
| QR: camera/scan speed/UX | Camera pipeline | `ui/components/QrCameraPreview.kt` | `ui/screens/QrScannerScreen.kt` |
| URL/website reputation | Aggregator + one API client | `network/ThreatIntelRepository.kt` + relevant `network/*Api.kt` | `data/SettingsRepository.kt` (`ApiKeys`/`ApiKeyId` defined here, not in `Models.kt`), Settings screen key entry |
| Bluetooth chat connection bugs | State machine | `chat/BluetoothChatManager.kt`, `chat/ChatStateRules.kt` | §7.2, `ConnectionStateSyncTest.kt`, `state/AppViewModel.kt` chat section (line ~1079+) |
| "One phone can't find any nearby device (but others can find it)" | BLE scan blindness | `chat/BluetoothChatManager.kt` (`isLocationEnabled()`, `startDiscovery()`) | §7.13, `BtChatConnState.LOCATION_DISABLED`, `ui/screens/ChatScreen.kt`'s banner for it |
| Chat/mesh battery drain | Radio duty-cycle tuning | `chat/BluetoothChatManager.kt` (`startAdvertising`'s `AdvertiseSettings`), `chat/MeshRelayManager.kt` (`tick()`'s Battery Saver check) | §7.14, §7.15 — don't revert either without re-deriving the tradeoff |
| Chat message/history persistence | Repository + models | `data/SettingsRepository.kt` (chat keys), `chat/ChatModels.kt` | `ui/screens/ChatHistoryScreen.kt`/`ChatSessionScreen.kt` |
| Mesh relay (offline messaging) | `MeshRelayManager` | `chat/MeshRelayManager.kt`, `chat/MeshEnvelope.kt`, `chat/MeshIdentity.kt` | `service/ProtectionForegroundService.kt` (who owns the singleton) |
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
