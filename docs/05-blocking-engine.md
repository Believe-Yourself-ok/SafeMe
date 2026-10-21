# 05 — Blocking engine (accessibility service)

`service/SafeMeAccessibilityService` is the **only** on-screen content
blocking engine. It reacts to window changes, matches visible text against
the merged rule sets, and raises the full-screen block gate.

## 1. Event pipeline

```
AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
  └─ handleEvent(event)
      ├─ ignore null / non-window events / SafeMe's own package
      ├─ [Prevent Uninstall guards]  (see 04 — Security architecture)
      ├─ [Schedule launch block]     (see 06 — Schedule blocking)
      ├─ cached BlockingPrefsState (from DataStore flow)
      ├─ if !blockingEnabled → return
      ├─ collectTexts(event)        (bounded node walk)
      ├─ findMatch(texts, state)    (keywords + websites)
      │    └─ findTitleMatchIfSettings (title rules, Settings windows only)
      ├─ 4 s cooldown on (package|match.value)
      └─ launchGate → BlockGateActivity
```

Every step is inside try/catch — a malformed event or node tree can never
crash the service.

## 2. Rule sources

The engine merges three sources per category:

| Category | User data (DataStore) | Bundled |
|---|---|---|
| Keywords | `blocklist_keywords_json` → `List<BlockedKeyword>` | `BundledKeywords.keywords` (adult, category ADULT) |
| Websites | `blocked_websites_json` → `List<BlockedWebsite>` | `BundledKeywords.websites` |
| Whitelist | `whitelist_keywords_json`, `trusted_websites_json` | — |
| Title rules | `title_block_rules_json` → `List<TitleBlockRule>` | — |

## 3. Matching semantics

### Text collection (`collectTexts`)

- Event text (`event.text`, the window title) plus a bounded node-tree walk:
  max depth 12, max 200 collected strings, both `text` and
  `contentDescription` captured, deduped.
- Nodes obtained from the active window are **recycled** on API 26–32
  (required to avoid exhausting the framework node pool); no-op on API 33+.

### Keyword matching (`findMatch`)

- Case-insensitive substring against all collected text.
- **Whitelist wins**: if any whitelist keyword appears, matching stops and
  nothing is blocked.
- User keywords first, then bundled adult keywords.

### Website matching (`findMatch`)

- Candidate strings are normalized via `normalizeDomain` (strip scheme, path,
  query, trailing dot, lowercase).
- A blocked site matches when a host equals `d` or ends with `.$d`
  (domain-suffix match at label boundaries, so `bad.example.com` matches but
  `notbad.example.com` does not).
- Trusted websites override blocked websites the same way the keyword
  whitelist overrides keywords.

### Title rules (`findTitleMatch`)

- Matched against the **window title only** (`event.getText` on
  window-state-changed events; on API 33+ the window's own `title` attribute
  is the fallback when the event carries no text) — never body text, so a
  rule like "Apps" cannot fire because a Settings list shows an "Apps" row.
- Modes: `CONTAINS`, `EXACT`, `STARTS_WITH`.
- **Settings only**: title rules are evaluated only when the foreground
  package is in `ProtectedSystemPages.isSettingsPackage` (AOSP + OEM list).
  A one-time log warning is emitted if a Settings-looking package is seen but
  isn't allowlisted and title rules are configured (an OEM fork symptom).
- Whitelist keywords suppress title rules too, keeping the escape hatch
  consistent across all match paths.

## 4. Block gate (`BlockGateActivity`)

On a match, the service launches `BlockGateActivity` with
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` and extras
(package, matched value, block type: `keyword` / `website` / `title` /
`schedule` / `pu`).

- Renders the self-contained `BlockOverlay` (dwell countdown, "why blocked"
  toggle, "Close" gated until the dwell elapses).
- **blockedToday counter**: incremented once per gate creation
  (`savedInstanceState == null` only — never re-incremented on rotation or
  recreation). Date-keyed (`blocked_date`, device zone): resets to 0 on a new
  local day — lazily on read, atomically on increment, and on screen resume.
- **Activity feed**: one `block` entry per gate with a human title
  ("Website blocked", "Keyword blocked", "Settings page blocked",
  "Blocked <label>", "Uninstall blocked").
- The engine skips SafeMe's own package, so the gate itself never re-triggers
  a block.

### Cooldowns & dedup

- `COOLDOWN_MS = 4 s` per `package|match` key prevents re-launching the gate
  for the same window in a loop.
- Prevent-Uninstall blocks use a separate key/cooldown so they never suppress
  keyword blocks.
- Schedule blocks use their own key and `SCHEDULE_COOLDOWN_MS = 4 s`.

## 5. Robustness guarantees

- Every event is processed inside try/catch.
- DataStore failures degrade to a safe default state (`BlockingPrefsState()`
  — empty lists, blocking enabled).
- The engine is self-contained: no network, no user-data collection, no
  storage of window contents beyond the in-memory match.

## 6. Relationship to other features

- **Schedules**: the service also enforces launch blocking via
  `FocusEngine.isLaunchBlocked` and a "block everything" exemption list
  (`SCHEDULE_SYSTEM_EXEMPT` — launcher, Settings, package installer,
  permission controllers, phone/telecom, DocumentsUI, media) so the user is
  never locked out. See [06](06-schedule-blocking.md).
- **Prevent Uninstall**: PU guards run before the keyword engine and are
  independent of the master blocking switch. See
  [04](04-security-architecture.md).
- **VPN**: on-screen URL/keyword blocking for browsers is this service's job;
  the VPN only filters DNS. The two complement, they do not overlap.
