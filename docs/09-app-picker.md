# 09 — App Picker

Every screen that selects apps (schedule targets, VPN whitelist) shares one
discovery + categorization + presentation pipeline.

## 1. Discovery (`data/AppCatalog.kt`)

`AppCatalog.load(context)`:

- Queries `PackageManager` for `ACTION_MAIN` + `CATEGORY_LAUNCHER` activities
  (enabled via the manifest `<queries>` on Android 11+).
- Dedupes by package, excludes SafeMe itself, resolves the launcher label.
- Classifies each app (see below).
- Returns `List<InstalledApp>(packageName, label, category)`.

Nothing is hard-coded about *which* apps appear — discovery is always
dynamic; only the classification rules for apps Android leaves unclassified
are static.

## 2. Category taxonomy

`AppCategory` in fixed display order (the prototype's canonical list):

1. `SOCIAL`
2. `VIDEO_MUSIC`
3. `MESSAGING`
4. `SHOPPING` (e-commerce, marketplaces, order/delivery)
5. `PAYMENT` (banking, wallets, money apps)
6. `GAMES`
7. `NEWS_PROD` (browsers, mail, docs, maps, tools — the prototype's
   catch-all)
8. `OTHER` (fallback)

### Classification precedence (`categorize`)

1. **Package-name prefix rules** — the most specific (longest) matching
   prefix wins. These mirror the prototype taxonomy exactly: e.g. WhatsApp /
   Messages are `MESSAGING` even though Play reports a coarse `SOCIAL`
   category. 300+ curated rules across package prefixes and labels (e.g.
   `com.facebook.orca` → MESSAGING before `com.facebook` → SOCIAL; game
   studios `com.supercell`, `com.king.`, …; `com.amazon.mshop` →
   SHOPPING while `com.amazon.avod` → VIDEO_MUSIC; payment apps →
   PAYMENT).
2. **Android's built-in `ApplicationInfo.category`** — the fallback that
   catches the long tail (`CATEGORY_GAME` → GAMES, audio/video/image →
   VIDEO_MUSIC, social → SOCIAL, news/maps/productivity/accessibility →
   NEWS_PROD).
3. **Label keyword rules** — for obfuscated packages ("clash of clans" →
   GAMES, "netflix" → VIDEO_MUSIC, "bank"/"wallet"/"upi" → PAYMENT, …).
4. **`OTHER`**.

Rules are sorted longest-first so the most specific match always wins. The
pure `categorize` function is unit-tested (`AppCatalogTest`) without
Android.

## 3. Grouping & search

`AppCatalog.groupApps(apps, query)`:

- Groups by `CATEGORY_ORDER`, apps alphabetical within each group, empty
  groups dropped.
- A search query filters inside each group by label **or** package name
  (empty groups hidden) — the prototype's search-then-group behavior.

## 4. Presentation (`ui/components/GroupedAppPicker.kt`)

`GroupedAppPickerList(groups, selected, onToggle, row)`:

- A `LazyColumn` where each category renders as an uppercase 11.5 sp/800
  label (`AppCategoryHeader`) above its own rounded bordered card of rows
  (20 dp radius, 1 dp `line` border, surface fill), 16 dp between groups.
- Row rendering is **injected** via the `row` slot lambda so each picker
  (schedule apps vs VPN whitelist) keeps its own design language — only
  grouping/presentation is shared.
- Selection is a `Set<String>` of package names toggled via `onToggle`.
- **Bulk actions:** both multi-select sheets (`AppPickerSheet`,
  `VpnAppsSheet`) show a **Select All / Deselect All** row between the
  search field and the list. The actions call ViewModel batch methods
  (`selectAllApps`/`deselectAllApps`, `selectAllWhitelistApps`/
  `deselectAllWhitelistApps`) that select/deselect **every app in the
  picker** with one state update; the VPN variant persists the whitelist
  and restarts the tunnel exactly once, never per app.
- **Search tap-target:** every picker search field is fully tappable —
  the `BasicTextField` fills the remaining width and the whole field row
  (icon + padding included) forwards taps to a `FocusRequester`, so
  tapping anywhere in the field focuses it, not just the text extent.

## 5. Consumers

| Screen | What it picks | Store |
|---|---|---|
| `FocusEditScreen` | `appPackages` per schedule (empty ⇒ blocks all) | `schedule_prefs` |
| `DnsVpnScreen` whitelist | `whitelist` set (bypasses DNS filtering) | `vpn_prefs` |

Both load apps off the main thread (slow PackageManager work) and reuse the
same `load` → `groupApps` → `GroupedAppPickerList` pipeline.
