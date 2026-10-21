package com.safeme.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.ui.theme.LocalAppColors

/**
 * COD-81: shown when an editor holding unsaved changes is being exited.
 * Three actions: keep editing (stay), save & exit, discard & exit. Scrim taps
 * and system back keep the user editing — same as Keep Editing. Styling
 * follows the app's confirmation-dialog conventions (RestoreConfirmDialog /
 * DeleteDialog): surface container, 18 sp bold title, 14 sp body, SemiBold
 * TextButton labels colored by role.
 */
@Composable
fun UnsavedChangesDialog(
    onKeepEditing: () -> Unit,
    onSaveAndExit: () -> Unit,
    onDiscardChanges: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onKeepEditing,
        containerColor = colors.surface,
        title = {
            Text(
                text = stringResource(R.string.unsaved_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.unsaved_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.ink2,
            )
        },
        confirmButton = {
            // AlertDialog only has two button slots, so the three actions are
            // a stacked trio (standard multi-action dialog layout).
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onKeepEditing, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.unsaved_keep),
                        color = colors.brandDark,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(onClick = onSaveAndExit, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.unsaved_save_exit),
                        color = colors.ink,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(onClick = onDiscardChanges, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.unsaved_discard),
                        color = colors.danger,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    )
}
