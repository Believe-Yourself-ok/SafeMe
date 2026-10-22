package com.safeme.app.ui.screens.socialblocking

import com.safeme.app.data.SocialBlockingState

/**
 * Whether applying a Social Media Blocking [preset] would remove protection the
 * user currently has — and therefore whether COD-79 card 2 must prompt first.
 *
 * All three presets turn every tab on, so only the master flag and the whole-app
 * set can shrink:
 *
 *  - `"relax"` flips the master off **and** clears the whole-app list.
 *  - `"balanced"` keeps the master on but clears the whole-app list.
 *  - `"deep"` keeps the master on and turns every tab on, but *overwrites* the
 *    whole-app list with [SocialBlockingState.DEFAULT_WHOLE_BLOCKED] — which
 *    reduces it whenever the user hand-picked apps outside the default. This is
 *    the easy one to miss: the preset is advertised as the strongest, yet it can
 *    still take protection away.
 *
 * Applying a preset that only strengthens never prompts, which is what keeps the
 * guard from feeling like friction on the happy path.
 *
 * Top-level and pure rather than a private method on the ViewModel so the
 * branching is testable on the JVM — the project's rule for decision logic
 * (docs/11 §2). `else -> false` covers an unknown preset id: `applySocialPreset`
 * ignores those without writing, so there is nothing to guard.
 */
internal fun presetWeakens(
    preset: String,
    enabled: Boolean,
    wholeBlocked: Set<String>,
): Boolean = when (preset) {
    "relax" -> enabled || wholeBlocked.isNotEmpty()
    "balanced" -> wholeBlocked.isNotEmpty()
    "deep" -> (wholeBlocked - SocialBlockingState.DEFAULT_WHOLE_BLOCKED).isNotEmpty()
    else -> false
}
