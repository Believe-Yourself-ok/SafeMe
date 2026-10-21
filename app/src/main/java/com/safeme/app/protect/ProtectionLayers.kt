package com.safeme.app.protect

/**
 * Result of evaluating how many of the app's protection layers are currently
 * active — drives the Home hero ring, title/subtitle and the "Review shield"
 * surface. Pure and unit-testable.
 */
data class ProtectionLayers(
    val active: Int,
    val total: Int,
    /** Stable ids of the layers that are off right now. */
    val attention: List<String>,
) {
    val progress: Float
        get() = if (total == 0) 0f else active.toFloat() / total
}

object ProtectionLayersEvaluator {

    const val LAYER_MASTER = "master"
    const val LAYER_ACCESSIBILITY = "accessibility"
    const val LAYER_VPN = "vpn"
    const val LAYER_APP_LOCK = "appLock"
    const val LAYER_A11Y_PROTECTION = "a11yProtection"
    const val LAYER_PREVENT_UNINSTALL = "preventUninstall"
    const val LAYER_FOCUS = "schedules"
    const val LAYER_CONTENT_RULES = "contentRules"
    const val LAYER_TITLE_RULES = "titleRules"
    const val LAYER_DEVICE_ADMIN = "deviceAdmin"

    val ALL_LAYERS = listOf(
        LAYER_MASTER,
        LAYER_ACCESSIBILITY,
        LAYER_VPN,
        LAYER_APP_LOCK,
        LAYER_A11Y_PROTECTION,
        LAYER_PREVENT_UNINSTALL,
        LAYER_FOCUS,
        LAYER_CONTENT_RULES,
        LAYER_TITLE_RULES,
        LAYER_DEVICE_ADMIN,
    )

    /**
     * Counts the active layers. A layer counts as active only when the
     * underlying mechanism is genuinely functional right now (e.g. the VPN
     * layer needs both the feature on AND the consent granted).
     */
    fun evaluate(
        masterBlocking: Boolean,
        accessibilityEnabled: Boolean,
        vpnEnabled: Boolean,
        appLockEnabled: Boolean,
        a11yProtectionEnabled: Boolean,
        preventUninstallEnabled: Boolean,
        hasEnabledFocusSession: Boolean,
        hasContentRules: Boolean,
        hasTitleRules: Boolean,
        deviceAdminActive: Boolean,
    ): ProtectionLayers {
        val active = buildSet {
            if (masterBlocking) add(LAYER_MASTER)
            if (accessibilityEnabled) add(LAYER_ACCESSIBILITY)
            if (vpnEnabled) add(LAYER_VPN)
            if (appLockEnabled) add(LAYER_APP_LOCK)
            if (a11yProtectionEnabled) add(LAYER_A11Y_PROTECTION)
            if (preventUninstallEnabled) add(LAYER_PREVENT_UNINSTALL)
            if (hasEnabledFocusSession) add(LAYER_FOCUS)
            if (hasContentRules) add(LAYER_CONTENT_RULES)
            if (hasTitleRules) add(LAYER_TITLE_RULES)
            if (deviceAdminActive) add(LAYER_DEVICE_ADMIN)
        }
        return ProtectionLayers(
            active = active.size,
            total = ALL_LAYERS.size,
            attention = ALL_LAYERS.filter { it !in active },
        )
    }
}
