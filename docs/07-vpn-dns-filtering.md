# 07 — VPN / DNS filtering

`service/SafeMeVpnService` implements DNS filtering by advertising a
family-safe resolver through a `VpnService` tunnel. It never routes traffic
into the TUN and never inspects packets.

## 1. Architecture: DNS-delegated filtering

The tunnel is established with `VpnService.Builder`:

- `addAddress(TUN_ADDR_V4=10.0.0.2/32, TUN_ADDR_V6=fd00:10:0:0:2::2/128)`,
  `setMtu(1280)`.
- `addDnsServer(...)` for the active preset — Android's system resolver sends
  every app's DNS query there, and the provider performs the filtering.
- **No routes are added** in normal (DNS-filter) mode, so no traffic enters
  the TUN. The app is not a relay and does not evaluate a local blocklist.

### Presets (`vpn/VpnConfig.kt`)

| Preset | DNS servers (v4 / v6) |
|---|---|
| `CLOUDFLARE_FAMILY` (default) | `1.1.1.3` / `2606:4700:4700::1113` |
| `ADGUARD_FAMILY` | `94.140.14.15` / `2a10:50c0::ad1:ff` |
| `CUSTOM` | user-supplied v4/v6 (validated by `VpnValidation`); blank → family-safe default so a tunnel with no resolver never silently disables filtering |

### Known limitation (accepted tradeoff)

Because no traffic flows through the TUN, the app cannot block encrypted DNS
(DoH/DoT/DoQ). Apps with their own resolver (Chrome Secure DNS, hardcoded
DNS) can bypass filtering — the same limitation the reference project
accepts. On-screen URL/keyword blocking is the accessibility service's job.

### System-wide layer: Private DNS (`vpn/PrivateDnsFilter.kt`)

The tunnel can serve exactly one master: DNS-filter mode cannot black-hole a
schedule-targeted app, and per-app-block mode excludes everyone else from the
VPN — their DNS goes to the router and the presets silently stop applying
(the "DNS presets not working with a 24/7 schedule" report).

When filtering is enabled, the app therefore ALSO points the system resolver
at the preset's family DoT hostname via Private DNS strict mode (requires the
WRITE_SECURE_SETTINGS grant the anti-tamper flow already sets up):

| Preset | Private DNS specifier |
|---|---|
| `CLOUDFLARE_FAMILY` | `family.cloudflare-dns.com` |
| `ADGUARD_FAMILY` | `family.adguard-dns.com` |
| `CUSTOM` | none — DoT needs a hostname with a valid TLS identity; custom stays VPN-only |

This makes filtering independent of the tunnel: schedule blocking keeps the
per-app tunnel while every other app resolves through the family resolver.
The user's original Private DNS settings are backed up in `vpn_prefs` and
restored on disable / CUSTOM switch; `VpnBootReceiver` re-asserts the
specifier on boot / package-replace (and clears it when filtering is
persisted off). Strict mode is fail-closed — if the DoT hostname is
unreachable, resolution fails rather than leaking unfiltered.

## 2. Tunnel modes

### DNS-FILTER (normal)

- SafeMe itself and whitelisted apps bypass via `addDisallowedApplication`.
- `allowBypass()` lets apps that explicitly request it skip the VPN (some
  system services require this).

### PER-APP-BLOCK (schedule-driven)

Active when `scheduledBlockAll || scheduledBlockApps.isNotEmpty()` (managed
by `FocusEngine` via `applyFocusBlocks`):

- Routes are added (`0.0.0.0/0`, `::/0`) and only the targeted apps'
  traffic enters the TUN; since nothing is ever forwarded from the TUN, that
  traffic is black-holed — targeted apps get no internet while everything
  else keeps working.
- `scheduledBlockAll` → `addDisallowedApplication(SafeMe)` + whitelist;
  everything else is routed and blocked.
- Otherwise → `addAllowedApplication` for each targeted app only.
- `allowBypass()` must **not** be called in this mode — it would let targeted
  apps circumvent the block.

## 3. Lifecycle

- `START_STICKY`, foreground service with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
  (API 34+), low-importance channel, content text from the notification-mode
  setting (`Default` / `Hide` / `Custom`), ongoing, with a "Stop" action.
- Actions: `START`, `STOP` (user off, but re-arms if a schedule block is
  active), `STOP_PERSIST` (also flips the persisted flag), `UPDATE_NOTIF`,
  `RESTART`.
- All commands run on a single-threaded command executor; teardown on
  `onDestroy` closes the fd (`@Volatile` fd so a concurrent teardown never
  misses a freshly assigned tunnel and leaves an orphan).
- `onRevoke` (user/system turned the VPN off in Settings or the shade):
  stops the tunnel and clears the persisted enabled flag — the UI reflects
  reality via `VpnStatusStore`.

## 4. Watchdog (`vpn/TunnelRestartPolicy.kt`)

A healthy DNS-only tunnel never delivers data, so a blocking `Os.read` on the
fd simply waits until the system closes the tunnel (network handover, another
VPN, airplane mode). That return is the only signal that filtering silently
stopped:

- `onRead(bytesRead, errno)` classifies: `>0` bytes or errno `EAGAIN`/`EINTR`
  → keep waiting (50 ms backoff); anything else → tunnel dead.
- On death, the current watchdog re-establishes the tunnel — **unless** the
  last successful establish is younger than `TUNNEL_RESTART_COOLDOWN_MS =
  5 s` (`shouldStopInsteadOfRestart`), in which case it stops instead of
  looping (anti-storm).
- Stale watchdog threads can never act: identity check
  (`watchdogThread !== me`) prevents a replaced watchdog from tearing down a
  fresh tunnel.
- Policy logic is pure and unit-tested (`TunnelRestartPolicyTest`).

## 5. Schedule integration

`SafeMeVpnService.applyFocusBlocks(packages, blockAll, context)`:

- Updates the volatile `scheduledBlockApps` / `scheduledBlockAll`.
- Tunnel running → restart it in the mode matching the new set.
- Tunnel not running but a block is active and `VpnService.prepare() == null`
  (consent was granted before) → start it silently.
- Nothing active → leave as-is.

The tunnel runs while the VPN feature is on **or** a schedule is
internet-blocking — scheduled blocks keep enforcement even when the cosmetic
switch is off (mirrors the reference).

## 6. Boot re-arm & status

- `VpnBootReceiver` — on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, reads the
  persisted `enabled` flag and calls `startForegroundService` **synchronously
  inside `onReceive`** (deferring to a coroutine throws
  `ForegroundServiceStartNotAllowedException` on Android 12+).
- `VpnStatusStore` — process-wide `StateFlow<Boolean>` of "tunnel is up",
  updated on start/stop/revoke, so the UI shows the real network state even
  when the user disables the VPN from the system shade.

## 7. UI (`ui/screens/vpn/`)

`DnsVpnScreen` + `DnsVpnSheets` + `DnsVpnViewModel`:

- Master switch (with the system VPN consent prompt on first enable),
  preset picker (Cloudflare / AdGuard / Custom), custom DNS fields with
  strict `VpnValidation` IPv4/IPv6 checks, app whitelist via the shared
  grouped picker, and notification-mode settings.
- The ViewModel reacts to `dnsVpnSettings()` and `VpnStatusStore.active`,
  restarting the tunnel on preset/whitelist changes (`ACTION_RESTART`).
