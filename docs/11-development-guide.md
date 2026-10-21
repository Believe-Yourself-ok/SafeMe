# 11 — Development guide

Guidelines for building, testing, and extending SafeMe. Read
[01 — Architecture](01-architecture.md) and
[02 — Design philosophy](02-design-philosophy.md) first.

## 1. Build & toolchain

- **Android Gradle Plugin + Kotlin + Compose** (version catalog in
  `gradle/libs.versions.toml`).
- `minSdk 26`, `targetSdk 36`, `compileSdk 36`, Java 25 toolchain.
- Compose enabled via the Kotlin Compose plugin (`kotlin.compose`), BOM-managed
  dependencies: `ui`, `ui-graphics`, `ui-tooling-preview`, `material3`,
  `navigation-compose`, `lifecycle-viewmodel-compose`, `datastore-preferences`,
  `biometric`, `fragment-ktx`.
- Release: R8 minify + resource shrink.

### Common commands

```bash
./gradlew :app:compileDebugKotlin     # fast compile check
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:installDebug           # build + install to device/emulator
./gradlew :app:assembleRelease        # minified release APK
```

### Lint baseline

All known findings live in `app/lint-baseline.xml`, including the single
intentional `ProtectedPermissions` error for `WRITE_SECURE_SETTINGS`
(ADB-granted for the accessibility self-heal — see
[04 — Security architecture](04-security-architecture.md#write_secure_settings-intentional-lint-baseline)).
`lintDebug` is therefore green, and any **new** finding fails the build. Do
not "fix" the permission by removing it; do not add new findings on top.
To regenerate the baseline, delete `app/lint-baseline.xml` and re-run
`./gradlew :app:lintDebug` — only do this when a new finding is
intentionally accepted, never to hide a defect.

### Device setup for full feature testing

```bash
# Grant the a11y self-heal permission
adb shell pm grant com.safeme.app android.permission.WRITE_SECURE_SETTINGS

# Fresh-install onboarding state
adb shell pm clear com.safeme.app
```

## 2. Unit testing

JVM tests live in `app/src/test/java/com/safeme/app/` — 14 files covering
the pure logic:

| Area | Files |
|---|---|
| Backup | `BackupCodecTest` (codec round-trips, all validation branches, strict parsing), `BackupManagerTest` (restore pipeline with in-memory stores, rollback) |
| Blocking/data | `JsoncTest`, `ActivityLogTest`, `QuickActionsPrefsTest`, `ScheduleWarningTest`, `AppCatalogTest` |
| Protect | `FocusEvaluatorTest`, `A11yProtectionUtilsTest` (component parsing, add-only rewrite), `AppLockManagerTest` (PBKDF2, lockout schedule), `ProtectedSystemPagesTest`, `ProtectionLayersTest` |
| VPN | `TunnelRestartPolicyTest`, `VpnValidationTest` |

Notes:

- The Android `org.json` stub throws "not mocked", so the project adds the
  **real** `org.json` artifact as a `testImplementation` dependency — JSON
  codecs are unit-testable on the JVM.
- Pure decision objects (`FocusEvaluator`, `TunnelRestartPolicy`,
  `AppLockManager.lockoutDeadlineFor`, `AppCatalog.categorize`,
  `A11yProtectionUtils.parseFlatComponent/canonicalAppendOnly`,
  `ProtectedSystemPages.*`, `BackupCodec`) are deliberately Android-free so
  they can be tested without Robolectric.
- Follow the pattern: extract pure logic into an `object`/top-level function,
  keep Android I/O in suspend functions, test the pure part.

## 3. Conventions

### Kotlin & architecture

- **Layering:** `ui/` → `data/`; `protect/` and `service/` → `data/`; never
  the reverse. Services own no user state.
- **Data access:** screens collect `Context.xxxPrefs()` flows; mutations go
  through `suspend fun Context.xxx(...)` DataStore edit helpers. Never write
  DataStore from a composable.
- **Resilient parsing:** enum-like stored values parse via
  `fromName`/`fromStorage` with defaults; every `Flow` reader has
  `.catch { emit(emptyPreferences()) }`.
- **Fail-open:** detection code degrades to "not blocked"; every external
  write is wrapped so failure is a graceful no-op.
- **Idempotency:** coordinators (`FocusEngine`, guard `ensureWatching`,
  VPN apply) must stay safe to call repeatedly from any thread.
- **Backup parity:** any new persisted domain should be added to
  `BackupSection` + `BackupSnapshot` + `backupStores()` + the codec's
  `toJson`/`parse` pair so backups capture it.

### UI

- Use `LocalAppColors.current` tokens — no `Color(0x…)` literals in screens.
- Match the prototype: 20 dp card radius, 1 dp `line` borders, 16 dp card
  padding, 40 dp icon chips, serif display type for headlines.
- Every pushed screen: `statusBarsPadding()`, back-chevron header, slide
  transition (automatic via the NavHost).
- Async feedback goes through `ToastHost`.

### Comments

The codebase carries dense KDoc explaining *why* (threat models, OEM
behavior, measured tradeoffs) — especially in `service/`, `protect/`, and
`data/`. Keep that standard: document the reasoning and the failure modes,
not the obvious "what".

## 4. Adding a feature (workflow)

1. Check the design prototype for the target screen; replicate its tokens,
   spacing, and component shapes.
2. Add/verify the `data/` store: keys, `Flow` reader, mutators, resilient
   parsing, `writeXxxPrefs` if it belongs in backups.
3. Wire the coordinator (e.g. `FocusEngine`, VPN apply) if the feature
   needs enforcement.
4. Build the screen + ViewModel, register the route in `MainScreen.kt`.
5. Add unit tests for any pure logic; run `testDebugUnitTest`.
6. Run `compileDebugKotlin`, `lintDebug` (must stay green — the baseline
   covers the known `WRITE_SECURE_SETTINGS` finding; new findings fail),
   install, and smoke-test the screen end-to-end on the emulator.

## 5. Constraints checklist (do not break)

- Never leave the block gate looping over accessibility-management screens
  (a11y kill vector): the gate may appear over SafeMe's own a11y detail page,
  but dismissing it must bounce away (HOME) — never re-cover the page. See
  [04](04-security-architecture.md).
- Keep the a11y self-heal strictly add-only.
- Keep the VPN tunnel DNS-only (no packet inspection/relay).
- Keep the "block everything" schedule exempting
  `SCHEDULE_SYSTEM_EXEMPT` surfaces so the user is never locked out.
- Keep `WRITE_SECURE_SETTINGS` handling graceful without the grant.
- Don't add per-frame allocations to draw paths; gate periodic background
  work by need.
- Backup restore must validate before writing and roll back on failure.

## 6. CI/CD

`.github/workflows/ci.yml` runs on every push: it validates the Gradle
wrapper, then builds the debug and release APKs and runs the JVM unit
tests on `ubuntu-latest` (Temurin JDK 25 + Android SDK 36). APKs are
uploaded as build artifacts.

### Release signing in CI

The keystore is never committed. Configure these repository secrets to
produce a signed release APK:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of `keystore/safeme-release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | signing key alias (default `safeme`) |
| `KEY_PASSWORD` | key password |

When the secrets are absent (e.g. forks), `assembleRelease` falls back
to the app's documented unsigned output — the workflow still passes.

`lintDebug` runs in CI against `app/lint-baseline.xml`, which baselines the
single documented `ProtectedPermissions` finding (the ADB-granted
`WRITE_SECURE_SETTINGS`); new findings fail the build.
