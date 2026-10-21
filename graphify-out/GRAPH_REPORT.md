# Graph Report - SafeMe  (2026-10-21)

## Corpus Check
- 188 files · ~186,102 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 30 file(s) not represented in the graph (top: .xml 16, .apk 3, .properties 2)

## Summary
- 2995 nodes · 8783 edges · 139 communities (99 shown, 40 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 626 edges (avg confidence: 0.91)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `1db06e07`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- BackupScreen
- 7. Prototype contract — what must not break
- BlockedCategory
- SafeMeVpnService
- TitleBlockViewModel.kt
- .matches
- Social Media Blocking — Detailed Execution Plan (SafeMe)
- DnsVpnViewModel
- BlockedCounterTest
- SchedulePrefs.kt
- icon_parity.py
- Composable
- ScheduleEvaluatorTest
- BackupCodecTest
- BlockScreen.kt
- BlockingViewModel.kt
- MainScreen
- ScheduleEditScreen
- Composable
- KeywordManagerScreen.kt
- ScheduleViewModel.kt
- asstateflow
- BlockSheets.kt
- PrivateDnsBlockersTest
- DnsVpnScreen
- SocialBlockingViewModel.kt
- Social Media Blocking — Final Execution Plan (Deep Reanalysis)
- BackupCodec
- BlockOverlayController.kt
- ScheduleScreen.kt
- VpnValidation
- VpnBlockersTest
- blurredShadow
- VpnAppsSheet
- HomeViewModel.kt
- AppLockInputs.kt
- Color.kt
- VpnPrefs.kt
- shots.mjs
- A11yProtectionPrefs.kt
- env.sh
- ScheduleEvaluator
- AppLockUnlockOverlay
- bootstrap.sh
- SafeMeAccessibilityService
- AboutScreen.kt
- ScheduleEditEnabledTest
- SafeMe sandbox toolchain — wipe survival kit
- app.js
- QuickActionType
- DeviceAdminUtils
- A11yProtectionUtils
- AppLockSheets.kt
- toast
- BlockingPrefs.kt
- BackupManager.kt
- BlockOverlayControllerTest
- isStableA11yDisabled
- .block
- AppCategory
- ScheduleEditViewModel.kt
- AppCatalogTest
- BlockingPrefsState
- SafeMeVpnService.kt
- BlockGateActivity
- Composable
- SocialBlockingScreen
- .evaluate
- 02 — Design philosophy
- executeRestore
- 3. Device checklist
- AppLockManager
- AppLockScreen
- verify_prototype.py
- .handleEvent
- UninstallBlockersTest
- MainActivity.kt
- 12 — WRITE_SECURE_SETTINGS protection plan
- A11yProtectionUtilsTest
- ProtectedSystemPagesTest
- saveLock
- Teck Stack ,Name & Details.md
- 2. Unit testing
- 03 — UI design system
- 11 — Development guide
- ServicePickerViewModel.kt
- isValidScheduleWindow
- ScheduleEngine
- resolveWhitelistSeed
- SafeMeAccessibilityService.kt
- BackupSection
- BlockGateActivityTest.kt
- 04 — Security architecture
- SAD-319 — Make the prototype exactly match production (SafeMe)
- AntiTamperScreen.kt
- AccessibilityProtectionScreen.kt
- BackupError
- .findMatch
- VpnStatusStore
- renderAppPicker
- AppLockViewModel.kt
- setDemo
- KeywordManagerScreen
- kwRender
- .toStrictJson
- ActivityLog.kt
- Theme.kt
- 08 — Backup & Restore
- 2. Principles
- .onCreate
- ProtectionLayersTest
- Release signing
- SafeMe
- .verify
- paintLockGuard
- Test
- AppLockBiometrics.kt
- Gate (`AppLockGateHost` / `AppLockGateController`)
- HistoryScreen.kt
- BundledKeywordCatalogTest
- A11yProtectionGuard
- renderTime
- LockType
- SafeMeApp.kt
- isOwnUiEvent
- TunnelRestartPolicyTest
- BottomNavBar
- A11yBootReceiver.kt
- AccessibilityProtectionViewModel.kt
- Prototype parity tooling (SAD-319)
- shouldThrottleScheduleRecheck
- setDemoLists

## God Nodes (most connected - your core abstractions)
1. `SafeMeAccessibilityService` - 75 edges
2. `ToastHost()` - 41 edges
3. `ScheduleEvaluatorTest` - 41 edges
4. `BlockingPrefsState` - 40 edges
5. `BackupCodecTest` - 37 edges
6. `DnsVpnViewModel` - 36 edges
7. `toast()` - 34 edges
8. `SafeMeTextField()` - 31 edges
9. `BlockedCategory` - 29 edges
10. `InstalledApp` - 28 edges

## Surprising Connections (you probably didn't know these)
- `Appendix C — File map for the implementation` --references--> `renderSocial()`  [INFERRED]
  PROTOTYPE_PARITY_PLAN_SAD-319.md → Reference/prototype/js/app.js
- `9. File Checklist (new/edit)` --references--> `renderSocial()`  [INFERRED]
  SOCIAL_MEDIA_BLOCKING_FINAL_PLAN.md → Reference/prototype/js/app.js
- `7. Navigation` --references--> `MainActivity`  [INFERRED]
  SOCIAL_MEDIA_BLOCKING_FINAL_PLAN.md → app/src/main/java/com/safeme/app/MainActivity.kt
- `New file: `data/SocialBlockingPrefs.kt`` --references--> `BackupCodec`  [INFERRED]
  SOCIAL_MEDIA_BLOCKING_EXECUTION_PLAN.md → app/src/main/java/com/safeme/app/data/BackupCodec.kt
- `Phase 1 — Data Layer (1d)` --references--> `BackupCodec`  [INFERRED]
  SOCIAL_MEDIA_BLOCKING_EXECUTION_PLAN.md → app/src/main/java/com/safeme/app/data/BackupCodec.kt

## Import Cycles
- None detected.

## Communities (139 total, 40 thin omitted)

### Community 0 - "BackupScreen"
Cohesion: 0.13
Nodes (13): BackupFile, createBackup(), ActionPill(), BackupActionCard(), BackupHeader(), BackupScreen(), BackupViewModel, cardShape() (+5 more)

### Community 1 - "7. Prototype contract — what must not break"
Cohesion: 0.14
Nodes (18): 7. Prototype contract — what must not break, appsDone(), back(), finishOnboard(), grantPerm(), kwAdd(), kwEdit(), NAV (+10 more)

### Community 2 - "BlockedCategory"
Cohesion: 0.11
Nodes (12): BlockedCategory, ADULT, CUSTOM, DISTRACTION, GAMBLING, SHOPPING, SOCIAL_MEDIA, categoryOf() (+4 more)

### Community 4 - "TitleBlockViewModel.kt"
Cohesion: 0.11
Nodes (23): addTitleBlockRule(), deleteTitleBlockRule(), TitleBlockRule, TitleMatchMode, CONTAINS, EXACT, STARTS_WITH, titleRulesFromJson() (+15 more)

### Community 5 - ".matches"
Cohesion: 0.25
Nodes (3): ImageVideoSearchGate, Match, ImageVideoSearchGateTest

### Community 6 - "Social Media Blocking — Detailed Execution Plan (SafeMe)"
Cohesion: 0.08
Nodes (24): blockingEnabled(), `data/` — persistence and pure domain logic, 0. Summary, 10. Navigation, 12. Testing Strategy, 13. Risks & Mitigations, 14. Acceptance Criteria / QA Checklist, 15. What I Need From You to Execute (+16 more)

### Community 7 - "DnsVpnViewModel"
Cohesion: 0.11
Nodes (3): DnsVpnUiState, DnsVpnViewModel, 4. Presentation (`ui/components/GroupedAppPicker.kt`)

### Community 8 - "BlockedCounterTest"
Cohesion: 0.21
Nodes (6): blockedDateKey(), blockedTodayFlow(), incrementBlockedToday(), nextBlockedCounter(), rolloverBlockedCount(), BlockedCounterTest

### Community 9 - "SchedulePrefs.kt"
Cohesion: 0.14
Nodes (18): addSchedule(), deleteSchedule(), ScheduleBlock, scheduleDaysLabel(), ScheduleMode, BOTH, INTERNET, LAUNCH (+10 more)

### Community 10 - "icon_parity.py"
Cohesion: 0.05
Nodes (32): all_kotlin_icons(), _arc_points(), _attrs(), _brace_block(), compare(), _cubic(), _d_attr(), fingerprint() (+24 more)

### Community 11 - "Composable"
Cohesion: 0.07
Nodes (48): BlockingScreen(), cardShape(), IconBox(), IconVariant, Amber, Dark, Green, Red (+40 more)

### Community 14 - "BlockScreen.kt"
Cohesion: 0.26
Nodes (11): BlockScreen(), bsImgColors(), CustomSwitch(), GhostBlockButton(), GradientTile(), GroupLabel(), HeaderRow(), ListRow() (+3 more)

### Community 15 - "BlockingViewModel.kt"
Cohesion: 0.12
Nodes (6): maybeRolloverBlockedCounter(), setBlockingEnabled(), setBlockingExcludedApps(), BundledKeywords, BlockingUiState, BlockingViewModel

### Community 16 - "MainScreen"
Cohesion: 0.06
Nodes (38): MainScreen(), PlaceholderScreen(), ManagePermissionsFlow(), OnboardingNavHost(), OnboardingViewModel, BackChip(), GrantPill(), HeroTitle() (+30 more)

### Community 17 - "ScheduleEditScreen"
Cohesion: 0.11
Nodes (30): AppsCard(), appSummary(), DayCircles(), DeleteButton(), EnabledRow(), GroupLabel(), Header(), ModeSegment() (+22 more)

### Community 18 - "Composable"
Cohesion: 0.10
Nodes (26): setThemePref(), ThemePref, DARK, LIGHT, SYSTEM, cardShape(), DeleteButton(), DeleteDialog() (+18 more)

### Community 20 - "ScheduleViewModel.kt"
Cohesion: 0.11
Nodes (8): scheduleTimeLabel(), setA11yWarningDismissed(), setExactAlarmWarningDismissed(), setExcludedApps(), ScheduleCard, ScheduleUiState, ScheduleViewModel, isExactAlarmGranted()

### Community 21 - "asstateflow"
Cohesion: 0.08
Nodes (4): AntiTamperUiState, AntiTamperViewModel, AppLockGateController, BlockScreenViewModel

### Community 22 - "BlockSheets.kt"
Cohesion: 0.35
Nodes (6): CustomMessageSheet(), ImgTile(), MotivationImageSheet(), SheetField(), SheetGrab(), SheetRow2()

### Community 24 - "DnsVpnScreen"
Cohesion: 0.19
Nodes (15): DnsPresetList(), DnsVpnScreen(), GroupLabel(), NotifSeg(), VpnDivider(), VpnDnsRow(), VpnHeaderRow(), VpnNoteRow() (+7 more)

### Community 25 - "SocialBlockingViewModel.kt"
Cohesion: 0.09
Nodes (15): applySocialPreset(), setSocialBlockingEnabled(), setSocialFacebook(), setSocialSnapchat(), setSocialWholeBlocked(), setSocialYoutube(), SocialBlockingPrefs, SocialBlockingState (+7 more)

### Community 26 - "Social Media Blocking — Final Execution Plan (Deep Reanalysis)"
Cohesion: 0.10
Nodes (20): 10. Risks & Mitigations, 11. Acceptance Criteria, 12. Ready to Execute, 1.3 Gap vs. desired, 1. Deep Reanalysis — What We Actually Found, 3. Design Philosophy Lock, 4.1 `data/SocialBlockingPrefs.kt` (new), 4.3 Backup (+12 more)

### Community 28 - "BlockOverlayController.kt"
Cohesion: 0.11
Nodes (3): BlockOverlayController, OverlayLifecycleOwner, BlockScreenPrefsState

### Community 32 - "blurredShadow"
Cohesion: 0.24
Nodes (3): blurredShadow(), 2. Shared components (`ui/components/`), Card language

### Community 33 - "VpnAppsSheet"
Cohesion: 0.24
Nodes (10): AppRow(), CustomDnsSheet(), VpnAppsSheet(), VpnCheckbox(), VpnDoneButton(), VpnSelectAllButton(), VpnSelectAllRow(), VpnSheetField() (+2 more)

### Community 34 - "HomeViewModel.kt"
Cohesion: 0.17
Nodes (9): A11yProtectionPrefsState, AppLockPrefsState, BackupSnapshot, PreventUninstallPrefsState, DnsVpnSettings, A11yStatus, HomeUiState, HomeViewModel (+1 more)

### Community 36 - "Color.kt"
Cohesion: 0.12
Nodes (6): iconBuilder(), iconBuilder(), iconBuilder(), iconBuilder(), iconBuilder(), iconBuilder()

### Community 37 - "VpnPrefs.kt"
Cohesion: 0.13
Nodes (15): clearPrivateDnsBackup(), PrivateDnsBackup, readPrivateDnsBackup(), savePrivateDnsBackup(), setVpnCustomDns(), setVpnNotifCustom(), setVpnNotifMode(), setVpnPreset() (+7 more)

### Community 38 - "shots.mjs"
Cohesion: 0.07
Nodes (26): { chromium }, HERE, loadPlaywright(), main(), MIME, ONLY, ORDER, OUT (+18 more)

### Community 40 - "A11yProtectionPrefs.kt"
Cohesion: 0.11
Nodes (14): a11yProtectionPrefs(), addProtectedA11yComponent(), removeProtectedA11yComponent(), setA11yProtectionEnabled(), writeA11yProtectionPrefs(), blockScreenPrefs(), writeBlockScreenPrefs(), contentEnginePrefs() (+6 more)

### Community 43 - "env.sh"
Cohesion: 0.22
Nodes (9): ANDROID_HOME, ANDROID_SDK_ROOT, GRADLE_USER_HOME, JAVA_HOME, PATH, env.sh script, gradle_run(), new_log() (+1 more)

### Community 44 - "ScheduleEvaluator"
Cohesion: 0.24
Nodes (6): ActiveRules, ScheduleEvaluator, 06 — Schedule-based blocking, 1. Data model (`data/SchedulePrefs.kt`), 2. Pure decision core (`protect/ScheduleEvaluator.kt`), 6. Enforcement surfaces

### Community 45 - "AppLockUnlockOverlay"
Cohesion: 0.25
Nodes (10): KeyCircle(), PasswordField(), PatternGrid(), PinDots(), PinKeypad(), shakeEffect(), MethodInput(), AppLockGateHost() (+2 more)

### Community 46 - "bootstrap.sh"
Cohesion: 0.36
Nodes (9): ANDROID_HOME, ANDROID_SDK_ROOT, ensure_jdk(), JAVA_HOME, ok(), PATH, bootstrap.sh script, step() (+1 more)

### Community 47 - "SafeMeAccessibilityService"
Cohesion: 0.14
Nodes (3): EventSnapshot, MatchResult, SafeMeAccessibilityService

### Community 48 - "AboutScreen.kt"
Cohesion: 0.53
Nodes (5): AboutHeader(), AboutIdentityCard(), AboutLinksList(), AboutRow(), AboutScreen()

### Community 50 - "SafeMe sandbox toolchain — wipe survival kit"
Cohesion: 0.25
Nodes (7): Last verified state, Recovery (one command), SafeMe sandbox toolchain — wipe survival kit, The problem, Two locations (identical files), What persists vs what doesn't, Why these exact choices

### Community 53 - "app.js"
Cohesion: 0.03
Nodes (63): ACTIVITY, APP_CATS, applyTheme(), appPickSel, APPS, BACKUP_ERR, blkExcludeDone(), blkExcludeDraft (+55 more)

### Community 54 - "QuickActionType"
Cohesion: 0.07
Nodes (32): quickActionPrefs(), quickActionsFromJson(), quickActionsToJson(), QuickActionType, APPLOCK, BACKUP, HISTORY, KEYWORD (+24 more)

### Community 55 - "DeviceAdminUtils"
Cohesion: 0.36
Nodes (3): DeviceAdminUtils, SafeMeDeviceAdminReceiver, Device Admin (`protect/DeviceAdminUtils.kt`)

### Community 56 - "A11yProtectionUtils"
Cohesion: 0.26
Nodes (3): A11yProtectionUtils, isAccessibilityEnabled(), 5. Accessibility protection (self-heal)

### Community 58 - "toast"
Cohesion: 0.11
Nodes (29): addKeyword(), addSite(), addTitle(), atToggleAdmin(), closeBlockov(), closeDlg(), closeSheets(), confirmDelete() (+21 more)

### Community 59 - "BlockingPrefs.kt"
Cohesion: 0.22
Nodes (23): addBlockedKeyword(), addBlockedWebsite(), addTrustedWebsite(), addWhitelistKeyword(), BlockedKeyword, BlockedWebsite, blockingPrefs(), keywordsFromJson() (+15 more)

### Community 60 - "BackupManager.kt"
Cohesion: 0.09
Nodes (12): A11yProtectionStore, AppLockStore, BackupStateStore, backupStores(), BlockingStore, BlockScreenStore, ContentEngineStore, PreventUninstallStore (+4 more)

### Community 61 - "BlockOverlayControllerTest"
Cohesion: 0.18
Nodes (5): blockActivitySub(), blockActivityTitle(), blockGateMessage(), blockGateWhyReason(), BlockOverlayControllerTest

### Community 63 - ".block"
Cohesion: 0.29
Nodes (4): requiresAccessibility(), shouldShowA11yWarning(), shouldShowExactAlarmWarning(), ScheduleWarningTest

### Community 64 - "AppCategory"
Cohesion: 0.12
Nodes (17): AppCategory, GAMES, MESSAGING, NEWS_PROD, OTHER, PAYMENT, SHOPPING, SOCIAL (+9 more)

### Community 65 - "ScheduleEditViewModel.kt"
Cohesion: 0.08
Nodes (9): AppCatalog, InstalledApp, Jsonc, newScheduleId(), Factory, ScheduleEditUiState, ScheduleEditViewModel, 1.2 Kotlin audit (`app/src/main`) (+1 more)

### Community 67 - "BlockingPrefsState"
Cohesion: 0.26
Nodes (3): BlockingPrefsState, isExcludedFromContentEngine(), ExcludeAppsE2ETest

### Community 69 - "BlockGateActivity"
Cohesion: 0.09
Nodes (19): BlockGateActivity, 1.1 Architecture overview, 1.2 Components and permissions (manifest), 1.3 Accessibility service configuration, 1.4 The detection core (`MyAccessibilityService.checkPreventUninstall`), 1.5 Execution flow (detection → protection), 1.6 Timing characteristics (static analysis), 1.7 Live measurement attempt (+11 more)

### Community 70 - "Composable"
Cohesion: 0.31
Nodes (10): AppLockSetupSheet(), AutoLockSheet(), CheckBox(), ConfirmInput(), CreateInput(), GhostButton(), GrabBar(), MethodList() (+2 more)

### Community 71 - "SocialBlockingScreen"
Cohesion: 0.23
Nodes (13): FootnoteCard(), LaunchBlockSection(), LaunchItem, LaunchRow(), MasterCard(), PresetCard(), QuickFocusModeRow(), SocialBlockingScreen() (+5 more)

### Community 72 - ".evaluate"
Cohesion: 0.24
Nodes (3): BrowserUrlGate, UrlMatch, BrowserUrlGateTest

### Community 73 - "02 — Design philosophy"
Cohesion: 0.18
Nodes (11): 02 — Design philosophy, 2. Safety rules (hard constraints), 3. Security posture, 4. Reliability posture, 5. Performance posture, 6. Decision log (ADRs), ADR-1: DNS-delegated filtering instead of a local DNS blocklist, ADR-2: Strict blocking-section validation in backups (+3 more)

### Community 74 - "executeRestore"
Cohesion: 0.34
Nodes (3): executeRestore(), BackupManagerTest, FakeStore

### Community 75 - "3. Device checklist"
Cohesion: 0.07
Nodes (26): 1. The gate, 2. The screenshot pack, 3. Device checklist, 4. Known, accepted differences, 5. Sign-off, About — `about`, Anti-tamper / Self-protection / Service picker, App Lock — `applock` (+18 more)

### Community 77 - "AppLockScreen"
Cohesion: 0.21
Nodes (13): AppLockScreen(), autoLockValue(), Chevron(), DisableButton(), Header(), HeroCard(), LockActions(), MasterSwitch() (+5 more)

### Community 78 - "verify_prototype.py"
Cohesion: 0.06
Nodes (50): is_domain(), literals_in_listof(), main(), measure(), regenerate(), render(), colour_kt(), grab() (+42 more)

### Community 79 - ".handleEvent"
Cohesion: 0.14
Nodes (7): SocialBlockingGate, SocialVertical, REELS, SHORTS, SPOTLIGHT, Phase 7 — Docs & Cleanup (0.5d), Phase 2 — Gates (1.5d) — depends 1.1

### Community 81 - "MainActivity.kt"
Cohesion: 0.10
Nodes (15): A11yProtectionStateHolder, ThemePrefHolder, 01 — Architecture, 1. Big picture, 2. Layer map, 3. Module-by-module, 4. Startup sequence, 5. Key data flows (+7 more)

### Community 82 - "12 — WRITE_SECURE_SETTINGS protection plan"
Cohesion: 0.12
Nodes (16): 12 — WRITE_SECURE_SETTINGS protection plan, 1. Goal and the honest answer, 2. Threat model: who can revoke today, 3.1 Module-level change list, 3.2 APIs, 3. Phase 1 — watchdog + in-app password gating (no new privileges), 4.1 Manifest & provisioning, 4.2 New `protect/DeviceOwnerManager.kt` (+8 more)

### Community 86 - "saveLock"
Cohesion: 0.19
Nodes (14): getWizCode(), openLockSetup(), patTap(), pinBackS(), refreshWizBtn(), renderDotsId(), resetWiz(), saveLock() (+6 more)

### Community 87 - "Teck Stack ,Name & Details.md"
Cohesion: 0.13
Nodes (14): Android App, Architecture, Background Processing, Build System, CI/CD, Code Quality, Dependency Injection, Dependency Management (+6 more)

### Community 89 - "03 — UI design system"
Cohesion: 0.13
Nodes (13): AppColors, `ui/` — Compose, 03 — UI design system, 1. Theme, 5. Prototype parity, 6. Writing a new screen, Authority model, Colors (`ui/theme/Color.kt`) (+5 more)

### Community 90 - "11 — Development guide"
Cohesion: 0.22
Nodes (9): 11 — Development guide, 1. Build & toolchain, 4. Adding a feature (workflow), 5. Constraints checklist (do not break), 6. CI/CD, Common commands, Device setup for full feature testing, Lint baseline (+1 more)

### Community 91 - "ServicePickerViewModel.kt"
Cohesion: 0.13
Nodes (7): ProtectedServiceEntry, MasterSwitch(), PickerRow(), SearchField(), ServicePickerScreen(), ServicePickerUiState, ServicePickerViewModel

### Community 93 - "ScheduleEngine"
Cohesion: 0.11
Nodes (10): ScheduleEngine, 1.2 Add-only writes for system settings, 1.3 Never lock the user out, 1.4 Idempotent coordinators, 1.5 Never crash on the user's data, 1.6 Mirror the prototype, not the reference code, 1. Core principles, 6. Relationship to other features (+2 more)

### Community 95 - "SafeMeAccessibilityService.kt"
Cohesion: 0.09
Nodes (6): UninstallBlockers, PrivateDnsBlockers, VpnBlockers, shouldThrottleAppContentRecheck(), shouldThrottleSocialTabRecheck(), 1.1 Fail open, never fail closed on detection

### Community 96 - "BackupSection"
Cohesion: 0.12
Nodes (16): BackupSection, A11Y_PROTECTION, APP_LOCK, BLOCK_SCREEN, BLOCKING, CONTENT_ENGINE, PREVENT_UNINSTALL, QUICK_ACTIONS (+8 more)

### Community 98 - "04 — Security architecture"
Cohesion: 0.29
Nodes (7): 04 — Security architecture, 1. Permissions model, 3. Prevent Uninstall & Device Admin, Accessibility guards (`service/SafeMeAccessibilityService.handlePreventUninstall`), `SCHEDULE_EXACT_ALARM` (user-granted, Play-declared), The a11y kill vector (critical), `WRITE_SECURE_SETTINGS` (intentional lint baseline)

### Community 99 - "SAD-319 — Make the prototype exactly match production (SafeMe)"
Cohesion: 0.10
Nodes (20): 10. Production-side debt found during the audit (**recorded, not fixed** — Kotlin is frozen), 11. Decisions I need from you (with a recommendation each), 1. Decision record (locked with you before writing), 1a. Delivery status, 2. Authority model — how each disagreement is settled, 3.1 Artefact inventory, 3.2 What is already at parity (do not touch), 3.3 The five real problem classes (+12 more)

### Community 100 - "AntiTamperScreen.kt"
Cohesion: 0.17
Nodes (9): HostToast, ToastHost(), ToastPill(), AntiTamperScreen(), cardShape(), Header(), Note(), PreventUninstallCard() (+1 more)

### Community 101 - "AccessibilityProtectionScreen.kt"
Cohesion: 0.32
Nodes (9): AccessibilityProtectionScreen(), cardShape(), copyToClipboard(), Header(), MasterCard(), MasterSwitch(), Note(), PermissionCard() (+1 more)

### Community 102 - "BackupError"
Cohesion: 0.10
Nodes (19): BackupError, EMPTY, INVALID_STRUCTURE, NOT_JSON, NOT_SAFEME, ROLLBACK_FAILED, UNSUPPORTED_VERSION, WRITE_FAILED (+11 more)

### Community 103 - ".findMatch"
Cohesion: 0.16
Nodes (12): 05 — Blocking engine (accessibility service), 1. Event pipeline, 2. Rule sources, 3. Matching semantics, 4. Block gate (`BlockGateActivity`), 5. Robustness guarantees, Cooldowns & dedup, Keyword matching (`findMatch`) (+4 more)

### Community 104 - "VpnStatusStore"
Cohesion: 0.10
Nodes (15): VpnBootReceiver, VpnStatusStore, `service/` — the enforcement engines, 07 — VPN / DNS filtering, 1. Architecture: DNS-delegated filtering, 2. Tunnel modes, 3. Lifecycle, 5. Schedule integration (+7 more)

### Community 105 - "renderAppPicker"
Cohesion: 0.15
Nodes (27): appByName(), appChip(), applyPreset(), classifyApp(), deselectAllApps(), deselectAllBlkExclude(), deselectAllExclude(), deselectAllSocialApps() (+19 more)

### Community 106 - "AppLockViewModel.kt"
Cohesion: 0.09
Nodes (16): appLockPrefs(), AutoLockDelay, AFTER_15S, AFTER_1M, AFTER_30S, AFTER_5M, IMMEDIATELY, OFF (+8 more)

### Community 107 - "setDemo"
Cohesion: 0.11
Nodes (20): bioUnlock(), closeLockov(), demoBackup(), demoLockout(), lockForgotTap(), lockNow(), openA11y(), openExactAlarm() (+12 more)

### Community 108 - "KeywordManagerScreen"
Cohesion: 0.20
Nodes (16): ActionButton(), CategoryPicker(), EmptyState(), EntryRow(), Keyword, KeywordManagerScreen(), KeywordSheet(), SearchField() (+8 more)

### Community 109 - "kwRender"
Cohesion: 0.32
Nodes (8): kwGo(), kwLabels(), kwOverride(), kwRemove(), kwRender(), kwRowHTML(), kwTab(), kwType()

### Community 112 - "ActivityLog.kt"
Cohesion: 0.26
Nodes (7): ActivityEntry, activityFromJson(), activityLog(), activityToJson(), addActivity(), appendActivity(), ActivityLogTest

### Community 113 - "Theme.kt"
Cohesion: 0.16
Nodes (3): findActivity(), SafeMeApp(), SafeMeTheme()

### Community 114 - "08 — Backup & Restore"
Cohesion: 0.50
Nodes (4): 08 — Backup & Restore, 2. File format, 3. Export, 6. Notes & behavior verified on-device

### Community 115 - "2. Principles"
Cohesion: 0.22
Nodes (9): 10 — Performance, 1. Measured results (baseline → after), 2. Principles, 3. What is deliberately NOT optimized further, 4. Verification workflow, Bound every per-event cost, Compose hygiene, Gate background work by need (+1 more)

### Community 116 - ".onCreate"
Cohesion: 0.17
Nodes (3): SafeMeProtectionService, 6. Architecture, Package structure (new vs. edit)

### Community 118 - "Release signing"
Cohesion: 0.20
Nodes (9): CI key identity (canonical), CI (recommended for releases), Local (developer machine), Reference builds, Release signing, Rules, Signing sources, TWO signing keys exist — they are NOT interchangeable (+1 more)

### Community 119 - "SafeMe"
Cohesion: 0.22
Nodes (9): Constraints at a glance, Documentation, Feature overview, Getting started, Optional ADB grants, Protection layers, Release signing, Repository layout (+1 more)

### Community 120 - ".verify"
Cohesion: 0.18
Nodes (14): 2. App Lock, Storage (`data/AppLockPrefs.kt`), Verification & rate limiting (`protect/AppLockManager.kt`), 5. Work breakdown, Phase 0 — Audit harness (no UI change), Phase 1 — Foundations (shared components, once, for all screens), Phase 2 — Chrome & navigation, Phase 3 — State machines (+6 more)

### Community 121 - "paintLockGuard"
Cohesion: 0.21
Nodes (15): checkUnlock(), fmt(), formatLockout(), kwMgCard(), lockBlocked(), lockGuardRemaining(), methodLabel(), paintLockGuard() (+7 more)

### Community 125 - "HistoryScreen.kt"
Cohesion: 0.32
Nodes (6): formatActivityTime(), dotColor(), HistoryHeader(), HistoryRow(), HistoryScreen(), HistoryViewModel

### Community 126 - "BundledKeywordCatalogTest"
Cohesion: 0.12
Nodes (12): BundledKeywordCatalog, BundledKeywordCatalogTest, 1. Curated adult list (`data/BundledAdult.kt`), 2. NopoX catalog (`data/BundledKeywordCatalog.kt`), 3. Recovery whitelist seed, ADULT — Keywords (20), ADULT — Websites (113), Dataset totals (ADULT ONLY) (+4 more)

### Community 128 - "renderTime"
Cohesion: 0.25
Nodes (11): fmt12h(), h12(), openSchedEdit(), openTime(), renderTime(), schedOvernightCap(), setTimep(), timeDone() (+3 more)

### Community 129 - "LockType"
Cohesion: 0.25
Nodes (6): LockType, OFF, PASSWORD, PATTERN, PIN, methodLabel()

### Community 130 - "SafeMeApp.kt"
Cohesion: 0.14
Nodes (3): ScheduleAlarmReceiver, SafeMeApp, 3. Coordinator (`protect/ScheduleEngine.kt`)

### Community 132 - "TunnelRestartPolicyTest"
Cohesion: 0.22
Nodes (6): Outcome, KEEP_WAITING, TUNNEL_DEAD, TunnelRestartPolicy, TunnelRestartPolicyTest, 4. Watchdog (`vpn/TunnelRestartPolicy.kt`)

### Community 133 - "BottomNavBar"
Cohesion: 0.43
Nodes (4): BadgeDot(), BottomNavBar(), NavDestination, NavItem()

### Community 136 - "Prototype parity tooling (SAD-319)"
Cohesion: 0.29
Nodes (6): Copy provenance for rendered text (group K), Driving states, Formatted copy, Prototype parity tooling (SAD-319), Sign-off, What is asserted, and against what

### Community 138 - "setDemoLists"
Cohesion: 0.13
Nodes (19): actAdd(), actDot(), clockDateLine(), clockGreeting(), daysLabel(), delSchedule(), excludeDone(), feedRender() (+11 more)

## Knowledge Gaps
- **80 isolated node(s):** `SOCIAL`, `VIDEO_MUSIC`, `MESSAGING`, `SHOPPING`, `PAYMENT` (+75 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 618 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **40 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AppCatalog` connect `ScheduleEditViewModel.kt` to `AppCategory`, `AppCatalogTest`, `BackupError`, `DnsVpnViewModel`, `renderAppPicker`, `BlockingViewModel.kt`, `KeywordManagerScreen.kt`, `ScheduleViewModel.kt`, `QuickActionType`, `SocialBlockingViewModel.kt`, `ScheduleScreen.kt`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `SafeMeAccessibilityService` (e.g. with `1. Big picture` and `6. Process components and receivers`) actually correct?**
  _`SafeMeAccessibilityService` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `SOCIAL`, `VIDEO_MUSIC`, `MESSAGING` to the rest of the system?**
  _80 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `BackupScreen` be split into smaller, more focused modules?**
  _Cohesion score 0.13 - nodes in this community are weakly interconnected._
- **Why does `1.1 Prototype audit (`Reference/prototype`)` connect `renderAppPicker` to `ScheduleEditViewModel.kt`, `Social Media Blocking — Final Execution Plan (Deep Reanalysis)`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `ToastHost()` (e.g. with ``ui/` — Compose` and `2. Shared components (`ui/components/`)`) actually correct?**
  _`ToastHost()` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Should `7. Prototype contract — what must not break` be split into smaller, more focused modules?**
  _Cohesion score 0.13725490196078433 - nodes in this community are weakly interconnected._