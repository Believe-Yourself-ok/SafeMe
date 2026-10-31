package com.safeme.app.ui.screens.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.safeme.app.R
import com.safeme.app.ui.util.isAccessibilityEnabled

const val PERM_ROUTE_NOTIFICATIONS = "permissions"
const val PERM_ROUTE_BATTERY = "permbattery"
const val PERM_ROUTE_A11Y = "perma11y"
const val PERM_ROUTE_BOUNDARIES = "permboundaries"
const val PERM_ROUTE_ACTIVE = "permactive"

const val KEY_NOTIFICATIONS = "notifications"
const val KEY_BATTERY = "battery"
const val KEY_ACCESSIBILITY = "accessibility"

fun hasNotificationsPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33) {
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    return true
}

/**
 * Returns true when every permission required for SafeMe to function is
 * already granted to the system: notifications (API 33+) and the SafeMe
 * accessibility service. Battery optimization is optional and not required.
 */
fun requiredPermissionsGranted(context: Context): Boolean =
    hasNotificationsPermission(context) && isAccessibilityEnabled(context)

/**
 * Step 1 of the permission flow — notifications (required, API 33+).
 */
@Composable
fun NotificationPermissionStep(
    navController: NavHostController,
    vm: OnboardingViewModel,
    onBack: () -> Unit = { navController.popBackStack() },
) {
    val context = LocalContext.current
    val granted by vm.granted.collectAsState()
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            vm.markGranted(KEY_NOTIFICATIONS)
            navController.navigate(PERM_ROUTE_BATTERY)
        } else {
            Toast.makeText(
                context, R.string.perm_required_toast, Toast.LENGTH_SHORT
            ).show()
        }
    }
    PermissionScreen(
        title = context.getString(R.string.perm_notifications_title),
        subtitle = context.getString(R.string.perm_notifications_sub),
        step = 1,
        granted = KEY_NOTIFICATIONS in granted,
        visual = PermissionVisual.Notifications,
        onBack = onBack,
        grantLabel = context.getString(R.string.perm_notifications_action),
        onGrant = {
            if (KEY_NOTIFICATIONS in granted) {
                navController.navigate(PERM_ROUTE_BATTERY)
            } else if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                vm.markGranted(KEY_NOTIFICATIONS)
                navController.navigate(PERM_ROUTE_BATTERY)
            }
        }
    )
}

/**
 * Step 2 of the permission flow — battery optimization (optional).
 */
@Composable
fun BatteryPermissionStep(
    navController: NavHostController,
    vm: OnboardingViewModel,
    onBack: () -> Unit = { navController.popBackStack() },
) {
    val context = LocalContext.current
    val granted by vm.granted.collectAsState()
    var pendingReturn by rememberSaveable { mutableStateOf(false) }

    fun continueToA11y() {
        vm.markGranted(KEY_BATTERY)
        navController.navigate(PERM_ROUTE_A11Y)
    }

    fun grant() {
        pendingReturn = true
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    OnResumeEffect {
        if (pendingReturn) {
            pendingReturn = false
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                continueToA11y()
            }
        }
    }

    PermissionScreen(
        title = context.getString(R.string.perm_battery_title),
        subtitle = context.getString(R.string.perm_battery_sub),
        step = 2,
        granted = KEY_BATTERY in granted,
        visual = PermissionVisual.Battery,
        onBack = onBack,
        grantLabel = context.getString(R.string.perm_battery_action),
        onGrant = {
            if (KEY_BATTERY in granted) navController.navigate(PERM_ROUTE_A11Y) else grant()
        },
        skipLabel = context.getString(R.string.perm_skip),
        onSkip = {
            Toast.makeText(
                context, R.string.perm_skipped_toast, Toast.LENGTH_SHORT
            ).show()
            navController.navigate(PERM_ROUTE_A11Y)
        }
    )
}

/**
 * Step 3 of the permission flow — accessibility (required). Calls
 * [onRequiredPermissionsGranted] once notifications AND accessibility are
 * both granted. The caller owns the two informational steps that follow.
 */
@Composable
fun AccessibilityPermissionStep(
    navController: NavHostController,
    vm: OnboardingViewModel,
    onRequiredPermissionsGranted: () -> Unit,
    onBack: () -> Unit = { navController.popBackStack() },
) {
    val context = LocalContext.current
    val granted by vm.granted.collectAsState()
    var pendingReturn by rememberSaveable { mutableStateOf(false) }

    fun finish() {
        vm.markGranted(KEY_ACCESSIBILITY)
        if (KEY_NOTIFICATIONS in vm.granted.value && KEY_ACCESSIBILITY in vm.granted.value) {
            onRequiredPermissionsGranted()
        } else {
            Toast.makeText(
                context, R.string.perm_required_toast, Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun grant() {
        if (isAccessibilityEnabled(context)) {
            finish()
        } else {
            pendingReturn = true
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    OnResumeEffect {
        if (pendingReturn) {
            pendingReturn = false
            if (isAccessibilityEnabled(context)) {
                finish()
            } else {
                Toast.makeText(
                    context, R.string.perm_required_toast, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    PermissionScreen(
        title = context.getString(R.string.perm_a11y_title),
        subtitle = context.getString(R.string.perm_a11y_sub),
        step = 3,
        granted = KEY_ACCESSIBILITY in granted,
        visual = PermissionVisual.Accessibility,
        onBack = onBack,
        grantLabel = context.getString(R.string.perm_a11y_action),
        onGrant = ::grant,
    )
}

@Composable
fun OnResumeEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
