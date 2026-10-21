package com.safeme.app.data

import android.content.Context
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A ready-to-export backup: the JSONC document plus a sensible file name. */
data class BackupFile(
    val suggestedName: String,
    val jsonc: String,
)

/**
 * A typed read/write handle for one [BackupSection]. Real implementations wrap
 * a DataStore; tests substitute in-memory fakes to exercise the restore
 * pipeline (success, mid-write failure, rollback).
 */
interface BackupStateStore {
    /** Current persisted state for this section (never null once set). */
    suspend fun read(): Any?

    /** Replace this section's persisted state. [value] is null only when clearing. */
    suspend fun write(value: Any?)
}

/**
 * Gathers every supported section into a JSONC backup document. Pure data
 * work happens in [BackupCodec]; this only reads the current DataStore state.
 */
suspend fun Context.createBackup(): BackupFile {
    val version = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    val now = Instant.now()

    val snapshot = BackupSnapshot(
        blocking = blockingPrefs().first(),
        schedules = focusPrefs().first(),
        vpn = dnsVpnSettings().first(),
        quickActions = quickActionPrefs().first(),
        appLock = appLockPrefs().first(),
        preventUninstall = preventUninstallPrefs().first(),
        a11yProtection = a11yProtectionPrefs().first(),
        contentEngine = contentEnginePrefs().first(),
        blockScreen = blockScreenPrefs().first(),
        socialBlocking = socialBlockingPrefs().first(),
    )
    val jsonc = BackupCodec.toJsonc(snapshot, appVersion = version, createdAt = now.toString())
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    return BackupFile(suggestedName = "SafeMe-backup-$stamp.jsonc", jsonc = jsonc)
}

/**
 * Restores a JSONC backup with these guarantees:
 *
 *  1. The whole file is parsed and validated BEFORE any store is touched —
 *     invalid/corrupt/incompatible backups never reach a write.
 *  2. Current values of every affected section are snapshotted first.
 *  3. Sections are applied one store at a time; if any write fails, all
 *     already-written sections are rolled back to their snapshotted values.
 *  4. Sections absent from the backup are never touched.
 *
 * [stores] must contain every [BackupSection]; the production wiring comes
 * from [Context.backupStores].
 */
suspend fun executeRestore(
    jsonc: String,
    stores: Map<BackupSection, BackupStateStore>,
): RestoreResult {
    val parsed = BackupCodec.fromJsonc(jsonc)
    val snapshot = when (parsed) {
        is BackupParseResult.Success -> parsed.snapshot
        is BackupParseResult.Failure -> return RestoreResult.Failure(parsed.error)
    }
    val sections = snapshot.presentSections

    // Snapshot current state for rollback — before any write.
    val previous = HashMap<BackupSection, Any?>()
    try {
        for (section in sections) {
            previous[section] = stores[section]?.read()
        }
    } catch (e: Exception) {
        // Reading is idempotent; nothing was written yet.
        return RestoreResult.Failure(BackupError.WRITE_FAILED)
    }

    try {
        for (section in sections) {
            stores[section]?.write(snapshot.valueFor(section))
        }
    } catch (e: Exception) {
        var rolledBack = true
        for ((section, oldValue) in previous) {
            val ok = runCatching { stores[section]?.write(oldValue) }.isSuccess
            if (!ok) rolledBack = false
        }
        return RestoreResult.Failure(if (rolledBack) BackupError.WRITE_FAILED else BackupError.ROLLBACK_FAILED)
    }
    return RestoreResult.Success(sections)
}

/** Production wiring: every section backed by its DataStore. */
fun Context.backupStores(): Map<BackupSection, BackupStateStore> = mapOf(
    BackupSection.BLOCKING to BlockingStore(this),
    BackupSection.FOCUS_SESSIONS to FocusPrefsStore(this),
    BackupSection.VPN to VpnStore(this),
    BackupSection.QUICK_ACTIONS to QuickActionsStore(this),
    BackupSection.APP_LOCK to AppLockStore(this),
    BackupSection.PREVENT_UNINSTALL to PreventUninstallStore(this),
    BackupSection.A11Y_PROTECTION to A11yProtectionStore(this),
    BackupSection.CONTENT_ENGINE to ContentEngineStore(this),
    BackupSection.BLOCK_SCREEN to BlockScreenStore(this),
    BackupSection.SOCIAL_BLOCKING to SocialBlockingStore(this),
)

private class BlockingStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.blockingPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.writeBlockingPrefs(value as BlockingPrefsState)
    }
}

private class FocusPrefsStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.focusPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.writeFocusPrefs(value as FocusPrefsState)
    }
}

private class VpnStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.dnsVpnSettings().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.writeVpnSettings(value as DnsVpnSettings)
    }
}

private class QuickActionsStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.quickActionPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.setQuickActions(value as List<QuickActionType>)
    }
}

private class AppLockStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.appLockPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.writeAppLockPrefs(value as AppLockPrefsState)
    }
}

private class PreventUninstallStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.preventUninstallPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.setPreventUninstallEnabled((value as PreventUninstallPrefsState).preventUninstallEnabled)
    }
}

private class A11yProtectionStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.a11yProtectionPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.writeA11yProtectionPrefs(value as A11yProtectionPrefsState)
    }
}

private class ContentEngineStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.contentEnginePrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.setBlockImageVideoSearch((value as ContentEnginePrefsState).blockImageVideoSearch)
    }
}

private class BlockScreenStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.blockScreenPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.writeBlockScreenPrefs(value as BlockScreenPrefsState)
    }
}

private class SocialBlockingStore(private val context: Context) : BackupStateStore {
    override suspend fun read(): Any? = context.socialBlockingPrefs().first()
    override suspend fun write(value: Any?) {
        if (value != null) context.writeSocialBlockingPrefs(value as SocialBlockingState)
    }
}
