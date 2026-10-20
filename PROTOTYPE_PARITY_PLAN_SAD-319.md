# SAD-319 — Make the prototype exactly match production (SafeMe)

**Status:** PLAN ONLY. No application file has been modified. This document is the only artefact.
**Issue:** [SAD-319](https://linear.app/sad-mod/issue/SAD-319/making-the-prototype-exactly-as-my-production) — *"Make the prototype match the production version exactly. Ensure that the UI, layout, styling, branding, colors, typography, and interactions are consistent with the production implementation. Do not introduce unnecessary changes or deviations."*
**Repo:** `Believe-Yourself-ok/SafeMe` @ `faf4ede` (cloned locally at `/home/user/SafeMe`)
**Date of audit:** 2026-10-20

---

## 1. Decision record (locked with you before writing)

| # | Question | Your answer → plan constraint |
|---|---|---|
| 1 | Which artefact is authoritative when they disagree? | **Production (Kotlin/Compose) is truth → fix the prototype.** No Compose layout, colour, or typography is re-derived from the HTML. |
| 2 | Scope? | **Whole app** — all 24 prototype screens + every sheet/dialog, not just the drifted ones. |
| 3 | How is "exactly" proven? | **Plan + rebuild the audit tooling.** I specify/build `verify_prototype.py`, a jsdom smoke test and a screenshot harness; the final visual sign-off is a numbered device checklist you run. |
| 4 | May the plan touch Kotlin? | **No. Prototype + docs only. Zero edits to `.kt`, `.xml`, `.gradle`.** Production-side debt found during the audit is *recorded* (§10), not fixed. |

**Hard constraint carried through every phase:** the modification must not influence other existing features/functionality. For this task the blast radius is `Reference/prototype/**` + `docs/**`; the Android build, CI (`ci.yml` builds/tests/signs the APK), DataStore schema, and the shipped APK are untouched by construction.

---

## 2. Authority model — how each disagreement is settled

Because Kotlin is frozen and is the reference, every pixel in the prototype is *derived*, never invented. Precedence, highest first:

1. **Existence** — if production renders it, the prototype must render it. If production never built it, the prototype must not show it. (This is what `36826a6` did when it deleted 10 screens.)
2. **Behaviour/state — production's state machine wins.** If production has 3 hero titles (`Fully protected` / `Partially protected` / `Protection paused`), the prototype must be able to show all 3, driven by a real switch in the toolbar, not a hardcoded string.
3. **Measurement — production's numbers win.** `dp`, `sp`, radii, paddings, gaps, stroke widths, durations, alpha values are copied from the Compose source. The prototype's CSS is edited to equal them.
4. **Copy — production's rendered string wins**, including `%1$d` formatting and pluralisation, and including production's *own* quirks (e.g. `Select All (0)` showing the same count as `Deselect All (0)`).
5. **Design intent — prototype keeps authority only where production has no opinion**: the desktop harness (`.ptb` toolbar, `.stage`, `.phone` bezel, `.notch`, `.statusbar`), the deliberate demo datasets *if* you choose to keep them (§11, Decision D-1), and icon *path data* where production's vector is visually identical but numerically different.

**Measurement contract (already established by `36826a6`, reused here):** `.phone` is 392 px wide with the bezel drawn as an `inset box-shadow`, so the content box is exactly 392 px and **1 CSS px = 1 dp**. Font sizes map px → sp 1:1. CSS `line-height: 1.3` on a 13 px font → `lineHeight = 17.sp` (`round(13 × 1.3)`). Any assertion that a value is off must quote both sides in this unit system.

**Tie-breaker for "production is arguably worse":** items where copying production makes the prototype objectively uglier (missing `max-width` on toasts, centred vs left empty-state text, `Select All (N)` double-counting) are **not** silently copied. They are collected in §11 as decisions with a recommendation, so you decide once instead of me guessing 30 times.

---

## 3. Verified baseline — what the audit actually found

Everything below was produced by reading the cloned tree and by scripts run against it (`§7` reproduces them). No figure in this document is estimated.

### 3.1 Artefact inventory

| Thing | Count | Evidence |
|---|---|---|
| Prototype `<section class="screen">` | **24** | ids: `welcome, permissions, permbattery, perma11y, home, block, schedule, profile, keywords, blockscreen, vpn, antitamper, selfprotect, titleblock, applock, backup, about, troubleshoot, history, scheduleedit, otherfeatures, socialblocking, servicepicker, quickactions` |
| Prototype sheets | **15** | `sheetKw, sheetSite, sheetTitle, sheetLockSetup, sheetAutoLock, sheetBsImg, sheetBsMsg, sheetTime, sheetApps, sheetDnsCustom, sheetVpnApps, sheetSocialApps, sheetDelete, sheetRestore, sheetDelay` |
| Prototype size | 995 HTML / 567 CSS / 1285 JS lines | `wc -l` |
| Production routes | **24** | 21 `composable()` in `MainScreen.kt` + `welcome` + 3 permission steps in `OnboardingNavHost.kt` |
| Production UI | 71 `.kt` files, **20,729 lines** | `wc -l app/src/main/java/com/safeme/app/ui/**` |
| Icons | **81** hand-ported `ImageVector`s / 55 `path{}` blocks vs **150** inline `<svg>` in the prototype | `*Icons.kt` vs `index.html` |
| Strings | 622 keys in `strings.xml`; **569 referenced from Kotlin**, **573 referenced from anywhere** (4 more come from `res/xml` + manifest), **49 unreferenced** (48 × `social_*` + `bs_toast_why`) | grep of `R.string.` + `@string/` |
| Kotlin build/run in this sandbox | **Impossible** — no Android SDK, JDK 11 only (`java -version` → 11.0.25), no emulator | verified by `which`/`ls` |

### 3.2 What is already at parity (do not touch)

- **Colour tokens: 13/13 identical in light, 13/13 in dark.** `:root` ↔ `LightAppColors`, `:root[data-theme="dark"]` ↔ `DarkAppColors`. Every hex matches (`#D97757`, `#B45A3B`, `#FBEFE8`, `#FDF8F4`, `#1F1A16`, `#6B625A`, `#A89E94`, `#FAF7F3`, `#FFFFFF`, `#EAE3DB`, `#2E7D5B`, `#C0822B`, `#C4453C` + the 13 dark overrides). **Zero colour work is needed at token level.**
- **Screen-set parity: 24 = 24.** No screen is missing on either side.
- **Structural integrity of the prototype:** 0 dangling `getElementById` (136 refs), 0 undefined inline handlers (265 handlers), 0 dead `nav()` targets, CSS braces balanced 436/436, renders correctly (screenshot baseline captured, `baseline_proto_social.png`).
- **Copy parity, measured exactly: 471 of 613 production strings (76.8%) appear verbatim in the prototype.** The other 142 are not noise — they decompose as: **53** Android system notification/toast strings the prototype legitimately has no surface for, **23** containing format placeholders (`%1$d`) the prototype composes at runtime, and **≈66 genuine UI-copy gaps**, enumerated with the fix in Appendix B.1. My first pass over-reported parity; the corrected number is what the phases work against.

### 3.3 The five real problem classes

| Class | Meaning | Size |
|---|---|---|
| **ADD** | Production renders something the prototype has no markup for | 10 items |
| **FIX** | Prototype value ≠ production value (geometry/colour/motion/copy) | 18 items |
| **DELETE** | Prototype-only UI production never built (dead/orphan) | 2 items |
| **STATE** | Prototype hardcodes one state; production is a state machine | 12 screens |
| **HYGIENE** | Production-side debt found while auditing (recorded, **not fixed**) | 4 items |

---

## 4. Drift register (measured)

Severity: **S1** = an existing production feature is unrepresentable in the prototype · **S2** = visible difference in normal use · **S3** = cosmetic/secondary · **S4** = harness/hygiene.
Every row carries both anchors so the implementer never has to guess a value. **Tally: A-01…A-20 (20 cross-cutting) + B-01…B-22 (22 per-screen) = 42 rows.**

### 4.1 Cross-cutting (shared components)

| ID | Sev | Class | Prototype (anchor → value) | Production (anchor → value) | Action |
|---|---|---|---|---|---|
| **A-01** | S1 | ADD | — | `strings.xml`: `home_hero_title`/`home_hero_partial`/`home_hero_paused`; `HomeViewModel.kt:163-182` builds title + subtitle + pill from layer state | Prototype Home must carry all 3 hero titles, 3 subtitles (`All %1$d layers active`, `%1$d of %2$d layers active · %1$d need attention`, `Turn on master protection to resume`) and 3 pill texts (`Protected`/`Attention`/`Paused`). Currently `index.html:114,119,120` hardcode the *partial* variant only. |
| **A-02** | S2 | FIX | `.ring .fg{stroke:var(--brand)}` (`styles.css:196`) | `HomeScreen.kt:338` → `drawArc(color = Color.White, …)` in `HeroRing` | Hero ring arc → white in the prototype (track stays `#EADFD6`). |
| **A-03** | S2 | FIX | `.toasts{bottom:90px}` (`styles.css:346`) + `.toast{max-width:88%}` (`styles.css:347-349`) | `ToastHost.kt:83-97` pill; **13** call sites use `padding(bottom = 24.dp)` (e.g. `HomeScreen.kt:158`) | Anchor the toast stack 24 dp above the screen bottom; decide toast `max-width` in D-3. |
| **A-04** | S2 | FIX | `.feed-empty{…text-align:center}` (`styles.css:549`) | `HomeScreen.kt` `FeedCard` empty `Text` — left aligned, `padding(vertical = 16.dp)` | Centre → left. |
| **A-05** | S2 | FIX | `.feed .fd{width:10px;height:10px;margin-top:5px}` (`styles.css:239`, dot top-aligned) | `FeedRow` uses `verticalAlignment = CenterVertically` | Drop the 5 px offset. |
| **A-06** | S2 | FIX | `.shield .s-left{gap:14px}` (`styles.css:224`) | `BlockingScreen.kt:201` `ShieldCard` → `Spacer(12.dp)` between icon and text | 14 → 12. |
| **A-07** | S2 | FIX | `.qa .ic` = 38×38, r12, svg 19, `margin-bottom:10px` (`styles.css:209-212`) | Home `QuickActionCard` = 38/12/19/10 (**match**); Blocking `MoreCard` → `IconBox(40.dp, iconSize 20.dp, radius 13.dp)` + `Spacer(12.dp)` (`BlockingScreen.kt:598-606`) | One `.qa` class cannot express both. The file already solves exactly this for Profile (`#sc-profile .qa .ic{width:40px;height:40px;border-radius:13px}` at `styles.css:506`), so add the Blocking counterpart: `#sc-block .qa .ic{width:40px;height:40px;border-radius:13px;margin-bottom:12px}` + `#sc-block .qa .ic svg{width:20px;height:20px}`. Verified: Profile 40/13/19 matches production (`ProfileScreen.QaCard` → `IconBox(40, iconSize 19, radius 13)`) — only Blocking is off. |
| **A-08** | S3 | FIX | `.screen{transform:translateX(24px); transition:opacity .22s, transform .26s}` (`styles.css:56`) | `MainScreen.kt:76-82` → `slideInHorizontally(250) + fadeIn(250)`, exit `fadeOut(200)`, pop reversed | Full-width slide, 250/200 ms (D-2). |
| **A-09** | S4 | — | `.ptb` / `.stage` / `.phone` / `.notch` / `.statusbar` | no counterpart | Keep — documented as harness chrome, out of scope. |
| **A-10** | S2 | ADD | — | `SocialBlockingScreen.kt` `FootnoteCard()` "How it works — Whole-app blocks on launch…"; tab pill has a **disabled** variant (`surface`/`line`/`ink3` when master off or `activeTabs == 0`); launch list renders **extras** (apps beyond the 5 defaults, subtitle = package name) | Prototype `#sc-socialblocking` must gain the footnote card, the pill's disabled style, and an extras mechanism. |
| **A-11** | S2 | FIX | `index.html:479` → `Version 1.0.76 · package protect.yourself` | `app/build.gradle.kts:36-37` → `versionName 0.1.0`, `applicationId com.safeme.app`; rendered by `AboutScreen.kt:147` as `Version %1$s · package %2$s` | `Version 0.1.0 · package com.safeme.app`. |
| **A-12** | S2 | FIX | `index.html:179` → `0 keywords · 0 websites · 0 trusted` (static) | `blk_manage_sub` = `%1$s keywords · %2$s websites · %3$s trusted`, formatted from live counts in `BlockingViewModel` | Wire the string to the demo state so counts can never be inconsistent with the tiles above. |
| **A-13** | S2 | FIX | Home `#homeBlocked` / `#homeSchedules` static `0`; Blocking `454` / `3` static | `home_*` / `blk_*` values computed (`blockedToday`, `scheduleCount`, `keywords`, `layersActive`) | Values must be produced by one demo-state object (§7.3) instead of hardcoded HTML. |
| **A-14** | S2 | FIX | Pill dot colour fixed per class (`.pill.g`) | Home pill flips green→amber by state (`HomeViewModel.kt:177-182`) | See A-01. |
| **A-15** | S2 | — | 150 inline `<svg>` (raw path data in markup) | 81 `ImageVector`s in 13 `*Icons.kt` files | Phase 6 slot-by-slot comparison; the 55 KDoc-poor vectors in `AntiTamperIcons/AppLockIcons/DnsVpnIcons/BlockScreenIcons/PermissionIcons/BackupIcons` are the highest risk. |
| **A-16** | S2 | FIX | Block-screen copy is a lossy paraphrase: `Closing in 5s…`, `Time before close unlocks · 3–120s`, `Close-gate · dwell countdown`, `"Stay safe, Alex. You chose this."`, `None · SAF persistable URI` | `bs_closing` = `Closing in %1$ds.`, `bs_dwell_sub` = `Time before Close unlocks • 3-120s`, `bs_overlay_label` = `Close-gate • dwell countdown`, `bs_img_sub_none` = `None • SAF persistable URI`, `bs_img_sub_set` = `%1$s • persistable`, `bs_why_fallback` | Replace the prototype's paraphrases with the exact production strings, including `•` (U+2022, not `·`) and the live `%1$d` countdown. |
| **A-17** | S2 | ADD | No backup progress or error state at all | `backup_exporting`, `backup_restoring`, and **7** error strings (`backup_err_not_json`, `_not_safeme`, `_version`, `_structure`, `_empty`, `_write`, `_rollback`) | Add the in-progress + 7 error variants to the prototype's Backup screen (this is B-18's concrete content). |
| **A-18** | S3 | FIX | TitleBlock placeholders are invented (`Search rules…`, `e.g. Apps` absent) | `tb_search_placeholder`, `tb_sheet_field_placeholder` | Use production's exact placeholder copy. |
| **A-19** | S3 | FIX | `Granted ✓` and the excluded-apps sheet subtitle are missing/paraphrased | `perm_granted` = `Granted ✓`, `blk_exclude_sheet_sub` = `Excluded apps are never blocked by keyword, porn keyword, website, or …`, `bs_img_sub_sheet` = `Shown on the block screen behind your message` | Copy verbatim. |
| **A-20** | S3 | STATE | Greeting hardcoded `Good evening, Alex` | `home_greeting_morning`/`_afternoon`/`_evening` selected by clock (`HomeViewModel`) | Drive the greeting from the demo clock (part of the Phase-3 state object). |

### 4.2 Per-screen

| ID | Screen (route) | Sev | Class | Finding |
|---|---|---|---|---|
| **B-01** | home | S1 | STATE | Hero/pill state matrix absent (A-01). Also missing: `home_hero_attention` pluralisation and the paused resume line. |
| **B-02** | home | S2 | STATE | A11y banner: production renders it **only** when `!a11yEnabled && a11yStateKnown && !a11yChecking` (`HomeScreen.kt:129`); prototype always shows it. Needs the "checking/unknown" state. |
| **B-03** | home | S2 | ADD | Production has **7** quick-action types (`QuickActionType`: KEYWORD, SCHEDULE, BACKUP, WEBSITES, VPN, APPLOCK, HISTORY) + an empty state (`home_quick_actions_empty`); prototype has 4 static tiles and no empty state. |
| **B-04** | block | S2 | FIX | `MoreCard` icon geometry (A-07). |
| **B-05** | block | S2 | FIX | Shield inner gap (A-06). |
| **B-06** | block | S2 | FIX | Static stats `454` / `3` + static `mgCardSub` (A-12/A-13). Prototype "Blocking help" toast exists in production (`blk_help_toast`) — verified equal. |
| **B-07** | profile | S2 | FIX | **Delete account is a Material3 `AlertDialog` in production** (`ProfileScreen.kt:70-95`, `DeleteDialog`), a **bottom sheet** in the prototype (`sheetDelete`, `index.html:940`). Prototype must present a dialog. |
| **B-08** | profile | S2 | FIX | `permCardSub` is static `0 of 3 granted · finish setup`; production formats the live granted count (`prof_permissions_sub`, recomputed on resume via `resumeTick`). Keep the fresh-install value but compute it. |
| **B-09** | profile | S3 | FIX | Version/footer strings hardcode `v0.1.0 · open://protectyourself`; production injects `prof_about_version`/`prof_footer_1` with `versionName`. |
| **B-10** | socialblocking | S1 | ADD | Footnote card, disabled tab pill, extras rows, master-off dimming (A-10). Prototype shows 5 static rows with no dim state; production computes `checked = enabled && pkg in wholeBlocked` and renders `alpha(0.45f)` for disabled rows. |
| **B-11** | socialblocking | S3 | FIX | Prototype `Select All (0)` / `Deselect All (0)` are static; production shows `selected.size` on **both** buttons (`SocialBlockingScreen.kt` picker) — quirk, see D-3. |
| **B-12** | about | S2 | FIX | Version line (A-11). Rest of the screen matches (title, `Deep link`/`open://protectyourself`, Website, Privacy Policy rows verified present in `AboutScreen.kt:185-205`). |
| **B-13** | antitamper / selfprotect / servicepicker | S2 | STATE | Prototype rows are demo services with `com.example.*`; production enumerates real accessibility services and has a distinct empty/loading state. Needs the empty + loaded states; demo packages may stay only if labelled demo (D-1). |
| **B-14** | schedule / scheduleedit | S3 | STATE | Copy verified equal. Remaining work is state coverage: overnight windows (` (+1)`, "Ends next day"), disabled schedule card, exact-alarm banner, exclude-apps card. |
| **B-15** | vpn | S3 | STATE | Prototype `vpnPill` = `Active` + `VPN filtering is on`; production has several status strings (`vpn_notif_active`, `_hidden`, `_custom`, `vpn_sub_schedule_suffix`, `vpn_sub_system_prefix`). Enumerate `DnsVpnViewModel` states in Phase 4 and mirror each. |
| **B-16** | applock | S3 | STATE | Copy verified equal; states to cover: off / setup / confirm / mismatch / gate / lockout-with-countdown / biometric-unavailable. |
| **B-17** | blockscreen | S3 | STATE | Copy verified equal (`Closing in %1$ds.`); verify the live countdown + the 3–120 s dwell clamp in the prototype. |
| **B-18** | backup | S3 | STATE | Restore confirmation exists on both sides; verify sheet-vs-dialog presentation and the 7 `backup_err_*` error states. |
| **B-19** | keywords / titleblock / history / quickactions / otherfeatures / troubleshoot | S3 | — | Copy verified equal apart from demo data (`Google Play` row in titleblock, category chips). No structural drift found. |
| **B-20** | welcome / permissions | S3 | STATE | Production hardcodes 3 strings in `WelcomeScreen.kt` (hygiene, §10) but renders identically; permission gating logic verified equivalent (`perm_required_toast`, `perm_skipped_toast`). |
| **B-21** | global | S2 | DELETE | **`sheetDelay`** ("Are you sure? · Time Delay" + `startDelay()`/`cancelDelay()` countdown, `index.html:957`, `app.js:305-318`) is production UI that **does not exist**: no route, no view-model, no string. It is reachable only from the dev helper `testDelay()`, which no UI calls. |
| **B-22** | global | S3 | DELETE | Dead compat shims inside `#sc-socialblocking` (`socialHeroPill`, `socialMasterSub`, `socialWholeSub`, `socialHeroTag`, `socialHeroTitle`, `socialHeroSub`, `socialCountPill`, `socialWholeChips`, `socialYoutubePill`, … — 10 `display:none` spans with no renderer writing to them). |

---

## 5. Work breakdown

Nine phases. Each phase is independently committable, independently verifiable, and leaves the prototype *shippable* (no phase leaves a broken screen). Commit style follows the repo convention: `ref(prototype): …`.

### Phase 0 — Audit harness (no UI change)

| Task | Deliverable |
|---|---|
| **P-0.1** | `tools/prototype/verify_prototype.py` — static verifier (§6.1, 46 checks) |
| **P-0.2** | `tools/prototype/smoke_prototype.mjs` — jsdom runtime smoke (§6.2, 24 checks) |
| **P-0.3** | `tools/prototype/shots.mjs` — Playwright screenshot harness, 24 screens × {light, dark} + 15 sheets → `tools/prototype/shots/` (§6.3) |
| **P-0.4** | `tools/prototype/css_rules.py` — emits `tools/prototype/css_map.json` (selector → declarations), so every measurement comparison is scripted, not eyeballed |
| **P-0.5** | Commit `chore(prototype): add parity audit harness (verify + smoke + shots)` |

**Acceptance:** `verify` and `smoke` both run green against the *current* tree (they assert today's state, not the target state); the 24-screen screenshot set is captured and stored as the "before" baseline.

### Phase 1 — Foundations (shared components, once, for all screens)

Fixes **A-02 … A-08**, **A-10 (footnote/pill), A-11 … A-14** at component level. Prototype-side component classes (`.sw`, `.seg`, `.chip`, `.tabs`, `.field`, `.li`, `.card`, `.qa`, `.stat`, `.shield`, `.hero`, `.pill`, `.toast`, `.sheet`, `.btn`, `.empty`, `.day`, `.timep`, `.checkbox`, `.avatar`, `.ob-steps`, `.nav`) are verified one-by-one against Appendix A and corrected where the matrix says MISMATCH.

**Acceptance:** Appendix A shows 100 % MATCH or an explicit, documented exception; `verify` asserts every Appendix-A value.

### Phase 2 — Chrome & navigation

- Bottom nav geometry re-verified (10/10/66/22, glass `.86`, border `line@.9`, shadow 6/24, icon 23, label 10/600, dot 7×7 with 1.5 dp white ring).
- Screen header conventions: back chip 40 dp r14 with `-8 px` optical offset, title 20 sp on tab roots / 24 sp on pushed screens, subtitle 12 sp lh20.
- Transition timing (A-08).
- Phone chrome (`statusbar`, `notch`) stays; document it as harness-only in `docs/03`.

**Acceptance:** every route reachable from the toolbar, the hash router, and in-app links; back-stack behaviour identical to `navController.popBackStack()` (already true — assert it in `smoke`).

### Phase 3 — State machines

For every STATE row — the 11 per-screen ones (B-01/02/03/10/13/14/15/16/17/18/20) plus A-20 (greeting) — introduce **one demo-state object per screen** in `app.js` (e.g. `const demo = {home:{layersActive:8,layersTotal:10,attention:2,paused:false,a11y:'off'}, …}`) and a toolbar control (`<select>`/toggles in `.ptb`) that flips states so every variant is demonstrable without editing code.

**Acceptance:** `smoke` iterates every state and asserts the rendered copy equals the corresponding `strings.xml` value with the same placeholders substituted.

### Phase 4 — Screen-by-screen reconciliation (ADD / FIX / DELETE)

Order = measured drift, worst first:
`socialblocking` (B-10/11) → `profile` (B-07/08/09) → `home` (B-01/02/03) → `block` (B-04/05/06) → `antitamper`+`selfprotect`+`servicepicker` (B-13) → `vpn` (B-15) → `schedule`+`scheduleedit` (B-14) → `applock`+`blockscreen` (B-16/17 + A-16 copy set) → `backup` (B-18 + A-17 error states) → `keywords`/`titleblock`/`history`/`quickactions`/`otherfeatures`/`about`/`welcome`/`permissions` (B-12/19/20, A-18/A-19) → DELETE rows B-21/B-22.

One commit per screen: `ref(prototype): match <screen> to production (<drift ids>)`.

**Acceptance per screen:** all its drift ids closed; the screen's text set equals the union of its production `R.string` values + hardcoded literals; `verify` + `smoke` green.

### Phase 5 — Sheets & dialogs

15 sheets − 1 orphan = 14 to verify, plus the **new dialog** presentation for delete-account (B-07). For each: title 18/700, `s-sub` 13, grab 40×5 r4, radius 26 top, padding 10/20/26, button row gap 10, and the *exact* production field set (e.g. `sheetTime` stepper 40 dp circles + 30/800 value column + AM/PM seg max-w 200).

**Acceptance:** every sheet in `verify`'s sheet manifest opens from a real handler and closes back to its origin screen.

### Phase 6 — Icons, slot by slot

81 vectors ⇄ 150 inline SVGs. Build a mapping table (`icon → prototype occurrence(s) → kotlin symbol → viewport/stroke/cap/join/` `fill`), then diff: `viewBox 24`, `stroke-width 2` for stroked icons, `stroke-linecap/linejoin round`, and `fill` vs `stroke` per icon. Confirm slot sizes (19/20/22/23/24/28/44) and tints. Target: every slot's *glyph* is the same shape as production's vector; numeric path identity is required only where the shapes are visibly different.

**Acceptance:** mapping table complete; `verify` asserts slot size + stroke convention for every registered icon.

### Phase 7 — Demo data & copy policy

Resolve D-1: either (a) prototype boots to production's fresh-install state with a "Demo data" toolbar toggle, or (b) prototype keeps a populated demo state, made internally consistent (A-12/A-13) and *reachable* from a real production code path. Then: version strings (A-11), pluralisation, and the `Google Play`/`com.example.*` demo rows (B-13/B-19).

**Acceptance:** no string in the prototype that production cannot produce; no number in the prototype that no production code path can compute.

### Phase 8 — Verification & sign-off

- `verify` 46 assertions green, `smoke` 24 checks green, 0 console errors.
- Screenshot set regenerated; the 24×2 light/dark + 14 sheet images attached to SAD-319.
- **Device checklist** (§Appendix D) executed by you on a real device against the same list of values.
- Optional (your call, D-4): add `verify`+`smoke` as a CI job (`ci.yml`), since today CI has **no** prototype gate at all.

### Phase 9 — Docs & knowledge graph

- `docs/03-ui-design-system.md`: add a "Prototype parity" section — the authority model (§2), the px→dp contract, the harness-chrome exclusion, and the state-matrix procedure.
- `README.md`: one line under repository layout for `tools/prototype/`.
- Refresh `graphify-out/` per repo convention (`graphify update .`, built_at_commit bump) so the prototype entry points stay queryable — matching what `36826a6` did.

---

## 6. Tooling specification (the "no guessing" guarantee)

### 6.1 `verify_prototype.py` — static checks (46)

27 check ids, **46 assertions** (group E expands to 19). Every failure exits non-zero with a `file:line` report.

**A. Document integrity (6)** — 1 CSS brace balance · 2 every `class=` used in HTML exists in CSS · 3 every CSS class is used · 4 HTML tag balance · 5 no duplicate `id` · 6 no `display:none` span that no JS writes to (kills B-22-class drift).

**B. Wiring (6)** — 7 every `getElementById` target exists (136) · 8 every inline handler resolves to a function (265) · 9 no dead `nav()` target · 10 every sheet has ≥1 opener · 11 every opener's sheet exists · 12 `ORDER`/`SCREENS`/`NAV`⇄sections consistent (24).

**C. Screen-set parity (3)** — 13 prototype section set == production route set (parsed from `MainScreen.kt` + `OnboardingNavHost.kt`) · 14 bottom-nav tabs == `MainNavDestinations` (4) · 15 every route's back target exists.

**D. Tokens (4)** — 16 the 13 light tokens == `LightAppColors` · 17 the 13 dark tokens == `DarkAppColors` · 18 no hex literal in a screen section that isn't a token or a documented brand hex · 19 dark-theme overrides cover every component that hardcodes a light-only colour.

**E. Appendix-A component values (19 assertions)** — one per matrix row (switch, segmented, chip, tabs, field, list row, card, qa, stat, shield, hero, pill, toast, sheet, nav bar, buttons, empty, day/timep, checkbox/avatar/steps).

**F. Copy parity (4)** — 20 screen text sets ⊆ (production `R.string` values ∪ production hardcoded literals) · 21 formatting placeholders preserved exactly (`%1$d` order/count) · 22 no version/package literal that differs from `build.gradle.kts` · 23 no demo value that violates the state object.

**G. Regressions from this work (4)** — 24 each drift id in §4 has a named assertion · 25 deleted UI (B-21/B-22) is absent · 26 newly added UI (ADD rows) is present · 27 state matrix counts (3 hero titles, 3 pills, 3 tab-pill states, N vpn states…) match the production constants.

Implementation note (already prototyped during this audit, reuse it):

```python
# tools/prototype/verify_prototype.py  (skeleton)
import re, html, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
proto = ROOT/"Reference/prototype"
html_src = (proto/"index.html").read_text()
css_src  = (proto/"css/styles.css").read_text()
js_src   = (proto/"js/app.js").read_text()

def check(cond, msg):
    print(("PASS  " if cond else "FAIL  ") + msg); return cond

# 1 brace balance
check(css_src.count("{") == css_src.count("}"), "CSS braces balanced")

# 7 getElementById targets
ids = set(re.findall(r'id="([^"]+)"', html_src))
refs = set(re.findall(r"getElementById\(['\"]([^'\"]+)['\"]\)", js_src))
check(not (refs - ids), f"JS ids resolve (missing: {sorted(refs-ids)})")

# 13 screen-set parity — routes come from three places, all must be parsed:
#   a) literal routes          composable("home") / composable("keywords?type={type}&tab={tab}")
#   b) constant routes         composable(PERM_ROUTE_BATTERY)  -> PermissionSteps.kt consts
#   c) the "main" host route   renders the tab NavHost, it is not a screen
ui    = ROOT/"app/src/main/java/com/safeme/app/ui/screens"
perms = ui/"permissions"
consts = {}
for f in perms.glob("*.kt"):
    consts.update(dict(re.findall(r'const val (\w*PERM_ROUTE_\w+)\s*=\s*"([^"]+)"', f.read_text())))
literal = lambda p: {m.group(1) for m in
                     re.finditer(r'composable\(\s*(?:route\s*=\s*)?"([a-z]+)(?:\?[^"]*)?"', p.read_text())}
by_const = lambda p: {consts[c] for c in
                      re.findall(r'composable\((\w+)\)', re.sub(r'"(?:[^"\\]|\\.)*"', '""', p.read_text()))
                      if c in consts}
secs = set(re.findall(r'<section class="screen[^"]*" id="sc-([^"]+)"', html_src))
screens = (literal(ui/"main/MainScreen.kt")
           | literal(perms/"OnboardingNavHost.kt")
           | by_const(perms/"OnboardingNavHost.kt")) - {"main"}
check(secs == screens, f"screen set == routes (proto-only={sorted(secs-screens)}, prod-only={sorted(screens-secs)})")
```

> This exact snippet was executed against the clone while writing the plan: the three checks above return **PASS** with `n=24` on both sides.

### 6.2 `smoke_prototype.mjs` — runtime (24)

jsdom + the real `css/js/html`: 24 screens navigate; 14 sheets open/close; keyword add/edit/delete round-trip; overnight schedule round-trip (`23:30 → 00:30` ⇒ ` (+1)`, bare `HH:MM` in `data-start`, `All apps`, correct Edit repopulation); 3 permission pills; every Phase-3 state variant renders the expected string; the social picker's Select/Deselect counters follow production; **0 uncaught exceptions, 0 console errors**.

### 6.3 `shots.mjs` — visual harness

Playwright, `deviceScaleFactor: 2`, viewport exactly `392×852` content box, `prefers-color-scheme` forced per shot, `localStorage` seeded to a deterministic state. Output `tools/prototype/shots/<screen>__<theme>.png` + `shots/index.html` contact sheet. Because the sandbox can serve `file://` over HTTP (`python3 -m http.server`), the harness runs identically here and on your machine.

---

## 7. Prototype contract — what must not break

The prototype is a single-page harness whose JS surface is already consumed by 265 inline handlers and 136 id lookups. To guarantee "no influence on existing features", these are frozen:

1. **Function names invoked from markup** (`nav`, `back`, `toast`, `openSheet`, `closeSheets`, `grantPerm`, `skipPerm`, `permBack`, `togLaunch`, `applyPreset`, `kwGo`, `openSocialApps`, `pickImg`, `saveMsg`, `timeStep`, `filterApps`, `appsDone`, `saveDns`, `vpnAppsDone`, `socialAppsDone`, `pickAmpm`, …). Refactor internals freely; keep the entry points.
2. **Element ids referenced by JS** (136) and the `data-*` attributes used as state (`data-app`, `data-launch`, `data-grant`, `data-next`, `data-perm`, `data-img`, `data-start`, `data-end`, `data-nav`, `data-pt`).
3. **localStorage keys**: `safeme_theme`, `safeme_onboarded` (only these two exist today — grepped; do not add a third without documenting it).
4. **Router**: `SCREENS`/`ORDER`/`NAV` constants and `#/s/<screen>` hashes.
5. **`sheet*` id namespace** — production's presentation may change (dict → dialog), but ids stay stable so `smoke` and deep links keep working.

Verification of the contract itself is checks 7–12 in §6.1.

---

## 8. Regression safety & gates

| Gate | When | Proof |
|---|---|---|
| **G0** | Phase 0 | `verify` + `smoke` green on the *unchanged* tree (proves the harness measures, not invents) |
| **G-k** | after every phase | `verify` + `smoke` green; screenshot set regenerated; no new console errors |
| **G-copy** | Phases 3–7 | every visible string ⊆ production's producible strings |
| **G-final** | Phase 8 | 24×2 screenshots + your device checklist signed |
| **G-iso** | always | `git diff --stat` touches only `Reference/prototype/**`, `tools/prototype/**`, `docs/**`, `README.md`. **Any `.kt`/`.xml`/`.gradle` file in the diff is a defect** — assert this in CI/verify (`check(not changed_kotlin_files)`). |

Android-side isolation is structural, not hopeful: nothing under `app/` imports anything from `Reference/` (verified — the prototype is not in the Gradle source set), and CI builds/tests/signs the APK only.

---

## 9. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| **R1** Compose can't run here (no SDK, JDK 11) → "exactly" is unprovable by me | Sign-off gap | Numeric proof from source (§4/Appendix A) + your device pass (§Appendix D). Every claim is traceable to a file:line. |
| **R2** Silently "improving" production quirks in the prototype | Divergence returns | Quirks are collected in §11 (D-3) and applied only on your decision. |
| **R3** `.qa`/`.stat` class collisions when Blocking needs different geometry than Home (A-07) | Regressing Home | Scoped overrides (`#sc-block …`) — the pattern the file already uses (`#sc-block .stat`, `#sc-home .hero`). |
| **R4** Deleting `sheetDelay`/compat spans breaks an untouched handler | Dead button | `verify` checks 10/11 (every sheet has an opener and every opener's target exists) run *before* deletion; the only opener is the dev helper `testDelay()`. |
| **R5** Phase 3's state objects drift from production's real constants | Fake states | Constants are read from `ProtectionLayers.ALL_LAYERS` (10), `SocialBlockingPrefs` presets, `strings.xml` — never invented; `verify` check 27 asserts the counts. |
| **R6** Icons: 81 vectors, 150 SVGs — a slot-by-slot pass can balloon | Schedule | Phase 6 is scoped to *slot size + stroke convention + glyph identity*, with an explicit "numerically identical path required" exception list. |
| **R7** Prototype has no automated gate in CI today | Future re-drift | Phase 8 optional CI job (D-4). |
| **R8** Concurrent Kotlin work (e.g. the Social strings refactor in §10) changes production mid-flight | Stale plan | Re-run the audit scripts; the plan orders work worst-drift-first so a mid-flight change invalidates one screen, not the plan. |

---

## 10. Production-side debt found during the audit (**recorded, not fixed** — Kotlin is frozen)

Create separate issues; these are *not* part of SAD-319:

1. `SocialBlockingScreen.kt`: **0 `R.string` references**, 14+ hardcoded user-facing literals, while 48 unreferenced `social_*` keys sit in `strings.xml`; header uses `26.sp` + `(-0.5).sp` tracking and an inline `26/30/40/14 sp` geometry mix that no other screen uses.
2. `WelcomeScreen.kt` (3) and `QuickActionsEditScreen.kt` (3) hardcode strings.
3. Hard-coded `Color(0x…)` literals outside `Color.kt`: `socialblocking` (18), `blockscreen` (18), `history` (5), `applock` (4), `schedule` (3), `home` (3), `titleblock` (1) — the design system says these must come from `LocalAppColors`.
4. `SocialBlockingScreen` picker: both Select All and Deselect All labels render `selected.size`.
5. 49 unreferenced `strings.xml` keys (48 × `social_*` + `bs_toast_why`).

**Why they matter for SAD-319:** because production is the reference, they make several prototype values *ambiguous* (`26.sp` header in Social vs `24.sp` elsewhere; `40 dp` icon in Blocking vs `38 dp` in Home). The plan resolves these by **copying production per screen** (never by normalising), and flags each occurrence so a future Kotlin cleanup knows the prototype will move with it.

---

## 11. Decisions I need from you (with a recommendation each)

| ID | Question | Recommendation |
|---|---|---|
| **D-1** | Demo data: should the prototype boot to production's **fresh-install** state (0 blocked, 0/10 layers, empty feeds) or to a **populated** demo state (454 keywords, 38-day streak, 8/10 layers)? | **Populated, but internally consistent and switchable** — a "Demo data" toggle in the toolbar (fresh ⇄ populated). Keeps the prototype useful for design review while making fresh-install provable. |
| **D-2** | Screen transition: match production exactly (full-width slide, 250/200 ms) or keep the prototype's subtle 24 px slide (220/260 ms)? | **Match production** — the issue names interactions explicitly. |
| **D-3** | Production quirks that make the prototype *worse* if copied: toast has no `max-width`; select/deselect counters both show the selected count; empty-state text left-aligned | **Copy production** (parity is the goal), with a one-line comment marking each as an upstream quirk + the 5 debt issues in §10. Say the word and I'll instead keep the prototype's better behaviour and record the divergence. |
| **D-4** | Add `verify_prototype.py` + smoke to CI as a new job (CI currently has no prototype gate)? | **Yes, as a separate non-blocking job first** (report-only for one cycle), then make it required. |
| **D-5** | Icon pass depth: slot-size/stroke-convention/glyph-identity (recommended) or byte-exact path data for all 81 vectors? | **Glyph identity**, with byte-exact required only where shapes visibly differ. |

If you don't answer D-1…D-5, the plan proceeds with the recommendation, and each choice is marked in `verify` so it can be flipped in one place.

---

## Appendix A — Component conformance matrix (verified)

`Prototype` = CSS declaration + selector; `Production` = Compose symbol. All values normalised to dp/sp (1 px = 1 dp).

| Component | Prototype value | Production value | Status |
|---|---|---|---|
| Switch | `.sw` 52×31, r999, `#DDD5CC`; thumb 25, top/left 3, travel 21, shadow `0 2 6 rgba(0,0,0,.2)`; on = `brand` | `MasterSwitch` 52×31, `swOff`, thumb 25, `offset(3 + 21)`, `shadow(2.dp)` | **MATCH** |
| Segmented | `.seg` r12, padding 3, gap 3; button h38 r9 13/600 | `ManageCard` row r12/pad3/spacedBy3; `SegButton` h38 r9 13 SemiBold | **MATCH** |
| Chip | `.chip` h36, pad 0/14, r999, 13/600; `.on` brandSoft + brand border + brandDark | `KeywordManagerScreen` chips | verify P4 |
| Tabs / underline tabs | `.tabs` r14 pad4, btn h38 r10 13.5/600; `.tabs.u` h46, 3 px underline inset 14 % | `Seg`/`TabRow` in KeywordManager | verify P4 |
| Field | `.field` h48 r14 pad 0/14, svg 19, input 15, placeholder ink3 | `SafeMeTextField` usages | verify P4 |
| List row | `.li` pad 13/14 min-h60, ic 40 r13, `.t` 15/600, `.s` 12, chev 18 | `AboutScreen`/`ProfileScreen` list rows | verify P4 |
| Card | `.card` r20 pad16 border 1 `line`, shadow `0 1 2` + `0 1 3` | `cardShape(20)` | **MATCH** |
| QA tile | `.qa` r18 pad14, ic 38 r12 svg19 mb10, t 13.5/700, s 11.5 lh1.35 | Home `QuickActionCard` 38/12/19/10 ✔ · Blocking `MoreCard` 40/13/20/12 ✘ | **MISMATCH (A-07)** |
| Stat | `.stat` r16 pad12, n 20/800, l 10.5/600 ls .2; `#sc-block .stat` r18 pad14 | Home 16/12 ✔ · Blocking 18/14 ✔ | **MATCH** |
| Shield hero | grad 160° mist→soft, border `brand@.35`, r18 pad14, gap12, ic 44 r14 shadow 8/18 svg22, t 16/700 brandDark, rings 120@(−34,−34) & 170@(−58,42) `brand@.16` | `ShieldCard` 18/.35/14, 44/14/shadow 8, 22, 16/Bold, rings 120/170 same offsets | **MISMATCH only on inner gap (A-06)** |
| Hero card | `#sc-home .hero` r20 + inset 1 ring; pad22; grad `brandDark 0 → brand .6 → #E8A07E 1`; tag 11/700 ls.8; h2 serif 27 lh1.15 600; p 13 `.92`; rings 150@(−30,−30) & 170@(−58,42) `#fff@.18`; row gap14 mt14 | `HeroCard` r20, pad22, same 3-stop gradient, 11/700/0.8, serif 27/31 SemiBold, 13 `white .92`, rings 150/170 | **MISMATCH: ring arc colour (A-02)** |
| Ring | `.ring` 64 (56 inline), `.bg #EADFD6`, `.fg brand`, stroke-linecap round, svg r26 sw6 | `HeroRing` 56, track `#EADFD6`, arc **white**, sw 6×(56/64), r 26×(56/64) | as A-02 |
| Pill | `.pill` pad 5/12 gap6 r999 12/700, dot 7; `.g/.o/.r/.b` bg+fg pairs | `Pill` 5/12, 6, dot 7, 12 Bold | **MATCH** |
| Toast | `.toast` bg `#3A332C`, pad 12/18, r999, 13/600, `max-width:88%`; stack `bottom:90px` | `ToastPill` pad 18/12, r999, `toastBg`, 13 SemiBold; anchored 24 dp | **MISMATCH (A-03)** |
| Sheet | r26 top, pad 10/20/26, `max-height:88%`, grab 40×5 r4 mb16, h3 18/700 mb4, `s-sub` 13 mb16, `row2` gap10 | `ModalBottomSheet` r26, pad 20/26, grab 40×5, h3 18 Bold | verify P5 |
| Bottom nav | `.nav` inset l/r 10 b 10, h66, r22, `rgba(255,255,255,.86)`, border `line@.9`, shadow 6/24, icon 23, label 10/600, dot 7 + 1.5 white ring | `BottomNavBar` 10/10/66/22, `surface@.86`, `line@.9`, shadow 6/24, 23, 10 SemiBold, dot 7/1.5 | **MATCH** |
| Buttons | `.btn` h52 pad 0/22 r999 16/600; `.sm` h38 pad 0/16 13.5 | Home "Review shield" 52/22/16 SemiBold | **MATCH** |
| Empty state | `.empty` pad 44/20, e-ic 64 r22 brandSoft/brand, t 16/700, s 13 lh1.5 | `EmptyState` in KeywordManager | verify P4 |
| Day chip / time picker | `.day` 42 r14 12/700; `.timep` r16 pad16 26/800 brandDark | `ScheduleEditScreen` 42/14, 16 r, 26 ExtraBold | verify P4 |
| Checkbox / avatar / steps | `.checkbox` 24 r8 border2; `.avatar` 44 17/700 grad brand→#E8A07E; `.ob-steps i` 7×7 → `.on` 20×7 brand | Social picker 24/8/2; Home avatar 44/17; permission steps 20 dp | **MATCH** |

---

## Appendix B — Copy inventory (what the numbers say)

- 622 `strings.xml` keys. 53 are not referenced from Kotlin, but 4 of those are consumed from `res/xml/accessibility_service_config.xml` (`accessibility_service_summary`/`_description`) and `AndroidManifest.xml` (`ps_fgs_subtype`/`vpn_fgs_subtype`), leaving **49 keys referenced nowhere: 48 × `social_*` + `bs_toast_why`**.
- Normalised comparison against every screen section: **~95 % of production copy is present verbatim in the prototype.**
- **Production-only copy** (prototype must add): the 3 hero titles, 3 hero subtitles, 3 pill texts, `home_quick_actions_empty`, `home_hero_resume`, `social_footnote`, live counts (`blk_manage_sub`, `prof_permissions_sub`), `%1$d launch` / `%1$d/3 tabs` / `%1$d active`.

### B.1 — The ≈66 genuine copy gaps, by screen (this is what Phase 4 closes)

| Screen | Missing / paraphrased production copy | Drift id |
|---|---|---|
| home | `Partially protected`, `Protection paused`, 3 subtitles, `Attention`/`Paused` pill, morning/afternoon/evening greetings, `No quick actions yet — tap Edit to add some` | A-01, A-20, B-01, B-03 |
| blockscreen | `Closing in %1$ds.`, `Time before Close unlocks • 3-120s`, `Close-gate • dwell countdown`, `None • SAF persistable URI`, `%1$s • persistable`, why-fallback, `Shown on the block screen behind your message` | A-16, A-19 |
| backup | `Creating backup…`, `Restoring…`, 7 × `backup_err_*` | A-17 |
| socialblocking | `How it works — …`, `%1$d active`, extras subtitles | A-10 |
| profile | `%1$d of 3 granted …`, version-derived `v%1$s · …` strings | B-08, B-09 |
| block | `%1$s keywords · %2$s websites · %3$s trusted`, excluded-apps sheet subtitle | A-12, A-19 |
| titleblock | `Search rules…`, `e.g. Apps` | A-18 |
| permissions | `Granted ✓` | A-19 |
| vpn, keywords, schedule… | placeholders and `%1$s`-composed subtitles — verify per screen in Phase 4 | B-15 |
| *not* gaps | 53 notification/toast strings (no prototype surface) + 23 runtime-formatted strings | — |
- **Prototype-only copy** (must be deleted or wired): `Version 1.0.76 · package protect.yourself`, `454`, `38-day streak`, `8 of 10 layers active · 2 need attention` (as literals), `3 active` (as literal), the 5 "Are you sure? · Time Delay" strings.
- **Verified equal** (spot-checked verbatim): keywords, titleblock, history, quickactions, otherfeatures, permissions, schedule, applock, blockscreen, backup, antitamper, selfprotect.

---

## Appendix C — File map for the implementation

| Purpose | Path |
|---|---|
| Screens (24 sections, 15 sheets) | `Reference/prototype/index.html` |
| Tokens, component classes, dark overrides | `Reference/prototype/css/styles.css` — `:root` **1**, `.screen` transition **56**, `.nav` **65**, `.hero` **88**, `.btn` **99**, `.card` **124**, `.sec-t` **131**, `.list` **133**, `.li` **134-150**, `.sw` **154**, `.chip` **163**, `.tabs` **166**, `.field` **175**, `.stats` **185**, `#sc-block .stat` **187**, `.stat` **188**, `.ring` **194-196**, `.grid2` **207**, `.qa` **208-212**, `.shield` **221-228**, `.card.feed` **236**, `.sheet` **247**, `.pill` **382**, `.empty` **386**, `.day` **393**, `.timep` **396**, `.seg` **399**, `.toasts` **346**, `.toast` **347**, dark block **452-470**, `.kw-row` **498**, `#sc-profile .qa .ic` **506** (the scoped-override precedent), `.feed-empty` **549** |
| Router, screens manifest, all behaviour | `Reference/prototype/js/app.js` (`NAV/SCREENS/ORDER` 2-11, `show/nav/back` 29-52, pickers 290-304, `testDelay/startDelay` 305-318, `renderSocial` + presets, permission gating) |
| Production reference — routes | `app/src/main/java/com/safeme/app/ui/screens/main/MainScreen.kt` |
| … onboarding routes | `.../permissions/OnboardingNavHost.kt` |
| … tokens | `.../ui/theme/Color.kt`, `Theme.kt`, `Type.kt` |
| … shared components | `.../ui/components/{BottomNavBar,ToastHost,GroupedAppPicker,SafeMeTextField,Effects}.kt` |
| … copy | `app/src/main/res/values/strings.xml` |
| … icons | `app/src/main/java/com/safeme/app/ui/screens/*/*Icons.kt` (13 files, 81 vectors) |
| … version facts | `app/build.gradle.kts:36-37` |
| Design-system doc to amend | `docs/03-ui-design-system.md` |
| New tooling (Phase 0) | `tools/prototype/{verify_prototype.py, smoke_prototype.mjs, shots.mjs, css_rules.py}` |

---

## Appendix D — Device sign-off checklist (you run this; I cannot)

Per screen: open it on a device with the production APK, then the prototype in Chrome (392 dp-wide viewport), and tick. Values to compare are the Appendix-A numbers.

- [ ] **home** — hero radius 20 + 1 dp ring; serif 27/31; ring 56 (white arc, `#EADFD6` track); pill green/amber per state; 3 hero titles + 3 subtitles; banner hidden while a11y unknown; 7 quick actions + empty state; stat cards r16/pad12
- [ ] **block** — shield r18, icon 44, inner gap 12, text 16/700 brandDark; stat cards r18/pad14; more-grid icons 40 r13 icon 20, gap 12; manage card segment r12 h38
- [ ] **schedule** — hero, card gaps 12, overnight ` (+1)`, "Ends next day", disabled card, exact-alarm banner
- [ ] **profile** — identity card, theme seg, 4 protection tiles (equal heights), troubleshoot card, support list, **delete = dialog**
- [ ] **socialblocking** — master card, 3 presets, launch rows + extras, tab pill (3 states), footnote card, master-off dimming
- [ ] **keywords / titleblock** — segments, search field, rows, per-tab empty states
- [ ] **vpn** — preset rows, custom DNS sheet, whitelist picker, status strings
- [ ] **antitamper / selfprotect / servicepicker** — cards, ADB command block, picker rows + empty state
- [ ] **applock** — setup/confirm/mismatch, gate, lockout countdown, biometric path
- [ ] **blockscreen** — preview, image grid, message chips, dwell 3–120 s, live countdown
- [ ] **backup** — export/restore, restore confirmation, 7 error strings
- [ ] **about / welcome / permissions** — version `0.1.0`/`com.safeme.app`, 3 permission steps + required gate
- [ ] **history / quickactions / otherfeatures / troubleshoot** — empty states, row heights
- [ ] **global** — switch 52×31, chips 36, sheet r26, toast 24 dp from bottom, nav 10/66/22 glass, transitions full-width 250 ms, dark theme parity

---

### One-paragraph summary

Production is already **very** close: colour tokens are 13/13 identical, the screen set is 24 = 24, and 76.8 % of production's copy appears verbatim (the rest is system-notification text, runtime-formatted strings, and ~66 enumerable gaps). What actually blocks "exactly" is a small, enumerable set of 42 drift rows: **one screen that never got reconciled** (Social Media Blocking — the prototype has no footnote card, no extras rows, no disabled tab-pill state), **one presentation mismatch** (delete-account dialog vs sheet), **one orphan sheet** (Time Delay) plus dead compat spans, **twelve state machines collapsed into single hardcoded states** (Home hero/pill/banner is the worst), and **eight numeric/copy drifts** (hero ring arc colour, toast anchor, feed empty + dot alignment, Blocking's 40 dp icon tile vs Home's 38 dp, shield inner gap, transition geometry, About's version line, and the block-screen/backup copy paraphrases). Phase 0 rebuilds the audit harness first so every subsequent change is measured rather than eyeballed, and Phase 8 hands you a device checklist for the one thing I cannot prove from here.
