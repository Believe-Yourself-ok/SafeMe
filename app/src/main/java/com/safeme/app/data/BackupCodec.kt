package com.safeme.app.data

import com.safeme.app.vpn.DnsPreset
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Current backup schema version. Bump on any breaking change to the file format. */
const val BACKUP_SCHEMA_VERSION = 1

/** Fixed marker identifying a file as a SafeMe backup (see [BackupSnapshot]). */
const val BACKUP_FORMAT_ID = "safeme-backup"

/**
 * The logical configuration areas a backup can carry. Each maps 1:1 to a
 * persisted store (DataStore) and is restored/exported atomically as a unit.
 */
enum class BackupSection {
    BLOCKING,
    FOCUS_SESSIONS,
    VPN,
    QUICK_ACTIONS,
    APP_LOCK,
    PREVENT_UNINSTALL,
    A11Y_PROTECTION,
    CONTENT_ENGINE,
    BLOCK_SCREEN,
    SOCIAL_BLOCKING,
}

/**
 * Why a backup could not be restored. Each maps to a user-facing message in
 * the UI (see `backup_err_*` strings).
 */
enum class BackupError {
    /** File couldn't be parsed as JSON at all (not valid JSON/JSONC). */
    NOT_JSON,

    /** Valid JSON, but not a SafeMe backup (wrong `format` marker). */
    NOT_SAFEME,

    /** Backup was written by a newer, incompatible schema version. */
    UNSUPPORTED_VERSION,

    /** Valid header but required fields are missing or the wrong type. */
    INVALID_STRUCTURE,

    /** Valid header but no section carries any data. */
    EMPTY,

    /** A store write failed mid-restore (state was rolled back). */
    WRITE_FAILED,

    /** A store write failed and the rollback itself failed — state may be partial. */
    ROLLBACK_FAILED,
}

sealed class BackupParseResult {
    data class Success(val snapshot: BackupSnapshot) : BackupParseResult()
    data class Failure(val error: BackupError) : BackupParseResult()
}

sealed class RestoreResult {
    data class Success(val restoredSections: List<BackupSection>) : RestoreResult()
    data class Failure(val error: BackupError) : RestoreResult()
}

/**
 * A full snapshot of the app's user configuration. Sections are nullable:
 * `null` means "not part of this backup" and is never touched on restore, so
 * backups created by older schema versions (or hand-edited files) restore the
 * sections they actually contain.
 */
data class BackupSnapshot(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val appVersion: String = "",
    val createdAt: String = "",
    val blocking: BlockingPrefsState? = null,
    val schedules: FocusPrefsState? = null,
    val vpn: DnsVpnSettings? = null,
    val quickActions: List<QuickActionType>? = null,
    val appLock: AppLockPrefsState? = null,
    val preventUninstall: PreventUninstallPrefsState? = null,
    val a11yProtection: A11yProtectionPrefsState? = null,
    val contentEngine: ContentEnginePrefsState? = null,
    val blockScreen: BlockScreenPrefsState? = null,
    val socialBlocking: SocialBlockingState? = null,
) {
    /** The sections that carry data — in canonical order. */
    val presentSections: List<BackupSection> get() = buildList {
        if (blocking != null) add(BackupSection.BLOCKING)
        if (schedules != null) add(BackupSection.FOCUS_SESSIONS)
        if (vpn != null) add(BackupSection.VPN)
        if (quickActions != null) add(BackupSection.QUICK_ACTIONS)
        if (appLock != null) add(BackupSection.APP_LOCK)
        if (preventUninstall != null) add(BackupSection.PREVENT_UNINSTALL)
        if (a11yProtection != null) add(BackupSection.A11Y_PROTECTION)
        if (contentEngine != null) add(BackupSection.CONTENT_ENGINE)
        if (blockScreen != null) add(BackupSection.BLOCK_SCREEN)
        if (socialBlocking != null) add(BackupSection.SOCIAL_BLOCKING)
    }

    fun valueFor(section: BackupSection): Any? = when (section) {
        BackupSection.BLOCKING -> blocking
        BackupSection.FOCUS_SESSIONS -> schedules
        BackupSection.VPN -> vpn
        BackupSection.QUICK_ACTIONS -> quickActions
        BackupSection.APP_LOCK -> appLock
        BackupSection.PREVENT_UNINSTALL -> preventUninstall
        BackupSection.A11Y_PROTECTION -> a11yProtection
        BackupSection.CONTENT_ENGINE -> contentEngine
        BackupSection.BLOCK_SCREEN -> blockScreen
        BackupSection.SOCIAL_BLOCKING -> socialBlocking
    }
}

/**
 * Pure JSONC backup codec — the single source of truth for the file format.
 * `toJsonc` writes the only supported format; `fromJsonc` parses and validates
 * it. Both are Android-free and unit-tested.
 */
object BackupCodec {

    // ------------------------------------------------------------ encode

    /**
     * Serializes the snapshot as a human-readable JSONC document with a
     * self-describing header comment. [appVersion]/[createdAt] are baked in so
     * the file records which app version produced it and when.
     */
    fun toJsonc(
        snapshot: BackupSnapshot,
        appVersion: String,
        createdAt: String,
    ): String {
        val root = JSONObject()
        root.put("format", BACKUP_FORMAT_ID)
        root.put("schemaVersion", snapshot.schemaVersion)
        root.put("appVersion", appVersion)
        root.put("createdAt", createdAt)

        snapshot.blocking?.let { root.put("blocking", it.toJson()) }
        snapshot.schedules?.let { root.put("schedules", it.toJson()) }
        snapshot.vpn?.let { root.put("vpn", it.toJson()) }
        snapshot.quickActions?.let { root.put("quickActions", JSONArray(quickActionsToJson(it))) }
        snapshot.appLock?.let { root.put("appLock", it.toJson()) }
        snapshot.preventUninstall?.let { root.put("preventUninstall", it.toJson()) }
        snapshot.a11yProtection?.let { root.put("a11yProtection", it.toJson()) }
        snapshot.contentEngine?.let { root.put("contentEngine", it.toJson()) }
        snapshot.blockScreen?.let { root.put("blockScreen", it.toJson()) }
        snapshot.socialBlocking?.let { root.put("socialBlocking", it.toJson()) }

        val header = buildString {
            appendLine("// SafeMe backup — JSONC")
            appendLine("// Created $createdAt · app $appVersion · schema v${snapshot.schemaVersion}")
            appendLine("// Edit at your own risk; SafeMe validates the file before restoring.")
        }
        return header + root.toString(2)
    }

    // ------------------------------------------------------------ decode

    /**
     * Parses and validates a JSONC backup. Returns a typed [BackupSnapshot]
     * on success; any structural problem yields a specific [BackupError]
     * without ever touching app state.
     */
    fun fromJsonc(raw: String): BackupParseResult {
        val strict = try {
            Jsonc.toStrictJson(raw)
        } catch (e: Exception) {
            return BackupParseResult.Failure(BackupError.NOT_JSON)
        }
        val root = try {
            JSONObject(strict)
        } catch (e: JSONException) {
            return BackupParseResult.Failure(BackupError.NOT_JSON)
        }

        if (root.optString("format") != BACKUP_FORMAT_ID) {
            return BackupParseResult.Failure(BackupError.NOT_SAFEME)
        }
        val schemaVersion = when (val v = root.opt("schemaVersion")) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            else -> return BackupParseResult.Failure(BackupError.INVALID_STRUCTURE)
        }
        if (schemaVersion > BACKUP_SCHEMA_VERSION) {
            return BackupParseResult.Failure(BackupError.UNSUPPORTED_VERSION)
        }

        val snapshot = try {
            BackupSnapshot(
                schemaVersion = schemaVersion,
                appVersion = root.optString("appVersion", ""),
                createdAt = root.optString("createdAt", ""),
                blocking = root.parseSection("blocking") { it.parseBlocking() },
                schedules = root.parseSection("schedules") { it.parseFocusSessions() },
                vpn = root.parseSection("vpn") { it.parseVpn() },
                quickActions = root.parseArraySection("quickActions") { quickActionsFromJson(it.toString()) },
                appLock = root.parseSection("appLock") { it.parseAppLock() },
                preventUninstall = root.parseSection("preventUninstall") { it.parsePreventUninstall() },
                a11yProtection = root.parseSection("a11yProtection") { it.parseA11yProtection() },
                contentEngine = root.parseSection("contentEngine") { it.parseContentEngine() },
                blockScreen = root.parseSection("blockScreen") { it.parseBlockScreen() },
                socialBlocking = root.parseSection("socialBlocking") { it.parseSocialBlocking() },
            )
        } catch (e: InvalidBackupException) {
            return BackupParseResult.Failure(e.error)
        } catch (e: IllegalArgumentException) {
            // Strict section parsers throw IAE on malformed entries (see parseBlocking).
            return BackupParseResult.Failure(BackupError.INVALID_STRUCTURE)
        }

        if (snapshot.presentSections.isEmpty()) {
            return BackupParseResult.Failure(BackupError.EMPTY)
        }
        return BackupParseResult.Success(snapshot)
    }

    // ------------------------------------------------------------ helpers

    /**
     * Parses an optional top-level object section. Absent / explicit-null →
     * null; present-but-wrong-type → invalid structure (thrown and caught
     * above).
     */
    private inline fun <T> JSONObject.parseSection(key: String, parse: (JSONObject) -> T): T? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        if (value !is JSONObject) throw InvalidBackupException(BackupError.INVALID_STRUCTURE)
        return parse(value)
    }

    /** Same as [parseSection] but for array-typed sections (e.g. quickActions). */
    private inline fun <T> JSONObject.parseArraySection(key: String, parse: (JSONArray) -> T): T? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        if (value !is JSONArray) throw InvalidBackupException(BackupError.INVALID_STRUCTURE)
        return parse(value)
    }

    private fun BlockingPrefsState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("blocklistKeywords", JSONArray(keywordsToJson(blocklistKeywords)))
        o.put("whitelistKeywords", JSONArray(stringsToJson(whitelistKeywords)))
        o.put("blockedWebsites", JSONArray(websitesToJson(blockedWebsites)))
        o.put("trustedWebsites", JSONArray(stringsToJson(trustedWebsites)))
        o.put("titleBlockRules", JSONArray(titleRulesToJson(titleBlockRules)))
        o.put("blockingEnabled", blockingEnabled)
        o.put("excludedApps", JSONArray(excludedApps.toList()))
        return o
    }

    /**
     * Strict parsing: a malformed entry (non-object, missing/empty value, wrong
     * type) is a file error, not silently dropped — a backup that would lose
     * data on restore must be rejected instead.
     */
    private fun JSONObject.parseBlocking(): BlockingPrefsState = BlockingPrefsState(
        blocklistKeywords = keywordsFromJson(jsonArray("blocklistKeywords")?.toString(), strict = true),
        whitelistKeywords = stringsFromJson(jsonArray("whitelistKeywords")?.toString(), strict = true),
        blockedWebsites = websitesFromJson(jsonArray("blockedWebsites")?.toString(), strict = true),
        trustedWebsites = stringsFromJson(jsonArray("trustedWebsites")?.toString(), strict = true),
        titleBlockRules = titleRulesFromJson(jsonArray("titleBlockRules")?.toString(), strict = true),
        blockingEnabled = boolean("blockingEnabled", true),
        excludedApps = stringArray("excludedApps").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    )

    private fun FocusPrefsState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("schedules", JSONArray(focusSessionsToJson(schedules)))
        o.put("a11yWarningDismissed", a11yWarningDismissed)
        o.put("exactAlarmWarningDismissed", exactAlarmWarningDismissed)
        o.put("excludedApps", JSONArray(excludedApps.toList()))
        return o
    }

    private fun JSONObject.parseFocusSessions(): FocusPrefsState = FocusPrefsState(
        schedules = focusSessionsFromJson(jsonArray("schedules")?.toString()),
        a11yWarningDismissed = boolean("a11yWarningDismissed", false),
        exactAlarmWarningDismissed = boolean("exactAlarmWarningDismissed", false),
        excludedApps = buildSet {
            val a = optJSONArray("excludedApps")
            if (a != null) for (i in 0 until a.length()) {
                val p = a.optString(i).trim()
                if (p.isNotEmpty()) add(p)
            }
        },
    )

    private fun DnsVpnSettings.toJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("preset", preset.name)
        o.put("customV4", customV4)
        o.put("customV6", customV6)
        o.put("whitelist", JSONArray(whitelist.toList()))
        o.put("notifMode", notifMode)
        o.put("notifCustom", notifCustom)
        return o
    }

    private fun JSONObject.parseVpn(): DnsVpnSettings = DnsVpnSettings(
        enabled = boolean("enabled", false),
        preset = DnsPreset.fromName(string("preset")) ?: DnsPreset.CLOUDFLARE_FAMILY,
        customV4 = string("customV4") ?: "",
        customV6 = string("customV6") ?: "",
        whitelist = stringArray("whitelist").toSet(),
        notifMode = (string("notifMode") ?: NOTIF_DEFAULT).takeIf { it in VALID_NOTIF_MODES } ?: NOTIF_DEFAULT,
        notifCustom = string("notifCustom") ?: "",
    )

    private fun AppLockPrefsState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("lockType", lockType.storage)
        o.put("storedHash", storedHash)
        o.put("credentialLength", credentialLength)
        o.put("biometricEnabled", biometricEnabled)
        o.put("forgotPasswordDisabled", forgotPasswordDisabled)
        o.put("autoLock", autoLock.storage)
        return o
    }

    private fun JSONObject.parseAppLock(): AppLockPrefsState = AppLockPrefsState(
        lockType = LockType.fromStorage(string("lockType")),
        storedHash = string("storedHash") ?: "",
        credentialLength = int("credentialLength", 0),
        biometricEnabled = boolean("biometricEnabled", false),
        forgotPasswordDisabled = boolean("forgotPasswordDisabled", false),
        autoLock = AutoLockDelay.fromStorage(string("autoLock")),
    )

    private fun PreventUninstallPrefsState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", preventUninstallEnabled)
        return o
    }

    private fun JSONObject.parsePreventUninstall(): PreventUninstallPrefsState =
        PreventUninstallPrefsState(preventUninstallEnabled = boolean("enabled", false))

    private fun ContentEnginePrefsState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("blockImageVideoSearch", blockImageVideoSearch)
        return o
    }

    private fun JSONObject.parseContentEngine(): ContentEnginePrefsState =
        ContentEnginePrefsState(blockImageVideoSearch = boolean("blockImageVideoSearch", false))

    private fun A11yProtectionPrefsState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", protectionEnabled)
        o.put("protectedComponents", JSONArray(protectedComponents.toList()))
        return o
    }

    private fun BlockScreenPrefsState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("dwell", dwell)
        o.put("message", message)
        o.put("img", img)
        o.put("whyOn", whyOn)
        return o
    }

    private fun JSONObject.parseBlockScreen(): BlockScreenPrefsState = BlockScreenPrefsState(
        dwell = int("dwell", BLOCK_SCREEN_DEFAULT_DWELL)
            .coerceIn(BLOCK_SCREEN_MIN_DWELL, BLOCK_SCREEN_MAX_DWELL),
        message = string("message") ?: "",
        img = string("img") ?: "",
        whyOn = boolean("whyOn", true),
    )

    private fun JSONObject.parseA11yProtection(): A11yProtectionPrefsState =
        A11yProtectionPrefsState(
            protectionEnabled = boolean("enabled", false),
            protectedComponents = stringArray("protectedComponents").toSet(),
        )

    private fun SocialBlockingState.toJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("wholeBlocked", JSONArray(wholeBlocked.toList()))
        o.put("youtube", youtube)
        o.put("facebook", facebook)
        o.put("snapchat", snapchat)
        return o
    }

    private fun JSONObject.parseSocialBlocking(): SocialBlockingState = SocialBlockingState(
        enabled = boolean("enabled", true),
        wholeBlocked = stringArray("wholeBlocked").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        youtube = boolean("youtube", true),
        facebook = boolean("facebook", true),
        snapchat = boolean("snapchat", true),
    )

    // ------------------------------------------------------ strict accessors

    /** Field present as a JSON array, or null when absent. Wrong type → invalid. */
    private fun JSONObject.jsonArray(key: String): JSONArray? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        if (value !is JSONArray) throw InvalidBackupException(BackupError.INVALID_STRUCTURE)
        return value
    }

    /** Field present as a boolean, else [default]. Wrong type → invalid. */
    private fun JSONObject.boolean(key: String, default: Boolean): Boolean {
        if (!has(key) || isNull(key)) return default
        val value = opt(key)
        if (value !is Boolean) throw InvalidBackupException(BackupError.INVALID_STRUCTURE)
        return value
    }

    /** Field present as an int, else [default]. Wrong type → invalid. */
    private fun JSONObject.int(key: String, default: Int): Int {
        if (!has(key) || isNull(key)) return default
        val value = opt(key)
        if (value !is Number) throw InvalidBackupException(BackupError.INVALID_STRUCTURE)
        return value.toInt()
    }

    /** Field present as a string, else null. Wrong type → invalid. */
    private fun JSONObject.string(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        if (value !is String) throw InvalidBackupException(BackupError.INVALID_STRUCTURE)
        return value
    }

    /** String-array field; non-string entries are dropped (tolerant, like the app readers). */
    private fun JSONObject.stringArray(key: String): List<String> {
        val arr = jsonArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                if (v is String && v.isNotBlank()) add(v)
            }
        }
    }
}

/** Internal control-flow exception for malformed-but-JSON sections. */
private class InvalidBackupException(val error: BackupError) : Exception()
