package com.safeme.app.ui.screens.socialblocking

import com.safeme.app.data.SocialBlockingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COD-79: which Social Media Blocking presets must prompt before applying.
 *
 * The interesting cases are the ones where a preset *advertised as stronger*
 * still removes protection the user configured by hand.
 */
class SocialPresetPolicyTest {

    private val defaults = SocialBlockingState.DEFAULT_WHOLE_BLOCKED

    // ------------------------------------------------------------------ relax

    @Test
    fun relaxWeakensWheneverThereIsAnythingToLose() {
        assertTrue("master on ⇒ relax turns it off", presetWeakens("relax", enabled = true, wholeBlocked = emptySet()))
        assertTrue("apps blocked ⇒ relax clears them", presetWeakens("relax", enabled = false, wholeBlocked = defaults))
    }

    /** Nothing is on and nothing is blocked: relax changes nothing, so no prompt. */
    @Test
    fun relaxDoesNotPromptWhenAlreadyFullyRelaxed() {
        assertFalse(presetWeakens("relax", enabled = false, wholeBlocked = emptySet()))
    }

    // --------------------------------------------------------------- balanced

    @Test
    fun balancedWeakensOnlyWhenItClearsBlockedApps() {
        assertTrue(presetWeakens("balanced", enabled = true, wholeBlocked = defaults))
        assertFalse("master stays on and no apps to clear", presetWeakens("balanced", enabled = true, wholeBlocked = emptySet()))
        assertFalse(presetWeakens("balanced", enabled = false, wholeBlocked = emptySet()))
    }

    // ------------------------------------------------------------------- deep

    @Test
    fun deepDoesNotPromptWhenTheListIsAlreadyTheDefault() {
        assertFalse(presetWeakens("deep", enabled = true, wholeBlocked = defaults))
        assertFalse("deep also turns the master on", presetWeakens("deep", enabled = false, wholeBlocked = emptySet()))
    }

    /**
     * The subtle one: "Deep Work — all apps & tabs" reads as the strongest
     * preset, but it overwrites the whole-app list with the default, so any
     * hand-picked app outside that set would silently stop being blocked.
     */
    @Test
    fun deepWeakensWhenTheUserHandPickedAppsOutsideTheDefault() {
        val handPicked = defaults + "com.example.user.added"
        assertTrue(presetWeakens("deep", enabled = true, wholeBlocked = handPicked))
    }

    @Test
    fun deepDoesNotPromptForASubsetOfTheDefault() {
        // Losing nothing: every currently-blocked app is still in the default set
        // the preset writes, so coverage only grows.
        val subset = defaults.take(1).toSet()
        assertTrue(subset.all { it in defaults })
        assertFalse(presetWeakens("deep", enabled = true, wholeBlocked = subset))
    }

    // ---------------------------------------------------------------- unknown

    @Test
    fun unknownPresetNeverPrompts() {
        // applySocialPreset ignores an unrecognised id without writing anything.
        assertFalse(presetWeakens("turbo", enabled = true, wholeBlocked = defaults))
        assertFalse(presetWeakens("", enabled = true, wholeBlocked = defaults))
    }

    // ------------------------------------------------------------ exhaustive

    /** Every preset the UI can send is accounted for, and the rule is total. */
    @Test
    fun everyKnownPresetIsDecidableInBothStates() {
        for (preset in listOf("deep", "balanced", "relax")) {
            for (enabled in listOf(true, false)) {
                for (blocked in listOf(emptySet(), defaults, defaults + "com.extra")) {
                    // No exception, and a definite answer either way.
                    presetWeakens(preset, enabled, blocked)
                }
            }
        }
    }
}
