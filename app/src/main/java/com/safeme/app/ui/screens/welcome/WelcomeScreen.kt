package com.safeme.app.ui.screens.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.ui.components.blurredShadow
import com.safeme.app.ui.screens.permissions.OnboardingPrimaryButton
import com.safeme.app.ui.screens.permissions.OnboardingProgressDots
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.UiFamily

private val WelcomeStar: ImageVector = ImageVector.Builder(
    name = "WelcomeStar",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        lineTo(14.4f, 9.6f)
        lineTo(22f, 12f)
        lineTo(14.4f, 14.4f)
        lineTo(12f, 22f)
        lineTo(9.6f, 14.4f)
        lineTo(2f, 12f)
        lineTo(9.6f, 9.6f)
        close()
    }
}.build()

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit = {}) {
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 56.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .blurredShadow(
                            cornerRadius = 16.dp,
                            color = Color.Black.copy(alpha = 0.08f),
                            blurRadius = 6.dp,
                            offsetY = 2.dp,
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.line, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = WelcomeStar,
                        contentDescription = null,
                        tint = colors.brand,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.welcome_title),
                    fontFamily = UiFamily,
                    fontSize = 42.sp,
                    lineHeight = 45.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-1.05).sp,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.welcome_body),
                    fontFamily = UiFamily,
                    fontSize = 15.sp,
                    lineHeight = 24.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = colors.ink2,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            OnboardingPrimaryButton(
                label = androidx.compose.ui.res.stringResource(R.string.welcome_get_started),
                onClick = onGetStarted,
            )
            Spacer(modifier = Modifier.height(48.dp))
            OnboardingProgressDots(activeIndex = 0)
        }
    }
}
