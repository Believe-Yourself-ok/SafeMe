package com.safeme.app.ui.screens.main

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.safeme.app.R
import com.safeme.app.ui.components.BottomNavBar
import com.safeme.app.ui.screens.antitamper.AccessibilityProtectionScreen
import com.safeme.app.ui.screens.antitamper.AntiTamperScreen
import com.safeme.app.ui.screens.antitamper.ServicePickerScreen
import com.safeme.app.ui.screens.applock.AppLockScreen
import com.safeme.app.ui.screens.backup.BackupScreen
import com.safeme.app.ui.screens.blocking.BlockingScreen
import com.safeme.app.ui.screens.blocking.OtherFeaturesScreen
import com.safeme.app.ui.screens.blockscreen.BlockScreen
import com.safeme.app.ui.screens.history.HistoryScreen
import com.safeme.app.ui.screens.home.HomeScreen
import com.safeme.app.ui.screens.home.QuickActionsEditScreen
import com.safeme.app.ui.screens.keywords.KeywordManagerScreen
import com.safeme.app.ui.screens.permissions.ManagePermissionsFlow
import com.safeme.app.ui.screens.profile.AboutScreen
import com.safeme.app.ui.screens.profile.ProfileScreen
import com.safeme.app.ui.screens.focus.FocusEditScreen
import com.safeme.app.ui.screens.focus.FocusScreen
import com.safeme.app.ui.screens.socialblocking.SocialBlockingScreen
import com.safeme.app.ui.screens.titleblock.TitleBlockScreen
import com.safeme.app.ui.screens.vpn.DnsVpnScreen
import com.safeme.app.ui.theme.LocalAppColors

private val MainTabRoutes = setOf("home", "block", "focus", "profile")

@Composable
fun MainScreen() {
    val colors = LocalAppColors.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in MainTabRoutes
    val context = LocalContext.current

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = navigateToTab,
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            // Every screen applies its own statusBarsPadding(), so consuming
            // the Scaffold's top inset here too would double-pad and push all
            // content down by a full status-bar height (leaving a dead strip at
            // the top and pushing the bottom of each screen off-screen). Only
            // the bottom padding (bottom bar / gesture inset) comes from the
            // Scaffold.
            modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()),
            enterTransition = { slideInHorizontally(animationSpec = tween(250), initialOffsetX = { it }) + fadeIn(animationSpec = tween(250)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
            popEnterTransition = { slideInHorizontally(animationSpec = tween(250), initialOffsetX = { -it }) + fadeIn(animationSpec = tween(250)) },
            popExitTransition = { slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)) },
        ) {
            composable("home") {
                HomeScreen(
                    onReviewShield = { navigateToTab("block") },
                    onAddKeyword = { navController.navigate("keywords") },
                    onNewFocusSession = { navController.navigate("focusedit") },
                    onBackup = { navController.navigate("backup") },
                    onHistory = { navController.navigate("history") },
                    onOpenWebsites = {
                        navController.navigate("keywords?type=websites&tab=blocked")
                    },
                    onOpenVpn = { navController.navigate("vpn") },
                    onOpenAppLock = { navController.navigate("applock") },
                    onEditQuickActions = { navController.navigate("quickactions") },
                    onOpenAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
            composable("block") {
                BlockingScreen(
                    onOpenBlockScreen = { navController.navigate("blockscreen") },
                    onOpenVpn = { navController.navigate("vpn") },
                    onOpenAntiTamper = { navController.navigate("antitamper") },
                    onOpenKeywords = {
                        navController.navigate("keywords?type=keywords&tab=blocklist")
                    },
                    onOpenWebsites = {
                        navController.navigate("keywords?type=websites&tab=blocked")
                    },
                    onOpenTitleBlock = { navController.navigate("titleblock") },
                    onOpenOtherFeatures = { navController.navigate("otherfeatures") },
                    onOpenSocialBlocking = { navController.navigate("socialblocking") },
                )
            }
            composable("blockscreen") {
                BlockScreen(onBack = { navController.popBackStack() })
            }
            composable("vpn") {
                DnsVpnScreen(onBack = { navController.popBackStack() })
            }
            composable("antitamper") {
                AntiTamperScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAccessibilityProtection = { navController.navigate("selfprotect") },
                )
            }
            composable("selfprotect") {
                AccessibilityProtectionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenServicePicker = { navController.navigate("servicepicker") },
                )
            }
            composable("servicepicker") {
                ServicePickerScreen(onBack = { navController.popBackStack() })
            }
            composable("focus") {
                FocusScreen(
                    onNewFocusSession = { navController.navigate("focusedit") },
                    onEditFocusSession = { id -> navController.navigate("focusedit?editId=$id") }
                )
            }
            composable("profile") {
                ProfileScreen(
                    onOpen = { target ->
                        when (target) {
                            "permissions" -> navController.navigate("permissions")
                            "backup" -> navController.navigate("backup")
                            "troubleshoot" -> navController.navigate("troubleshoot")
                            "about" -> navController.navigate("about")
                            "applock" -> navController.navigate("applock")
                        }
                    }
                )
            }
            composable("applock") {
                AppLockScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "keywords?type={type}&tab={tab}",
                arguments = listOf(
                    navArgument("type") { defaultValue = "keywords" },
                    navArgument("tab") { defaultValue = "blocklist" },
                ),
            ) { entry ->
                KeywordManagerScreen(
                    initialType = entry.arguments?.getString("type") ?: "keywords",
                    initialTab = entry.arguments?.getString("tab") ?: "blocklist",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "focusedit?editId={editId}",
                arguments = listOf(navArgument("editId") { nullable = true }),
            ) { entry ->
                FocusEditScreen(
                    editId = entry.arguments?.getString("editId"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("backup") {
                BackupScreen(onBack = { navController.popBackStack() })
            }
            composable("history") {
                HistoryScreen(onBack = { navController.popBackStack() })
            }
            composable("quickactions") {
                QuickActionsEditScreen(onBack = { navController.popBackStack() })
            }
            composable("permissions") {
                ManagePermissionsFlow(onBack = { navController.popBackStack() })
            }
            composable("troubleshoot") { PlaceholderScreen(R.string.prof_troubleshoot_title) }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable("titleblock") {
                TitleBlockScreen(onBack = { navController.popBackStack() })
            }
            composable("otherfeatures") {
                OtherFeaturesScreen(onBack = { navController.popBackStack() })
            }
            composable("socialblocking") {
                SocialBlockingScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(titleRes: Int) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(titleRes),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink,
        )
    }
}
