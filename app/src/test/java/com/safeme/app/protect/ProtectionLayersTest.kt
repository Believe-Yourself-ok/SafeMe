package com.safeme.app.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Home hero's protection-layer evaluation. */
class ProtectionLayersTest {

    private fun evaluate(
        master: Boolean = false,
        a11y: Boolean = false,
        vpn: Boolean = false,
        appLock: Boolean = false,
        a11yProt: Boolean = false,
        pu: Boolean = false,
        schedules: Boolean = false,
        contentRules: Boolean = false,
        titleRules: Boolean = false,
        deviceAdmin: Boolean = false,
    ) = ProtectionLayersEvaluator.evaluate(
        masterBlocking = master,
        accessibilityEnabled = a11y,
        vpnEnabled = vpn,
        appLockEnabled = appLock,
        a11yProtectionEnabled = a11yProt,
        preventUninstallEnabled = pu,
        hasEnabledFocusSession = schedules,
        hasContentRules = contentRules,
        hasTitleRules = titleRules,
        deviceAdminActive = deviceAdmin,
    )

    @Test
    fun allOff_isZeroActiveWithFullAttention() {
        val layers = evaluate()
        assertEquals(0, layers.active)
        assertEquals(10, layers.total)
        assertEquals(10, layers.attention.size)
        assertEquals(0f, layers.progress)
    }

    @Test
    fun allOn_isFullyActive() {
        val layers = evaluate(
            master = true, a11y = true, vpn = true, appLock = true, a11yProt = true,
            pu = true, schedules = true, contentRules = true, titleRules = true,
            deviceAdmin = true,
        )
        assertEquals(10, layers.active)
        assertTrue(layers.attention.isEmpty())
        assertEquals(1f, layers.progress)
    }

    @Test
    fun masterOff_whenEverythingElseOn_onlyMasterNeedsAttention() {
        val layers = evaluate(
            master = false, a11y = true, vpn = true, appLock = true, a11yProt = true,
            pu = true, schedules = true, contentRules = true, titleRules = true,
            deviceAdmin = true,
        )
        assertEquals(9, layers.active)
        assertEquals(listOf(ProtectionLayersEvaluator.LAYER_MASTER), layers.attention)
    }

    @Test
    fun vpnLayer_requiresConsentSignal() {
        // The caller passes vpnEnabled=false when consent is missing, so the
        // layer must not count as active.
        val layers = evaluate(vpn = false)
        assertTrue(ProtectionLayersEvaluator.LAYER_VPN in layers.attention)
        val granted = evaluate(vpn = true)
        assertTrue(ProtectionLayersEvaluator.LAYER_VPN !in granted.attention)
    }

    @Test
    fun contentAndTitleRulesAreSeparateLayers() {
        val onlyContent = evaluate(contentRules = true)
        assertEquals(1, onlyContent.active)
        assertTrue(ProtectionLayersEvaluator.LAYER_CONTENT_RULES in onlyContent.activeLayers())
        val onlyTitle = evaluate(titleRules = true)
        assertEquals(1, onlyTitle.active)
        val both = evaluate(contentRules = true, titleRules = true)
        assertEquals(2, both.active)
    }

    private fun ProtectionLayers.activeLayers(): Set<String> =
        ProtectionLayersEvaluator.ALL_LAYERS
            .filter { it !in attention }
            .toSet()
}
