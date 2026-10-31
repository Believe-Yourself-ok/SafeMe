package com.safeme.app.ui.screens.permissions

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.safeme.app.R
import com.safeme.app.data.markBoundariesAcknowledged
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.util.isAccessibilityEnabled
import kotlinx.coroutines.launch

/**
 * Permission management flow opened from the Profile tab. Walks the same
 * notifications → battery → accessibility steps as first-run onboarding, but
 * seeded with the permissions already granted to the system so the user can
 * review what's granted, grant whatever is missing, and tap through.
 */
@Composable
fun ManagePermissionsFlow(onBack: () -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val vm: OnboardingViewModel = viewModel()
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()

    // Seed the flow with the permissions already granted to the system so
    // granted steps render as "Granted ✓" and can be tapped through.
    LaunchedEffect(Unit) {
        if (hasNotificationsPermission(context)) vm.markGranted(KEY_NOTIFICATIONS)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) vm.markGranted(KEY_BATTERY)
        if (isAccessibilityEnabled(context)) vm.markGranted(KEY_ACCESSIBILITY)
    }

    NavHost(
        navController = navController,
        startDestination = PERM_ROUTE_NOTIFICATIONS,
        modifier = Modifier.fillMaxSize().background(colors.background)
    ) {
        composable(PERM_ROUTE_NOTIFICATIONS) {
            NotificationPermissionStep(navController, vm, onBack = onBack)
        }
        composable(PERM_ROUTE_BATTERY) {
            BatteryPermissionStep(navController, vm, onBack = onBack)
        }
        composable(PERM_ROUTE_A11Y) {
            AccessibilityPermissionStep(
                navController = navController,
                vm = vm,
                onRequiredPermissionsGranted = {
                    navController.navigate(PERM_ROUTE_BOUNDARIES)
                },
                onBack = onBack,
            )
        }
        composable(PERM_ROUTE_BOUNDARIES) {
            BoundariesScreen(
                onBack = onBack,
                onContinue = {
                    scope.launch {
                        context.markBoundariesAcknowledged()
                        navController.navigate(PERM_ROUTE_ACTIVE)
                    }
                },
            )
        }
        composable(PERM_ROUTE_ACTIVE) {
            ActiveScreen(
                onBack = onBack,
                onOpen = {
                    Toast.makeText(
                        context,
                        R.string.perm_all_granted_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                    onBack()
                },
            )
        }
    }
}
