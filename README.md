# SafeMe

SafeMe is an Android parental-control / digital-wellbeing app that blocks
adult content and distracting apps on the device. It combines on-screen
content blocking (keywords, websites, title rules), FocusMe focus sessions
(launch + internet), family-safe DNS filtering through a VPN-style
tunnel, an App Lock, uninstall protection (Device Admin + accessibility
guards), and a full backup/restore system.

The UI is a Jetpack Compose implementation of a fixed design prototype
(reference HTML prototype). Screens and interactions mirror the prototype's
design language pixel-for-pixel; enforcement machinery is original work
informed by the "Protect Yourself" reference project.

- **Package:** `com.safeme.app`
- **Min / target SDK:** 26 / 36
- **Version:** 0.1.0 (versionCode 1)
- **UI:** Jetpack Compose (Material 3), single-activity + fragment host
- **Persistence:** Jetpack DataStore (Preferences) — one file per domain
- **Build:** Kotlin + Compose Gradle plugin; release builds run R8 minify +
  resource shrinking (~3.1 MB APK)

---

## Feature overview

| Feature | How it works | Entry points |
|---|---|---|
| Keyword / website / title blocking | Accessibility service reads the foreground window, matches against bundled + user lists, raises a full-screen block gate | Home → Keywords; Blocking tab; Settings-pages title rules |
| FocusMe (scheduled focus sessions) | Pure decision core evaluates day/time windows; launch blocks gate apps via the a11y service, internet blocks go through the VPN tunnel | FocusMe tab |
| DNS filtering (VPN) | A `VpnService` advertises a family-safe resolver (Cloudflare / AdGuard Family / custom) — no traffic is routed into the TUN; filtering is delegated to the DNS provider | Blocking tab → DNS & VPN |
| App Lock | PBKDF2-hashed PIN / password / pattern gate with biometric unlock, auto-lock delays, and brute-force backoff / lockout | Home → App Lock; Profile |
| Prevent Uninstall | Device Admin (replaces Uninstall with Disable) + accessibility guards that block SafeMe's App-Info / Device-Admin / uninstall pages | Blocking tab → Anti-Tamper |
| Accessibility protection | Watches for protected a11y services being disabled/unbound (ContentObserver + poll) and re-enables them (needs `WRITE_SECURE_SETTINGS` via ADB) | Anti-Tamper → Accessibility protection |
| Backup & Restore | Versioned, human-editable JSONC file capturing all 8 config domains; atomic validate-before-write restore with rollback | Profile → Backup & Restore |
| Quick actions, activity feed, history | Home tab surfaces: curated quick actions, blocked-today counter, recent-activity log (capped, deduped) | Home tab |
| Onboarding | First-run permission walkthrough (notifications → battery → accessibility) with skip-if-already-granted | App start |

## Protection layers

The Home "Shield" summary counts 10 protection layers; each counts only when
it is genuinely functional right now:

1. Master blocking switch
2. Accessibility service
3. VPN / DNS filtering
4. App Lock
5. Accessibility protection
6. Prevent Uninstall
7. Enabled focus sessions
8. Content rules (keywords/websites)
9. Title rules
10. Device Admin

## Getting started

```bash
# Build and install the debug APK on a connected device/emulator
./gradlew :app:installDebug

# Run unit tests (JVM, no device needed)
./gradlew :app:testDebugUnitTest

# Run Android lint
./gradlew :app:lintDebug

# Build a release APK (R8 minify + resource shrink)
./gradlew :app:assembleRelease
```

### Release signing

Release builds are signed from `keystore.properties` (gitignored) when it
is present, and fall back to unsigned output otherwise. The file and the
keystore it points at are **never** committed — copy
[`keystore.properties.example`](keystore.properties.example) to
`keystore.properties` and fill in real values:

```bash
keytool -genkeypair -v -keystore keystore/safeme-release.jks -alias safeme \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=SafeMe, O=SafeMe, C=BD"
```

> **Back up `keystore/safeme-release.jks` and its passwords somewhere safe.**
> Android apps can only be updated in place by APKs signed with the same key;
> losing the keystore means every installed SafeMe must be uninstalled and
> reconfigured before any future release can be installed.

### Optional ADB grants

The Accessibility-protection self-heal feature rewrites
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which requires the
`WRITE_SECURE_SETTINGS` permission. It is **not** grantable from the UI;
grant it once over ADB for the feature to be able to write:

```bash
adb shell pm grant com.safeme.app android.permission.WRITE_SECURE_SETTINGS
```

Android lint reports this as a `ProtectedPermissions` error by design — the
permission is intentionally requested and ADB-granted; it is the pre-existing
baseline, not a defect. It is recorded in `app/lint-baseline.xml`, so
`lintDebug` stays green and only *new* findings fail the build. Without it
the self-heal degrades to detection + notification (throttled to once per
hour).

## Repository layout

```
SafeMe/
├── app/
│   ├── build.gradle.kts        # SDK versions, R8, dependencies
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/safeme/app/
│       │   │   ├── SafeMeApp.kt          # Application: caches + background coordinators
│       │   │   ├── MainActivity.kt       # Window theme + nav host + App Lock gate host
│       │   │   ├── BlockGateActivity.kt  # Full-screen block overlay host
│       │   │   ├── data/                 # DataStore prefs, backup codec, catalogs
│       │   │   ├── protect/              # Focus sessions, a11y protection, App Lock, Device Admin
│       │   │   ├── service/              # Accessibility + VPN services, boot receivers
│       │   │   ├── ui/                   # Compose screens, theme, components
│       │   │   └── vpn/                  # DNS presets, tunnel restart policy, status store
│       │   └── res/
│       └── test/java/com/safeme/app/     # JVM unit tests (pure logic)
└── docs/                                 # Detailed technical documentation (start here)
```

## Documentation

Detailed architecture, design rules, and implementation notes live in
[`docs/`](docs/):

| Doc | Contents |
|---|---|
| [01 — Architecture](docs/01-architecture.md) | Layers, module map, data flow, component relationships |
| [02 — Design philosophy](docs/02-design-philosophy.md) | Core principles, safety rules, ADRs |
| [03 — UI design system](docs/03-ui-design-system.md) | Theme tokens, typography, components, navigation |
| [04 — Security architecture](docs/04-security-architecture.md) | Permissions, App Lock, Prevent Uninstall, Device Admin, a11y protection |
| [05 — Blocking engine](docs/05-blocking-engine.md) | Accessibility service matching, block gate, cooldowns |
| [06 — FocusMe](docs/06-schedule-blocking.md) | Focus sessions: evaluator, engine, boundary alarms, safety ticker |
| [07 — VPN / DNS filtering](docs/07-vpn-dns-filtering.md) | Tunnel modes, presets, whitelist, watchdog, focus-session integration |
| [08 — Backup & Restore](docs/08-backup-restore.md) | JSONC format, codec, atomic restore, error model |
| [09 — App Picker](docs/09-app-picker.md) | App discovery, categorization taxonomy, grouped picker UI |
| [10 — Performance](docs/10-performance.md) | Measured optimizations and principles |
| [11 — Development guide](docs/11-development-guide.md) | Build/test workflow, conventions, constraints |

## Constraints at a glance

- The Accessibility service is the **only** content-blocking engine. The VPN
  tunnel filters DNS at the provider, never inspects traffic, and cannot block
  encrypted DNS (DoH/DoT/DoQ) — the same tradeoff the reference project accepts.
- The block gate **must never** be raised over accessibility-management
  screens: Android disables an accessibility service whose window obscures
  them. Those pages are self-heal territory only.
- The a11y self-heal is strictly **add-only** — it appends the protected
  services and never removes entries a user configured.
- All enforcement degrades **fail-open** (a missed block is safer than a false
  positive) and never crashes the process on malformed input.
