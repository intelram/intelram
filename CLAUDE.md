# Thread Protection — working rules

Native Android app (Kotlin, Jetpack Compose, single-Activity MVVM). The codebase map is
`GRAPH_ENGINEERING_MAP.md` (~21K tokens) — use it before every code change, but don't read it whole:

1. Search its **§10 "Change → Affected Systems lookup"** table for the row matching the task
   (e.g. `grep -n -i "breach" GRAPH_ENGINEERING_MAP.md`).
2. Open only the files that row lists, and read the **§7 pitfall** entries it cites before editing them.
3. No matching row: read §1–§3 (architecture, packages, screen graph) to place the change.
4. Update the map in the same commit when **§11** says to.

## Standing rules
- No mocked data, fake connection states or hardcoded "results" — always show real device/network state.
- Never swallow exceptions silently; failures surface as a specific, actionable message.
- Verify with `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest` before committing.
  No device or emulator exists here — say so rather than claiming on-device testing.
- After a feature: render a visual preview and send it. Build the release APK only when the user types
  **"release apk"**: temporarily add `signingConfig = signingConfigs.getByName("debug")` to the release
  buildType, run `assembleRelease`, confirm `grep -c "org.bouncycastle.pqc.crypto.mlkem"
  app/build/outputs/mapping/release/seeds.txt` is 272, copy the APK out, then
  `git checkout -- app/build.gradle.kts`.
- Develop on `claude/prototype-implementation-live-2mmwnt`.
