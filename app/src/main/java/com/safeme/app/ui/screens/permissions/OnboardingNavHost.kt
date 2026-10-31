package com.safeme.app.ui.screens.permissions

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.safeme.app.R
import com.safeme.app.data.markBoundariesAcknowledged
import com.safeme.app.data.markOnboardingComplete
import com.safeme.app.data.onboardingComplete
import com.safeme.app.ui.screens.welcome.WelcomeScreen
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.findActivity
import kotlinx.coroutines.launch

@Composable
fun OnboardingNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // Resolve the hosting activity defensively; never crash on a null or
    // wrapped context (e.g. previews or unusual host configurations).
    val activity = (LocalActivity.current as? ComponentActivity)
        ?: context.findActivity() as? ComponentActivity
    val vm: OnboardingViewModel =
        if (activity != null) {
            viewModel(viewModelStoreOwner = activity)
        } else {
            viewModel()
        }
    val scope = rememberCoroutineScope()
    val onboardedState by context.onboardingComplete().collectAsState(initial = null)
    val onboarded = onboardedState
    val colors = LocalAppColors.current

    // If onboarding was never completed but all required permissions are
    // already granted, skip the permission screens entirely and go straight to
    // the main app (smoother first-run experience).
    val canSkip = remember(context) { requiredPermissionsGranted(context) }
    LaunchedEffect(onboarded, canSkip) {
        if (onboarded == false && canSkip) {
            context.markOnboardingComplete()
        }
    }

    if (onboarded == null) {
        Box(modifier = modifier.fillMaxSize().background(colors.background))
        return
    }

    val showMain = onboarded == true || (onboarded == false && canSkip)

    NavHost(
        navController = navController,
        startDestination = if (showMain) "main" else "welcome",
        modifier = modifier
    ) {
        composable("welcome") {
            WelcomeScreen(onGetStarted = { navController.navigate(PERM_ROUTE_NOTIFICATIONS) })
        }

        composable(PERM_ROUTE_NOTIFICATIONS) {
            NotificationPermissionStep(navController, vm)
        }

        composable(PERM_ROUTE_BATTERY) {
            BatteryPermissionStep(navController, vm)
        }

        composable(PERM_ROUTE_A11Y) {
            AccessibilityPermissionStep(
                navController = navController,
                vm = vm,
                onRequiredPermissionsGranted = {
                    navController.navigate(PERM_ROUTE_BOUNDARIES)
                },
            )
        }

        composable(PERM_ROUTE_BOUNDARIES) {
            BoundariesScreen(
                onBack = { navController.popBackStack() },
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
                onBack = { navController.popBackStack() },
                onOpen = {
                    scope.launch {
                        context.markOnboardingComplete()
                        navController.navigate("main") {
                            popUpTo(0) { inclusive = true }
                        }
                        Toast.makeText(
                            context,
                            R.string.perm_welcome_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        composable("main") {
            com.safeme.app.ui.screens.main.MainScreen()
        }
    }
}
