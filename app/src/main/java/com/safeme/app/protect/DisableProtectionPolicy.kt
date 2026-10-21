package com.safeme.app.protect

/**
 * COD-79 decision core.
 *
 * Pure and Android-free so the entire truth table is JVM-testable, following
 * the project convention (docs/11 §2): the decision lives here, the Android I/O
 * stays in the DataStore helpers and the ViewModels.
 *
 * ## The two protections are independent by construction
 *
 * Neither function takes both flags. [entryLockActive] cannot see
 * `disableProtectionEnabled` and [passwordRequired] cannot see
 * `entryLockEnabled`, so it is structurally impossible for one card to change
 * the other's behaviour — the property COD-79 asks for and the one the previous
 * design could not express (`lockType != OFF` meant "credential exists" and
 * "gate armed" at the same time).
 */
object DisableProtectionPolicy {

    /**
     * Card 1 — is the entry gate armed?
     *
     * Requiring [hasCredential] is a lockout guard, not a nicety: the unlock
     * overlay renders no input at all for `LockType.OFF`, so a gate armed
     * without a credential would put up a full-screen overlay with nothing to
     * type into and no way past it.
     */
    fun entryLockActive(entryLockEnabled: Boolean, hasCredential: Boolean): Boolean =
        entryLockEnabled && hasCredential

    /**
     * Card 2 — must [action] be preceded by credential verification?
     *
     * Enabling is never gated: [action] only ever describes turning something
     * OFF or deleting it, and callers must not invoke this for the enable
     * direction. There is deliberately no "enable" variant of [ProtectedAction]
     * so that mistake cannot be expressed.
     *
     * Fails open when no credential is stored, matching the project's fail-open
     * rule (docs/11 §3): a guard that cannot be satisfied must never trap the
     * user inside their own settings.
     */
    fun passwordRequired(
        disableProtectionEnabled: Boolean,
        hasCredential: Boolean,
        action: ProtectedAction,
    ): Boolean = disableProtectionEnabled && hasCredential && action.protected
}

/**
 * Every action that weakens a protection, i.e. every surface the COD-79 guard
 * covers. One value per gate site — the enum is the implementation checklist,
 * and `ProtectedActionTest` pins its size so adding a surface without wiring
 * it (or dropping one by accident) fails a test rather than passing silently.
 *
 * There is deliberately NO value for card 1 itself: turning the entry gate on
 * or off is never password-gated, because gating it would mean the user must
 * already be past a gate in order to configure the gate.
 */
enum class ProtectedAction(
    /** Stable id for logging and telemetry. */
    val id: String,
    /** Reserved so a non-gated surface can be listed without a second enum. */
    val protected: Boolean = true,
) {
    /** Master blocking switch — Home, Blocking tab. */
    MASTER_BLOCKING("master_blocking"),

    /** Anti-Tamper → Deactivate (in-app Device Admin revoke). */
    ANTI_TAMPER("anti_tamper"),

    /**
     * Anti-Tamper returning from the SYSTEM Device-Admin page with the admin no
     * longer active. Gated by explicit product decision; cancelling leaves the
     * PU flag ON so the accessibility guards keep blocking (fail toward
     * protection) while `adminActive` still reports the real state.
     */
    ANTI_TAMPER_ADMIN_SYNC("anti_tamper_admin_sync"),

    /** Accessibility Protection master toggle. */
    ACCESSIBILITY_PROTECTION("a11y_protection"),

    /** Social Media Blocking master toggle, incl. the "Relax" preset. */
    SOCIAL_BLOCKING_MASTER("social_master"),

    /** Social Media Blocking — allowing a previously blocked app again. */
    SOCIAL_BLOCKING_APP("social_app"),

    /** Social Media Blocking — allowing a tab (Shorts / Reels / Spotlight). */
    SOCIAL_BLOCKING_TAB("social_tab"),

    /** DNS & VPN filtering off. The enable/re-enable paths are not gated. */
    DNS_VPN("dns_vpn"),

    /** Image & video search blocking off. */
    IMAGE_VIDEO_SEARCH("image_video_search"),

    /** Turning one schedule off from the Schedule list. */
    SCHEDULE_ITEM("schedule_item"),

    /** Saving the schedule editor with an enabled schedule switched off. */
    SCHEDULE_EDITOR_OFF("schedule_editor_off"),

    /** Deleting a schedule outright — a strictly stronger form of switching off. */
    SCHEDULE_DELETE("schedule_delete"),

    /** Turning one title-block rule off. */
    TITLE_RULE("title_rule"),

    /** Deleting a title-block rule. */
    TITLE_RULE_DELETE("title_rule_delete"),

    /** Deleting a keyword from the blocklist (weakens blocking). */
    KEYWORD_DELETE("keyword_delete"),

    /** Deleting a website from the blocked list (weakens blocking). */
    WEBSITE_DELETE("website_delete"),

    /**
     * "Reset" on the Keyword Manager: wipes every user blocklist keyword,
     * website and title rule back to the bundled defaults in one tap. Gated
     * because it is the largest single weakening in the app — strictly more
     * destructive than the per-item deletes above it.
     */
    BLOCKLIST_RESET("blocklist_reset"),

    /**
     * Removing a quick action from the Home grid. Security-relevant, not
     * cosmetic: `QuickActionType.APPLOCK` is a route back into the lock
     * settings, so removing it would hide the guard's own configuration.
     */
    QUICK_ACTION_REMOVE("quick_action_remove"),

    /** Block Screen — turning off the "Why blocked" explanation. */
    BLOCK_SCREEN_WHY("block_screen_why"),

    /** Card 2's own switch, in the OFF direction. */
    DISABLE_PROTECTION_ITSELF("disable_protection"),
}
