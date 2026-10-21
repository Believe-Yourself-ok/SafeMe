package com.safeme.app.ui.screens.backup

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.BackupCodec
import com.safeme.app.data.BackupError
import com.safeme.app.data.BackupFile
import com.safeme.app.data.BackupParseResult
import com.safeme.app.data.BackupSection
import com.safeme.app.data.BackupSnapshot
import com.safeme.app.data.RestoreResult
import com.safeme.app.data.backupStores
import com.safeme.app.data.createBackup
import com.safeme.app.data.executeRestore
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    fun toast(message: String) {
        _toasts.tryEmit(message)
    }

    /** Reads every store and produces the JSONC document + suggested file name. */
    suspend fun export(): BackupFile = getApplication<Application>().createBackup()

    /** Persists [jsonc] to the SAF uri picked by the user. */
    suspend fun writeTo(uri: Uri, jsonc: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(jsonc) }
                ?: throw IllegalStateException("No output stream for $uri")
        }.isSuccess
    }

    /** Reads the whole picked file as text; null when unreadable. */
    suspend fun readFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    /** Parses + validates a backup without touching any state. */
    suspend fun prepare(jsonc: String): BackupParseResult = BackupCodec.fromJsonc(jsonc)

    /** Atomically applies a validated backup (validates again, then rolls back on failure). */
    suspend fun restore(jsonc: String): RestoreResult =
        getApplication<Application>().run { executeRestore(jsonc, backupStores()) }
}

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = viewModel()) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<BackupFile?>(null) }
    var pendingRestoreJsonc by remember { mutableStateOf<String?>(null) }
    var pendingRestoreSnapshot by remember { mutableStateOf<BackupSnapshot?>(null) }

    fun toastRes(res: Int) = viewModel.toast(context.getString(res))

    // `application/octet-stream` (not `application/json`) so DocumentsUI keeps
    // the suggested `.jsonc` name instead of appending `.json` to it.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult // user cancelled
        scope.launch {
            busy = true
            val ok = viewModel.writeTo(uri, file.jsonc)
            busy = false
            toastRes(if (ok) R.string.backup_toast_exported else R.string.backup_toast_export_failed)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user cancelled
        scope.launch {
            busy = true
            val jsonc = viewModel.readFrom(uri)
            if (jsonc == null) {
                busy = false
                toastRes(R.string.backup_toast_import_failed)
                return@launch
            }
            when (val parsed = viewModel.prepare(jsonc)) {
                is BackupParseResult.Success -> {
                    pendingRestoreJsonc = jsonc
                    pendingRestoreSnapshot = parsed.snapshot
                }
                is BackupParseResult.Failure -> toastRes(errorRes(parsed.error))
            }
            busy = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BackupHeader(onBack)
            Text(
                text = stringResource(R.string.backup_screen_sub),
                fontSize = 13.sp,
                color = colors.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(18.dp))

            BackupActionCard(
                icon = BackupExportIcon,
                title = stringResource(R.string.backup_export_title),
                sub = stringResource(R.string.backup_export_sub),
                actionLabel = if (busy) stringResource(R.string.backup_exporting)
                    else stringResource(R.string.backup_export_action),
                primary = true,
                enabled = !busy,
                onClick = {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            val file = viewModel.export()
                            pendingExport = file
                            busy = false
                            exportLauncher.launch(file.suggestedName)
                        }
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            BackupActionCard(
                icon = BackupImportIcon,
                title = stringResource(R.string.backup_import_title),
                sub = stringResource(R.string.backup_import_sub),
                actionLabel = if (busy) stringResource(R.string.backup_restoring)
                    else stringResource(R.string.backup_import_action),
                primary = false,
                enabled = !busy,
                onClick = {
                    if (!busy) importLauncher.launch(arrayOf("*/*"))
                },
            )

            Spacer(Modifier.height(22.dp))
            GroupLabel(text = stringResource(R.string.backup_whats_included))
            BackupSection.entries.forEach { section ->
                IncludedRow(label = stringResource(sectionLabelRes(section)))
            }
            Spacer(Modifier.height(20.dp))
        }

        ToastHost(
            flow = viewModel.toasts,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }

    pendingRestoreSnapshot?.let { snapshot ->
        RestoreConfirmDialog(
            snapshot = snapshot,
            onConfirm = {
                val jsonc = pendingRestoreJsonc
                pendingRestoreJsonc = null
                pendingRestoreSnapshot = null
                scope.launch {
                    busy = true
                    val result = viewModel.restore(jsonc.orEmpty())
                    busy = false
                    when (result) {
                        is RestoreResult.Success -> toastRes(R.string.backup_toast_restored)
                        is RestoreResult.Failure -> toastRes(errorRes(result.error))
                    }
                }
            },
            onDismiss = {
                pendingRestoreJsonc = null
                pendingRestoreSnapshot = null
            },
        )
    }
}

@Composable
private fun BackupHeader(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Box(
            modifier = Modifier
                .offset(x = (-8).dp)
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ChevronIcon,
                contentDescription = stringResource(R.string.perm_back),
                modifier = Modifier.size(20.dp),
                tint = colors.ink,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_backup_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            color = colors.ink,
        )
    }
}

@Composable
private fun BackupActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    actionLabel: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = colors.brandDark,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    fontSize = 12.sp,
                    color = colors.ink2,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ActionPill(
            text = actionLabel,
            primary = primary,
            enabled = enabled,
            onClick = onClick,
        )
    }
}

@Composable
private fun ActionPill(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(
                if (primary) colors.brand else colors.surface,
            )
            .border(
                1.dp,
                if (primary) colors.brand else colors.line,
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else colors.ink2,
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    val colors = LocalAppColors.current
    Text(
        text = text.uppercase(),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        color = colors.ink3,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun IncludedRow(label: String) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.brand),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = colors.ink,
        )
    }
}

@Composable
private fun RestoreConfirmDialog(
    snapshot: BackupSnapshot,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = stringResource(R.string.backup_confirm_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_confirm_note),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = colors.ink2,
                )
                if (snapshot.createdAt.isNotBlank() || snapshot.appVersion.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.backup_confirm_meta,
                            snapshot.createdAt.ifBlank { "—" },
                            snapshot.appVersion.ifBlank { "—" },
                            snapshot.schemaVersion,
                        ),
                        fontSize = 12.sp,
                        color = colors.ink3,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.backup_confirm_restore),
                    color = colors.brandDark,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.backup_confirm_cancel),
                    color = colors.ink2,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

/** Design-language card: soft shadow + surface + rounded corners. */
@Composable
private fun Modifier.cardShape(radius: androidx.compose.ui.unit.Dp = 20.dp): Modifier {
    val colors = LocalAppColors.current
    return this
        .shadow(
            elevation = 1.dp,
            shape = RoundedCornerShape(radius),
            ambientColor = colors.ink.copy(alpha = 0.02f),
            spotColor = colors.ink.copy(alpha = 0.02f),
        )
        .clip(RoundedCornerShape(radius))
        .background(colors.surface)
}

private fun errorRes(error: BackupError): Int = when (error) {
    BackupError.NOT_JSON -> R.string.backup_err_not_json
    BackupError.NOT_SAFEME -> R.string.backup_err_not_safeme
    BackupError.UNSUPPORTED_VERSION -> R.string.backup_err_version
    BackupError.INVALID_STRUCTURE -> R.string.backup_err_structure
    BackupError.EMPTY -> R.string.backup_err_empty
    BackupError.WRITE_FAILED -> R.string.backup_err_write
    BackupError.ROLLBACK_FAILED -> R.string.backup_err_rollback
}

private fun sectionLabelRes(section: BackupSection): Int = when (section) {
    BackupSection.BLOCKING -> R.string.backup_section_blocking
    BackupSection.FOCUS_SESSIONS -> R.string.backup_section_schedules
    BackupSection.VPN -> R.string.backup_section_vpn
    BackupSection.QUICK_ACTIONS -> R.string.backup_section_quick_actions
    BackupSection.APP_LOCK -> R.string.backup_section_app_lock
    BackupSection.PREVENT_UNINSTALL -> R.string.backup_section_prevent_uninstall
    BackupSection.A11Y_PROTECTION -> R.string.backup_section_a11y
    BackupSection.CONTENT_ENGINE -> R.string.backup_section_content_engine
    BackupSection.BLOCK_SCREEN -> R.string.backup_section_block_screen
    BackupSection.SOCIAL_BLOCKING -> R.string.backup_section_social
}
