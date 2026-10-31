package com.safeme.app.ui.screens.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.ui.components.blurredShadow
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.UiFamily

/** Visual treatment for the three existing Android permission mechanisms. */
enum class PermissionVisual {
    Notifications,
    Battery,
    Accessibility,
}

/**
 * Prototype-matched presentation for a production permission step.
 *
 * All permission requests, Settings intents, lifecycle checks and navigation
 * callbacks remain owned by [PermissionSteps.kt]. This composable only renders
 * the supplied state and invokes the supplied actions.
 */
@Composable
fun PermissionScreen(
    title: String,
    subtitle: String,
    step: Int,
    totalSteps: Int = 5,
    granted: Boolean,
    visual: PermissionVisual,
    onBack: () -> Unit,
    grantLabel: String,
    onGrant: () -> Unit,
    skipLabel: String? = null,
    onSkip: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .padding(start = 32.dp, top = 12.dp, end = 32.dp, bottom = 28.dp),
        ) {
            OnboardingBackButton(onBack)
            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.perm_step,
                        step,
                        totalSteps,
                    ),
                    fontFamily = UiFamily,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                    color = colors.brand,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    fontFamily = UiFamily,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.75).sp,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = subtitle,
                    fontFamily = UiFamily,
                    fontSize = 14.5.sp,
                    lineHeight = 23.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = colors.ink2,
                )
                Spacer(modifier = Modifier.height(if (visual == PermissionVisual.Accessibility) 16.dp else 24.dp))

                when (visual) {
                    PermissionVisual.Notifications -> NotificationPreview()
                    PermissionVisual.Battery -> BatteryPreview()
                    PermissionVisual.Accessibility -> PrivacyCallout()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            OnboardingPrimaryButton(
                label = if (granted) {
                    androidx.compose.ui.res.stringResource(R.string.perm_granted)
                } else {
                    grantLabel
                },
                granted = granted,
                onClick = onGrant,
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Required production permissions intentionally retain no Skip
            // action. The fixed visual slot keeps the primary CTA and dots in
            // the same positions as the prototype without adding an interaction.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (skipLabel != null && onSkip != null) {
                    OnboardingSecondaryButton(label = skipLabel, onClick = onSkip)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            OnboardingProgressDots(activeIndex = step)
        }
    }
}

@Composable
private fun NotificationPreview() {
    val colors = LocalAppColors.current
    PrototypeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(colors.brandSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "S",
                    fontFamily = UiFamily,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.brand,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_notifications_card_title),
                    fontFamily = UiFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_notifications_card_subtitle),
                    fontFamily = UiFamily,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.ink3,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.perm_notifications_card_body),
            fontFamily = UiFamily,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = colors.ink2,
            modifier = Modifier.padding(start = 40.dp),
        )
    }
}

@Composable
private fun BatteryPreview() {
    val colors = LocalAppColors.current
    PrototypeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_battery_card_title),
                    fontFamily = UiFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_battery_card_subtitle),
                    fontFamily = UiFamily,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.ink2,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 24.dp)
                    .clearAndSetSemantics { }
                    .background(colors.brand, CircleShape)
                    .padding(2.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(colors.surface, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun PrivacyCallout() {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.brandSoft)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = InfoCircleIcon,
            contentDescription = null,
            tint = colors.brand,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.perm_a11y_privacy),
            fontFamily = UiFamily,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = colors.ink2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PrototypeCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .blurredShadow(
                cornerRadius = 16.dp,
                color = Color.Black.copy(alpha = 0.08f),
                blurRadius = 18.dp,
                offsetY = 8.dp,
            )
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.surface, colors.brandMist),
                )
            )
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(20.dp),
        content = content,
    )
}

@Composable
fun OnboardingBackButton(onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ChevronIcon,
            contentDescription = androidx.compose.ui.res.stringResource(R.string.perm_back),
            modifier = Modifier.size(20.dp),
            tint = colors.ink,
        )
    }
}

@Composable
fun OnboardingPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    granted: Boolean = false,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (granted) {
                    Modifier
                } else {
                    Modifier.blurredShadow(
                        cornerRadius = 16.dp,
                        color = Color.Black.copy(alpha = 0.14f),
                        blurRadius = 10.dp,
                        offsetY = 4.dp,
                    )
                }
            )
            .clip(shape)
            .background(if (granted) colors.brandSoft else colors.brand)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = UiFamily,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            color = if (granted) colors.brandDark else Color.White,
        )
    }
}

@Composable
fun OnboardingSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = UiFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )
    }
}

@Composable
fun OnboardingProgressDots(activeIndex: Int) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(6) { index ->
            if (index > 0) Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .width(if (index == activeIndex) 28.dp else 8.dp)
                    .height(8.dp)
                    .background(
                        color = if (index == activeIndex) colors.brand else colors.ink3,
                        shape = CircleShape,
                    ),
            )
        }
    }
}
