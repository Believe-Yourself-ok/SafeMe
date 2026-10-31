package com.safeme.app.ui.screens.permissions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.safeme.app.ui.theme.SafeMeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PermissionPresentationTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun requiredNotificationStepDoesNotAddSkipAction() {
        rule.setContent {
            SafeMeTheme {
                PermissionScreen(
                    title = "Stay mindful",
                    subtitle = "Notification description",
                    step = 1,
                    granted = false,
                    visual = PermissionVisual.Notifications,
                    onBack = {},
                    grantLabel = "Allow notifications",
                    onGrant = {},
                )
            }
        }

        rule.onNodeWithText("Allow notifications").assertIsDisplayed()
        rule.onNodeWithText("Skip for now").assertDoesNotExist()
    }

    @Test
    fun optionalBatteryStepRetainsSkipAction() {
        var skipped = false
        rule.setContent {
            SafeMeTheme {
                PermissionScreen(
                    title = "Uninterrupted guard",
                    subtitle = "Battery description",
                    step = 2,
                    granted = false,
                    visual = PermissionVisual.Battery,
                    onBack = {},
                    grantLabel = "Disable optimization",
                    onGrant = {},
                    skipLabel = "Skip for now",
                    onSkip = { skipped = true },
                )
            }
        }

        rule.onNodeWithText("Skip for now").performClick()
        rule.runOnIdle { assertTrue(skipped) }
    }

    @Test
    fun boundariesScreenExposesApprovedAction() {
        var acknowledged = false
        rule.setContent {
            SafeMeTheme {
                BoundariesScreen(
                    onBack = {},
                    onContinue = { acknowledged = true },
                )
            }
        }

        rule.onNodeWithText("I understand").performClick()
        rule.runOnIdle { assertTrue(acknowledged) }
    }

    @Test
    fun activeScreenExposesApprovedAction() {
        var opened = false
        rule.setContent {
            SafeMeTheme {
                ActiveScreen(
                    onBack = {},
                    onOpen = { opened = true },
                )
            }
        }

        rule.onNodeWithText("Open SafeMe").performClick()
        rule.runOnIdle { assertTrue(opened) }
    }
}
