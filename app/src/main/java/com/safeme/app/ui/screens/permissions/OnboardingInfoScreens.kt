package com.safeme.app.ui.screens.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.ui.components.blurredShadow
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.UiFamily

@Composable
fun BoundariesScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
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
                    text = androidx.compose.ui.res.stringResource(R.string.perm_step, 4, 5),
                    fontFamily = UiFamily,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                    color = colors.brand,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_boundaries_title),
                    fontFamily = UiFamily,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.75).sp,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_boundaries_sub),
                    fontFamily = UiFamily,
                    fontSize = 14.5.sp,
                    lineHeight = 23.5.sp,
                    color = colors.ink2,
                )
                Spacer(modifier = Modifier.height(24.dp))

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
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.perm_boundaries_card_title),
                        fontFamily = UiFamily,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.perm_boundaries_card_body),
                        fontFamily = UiFamily,
                        fontSize = 12.sp,
                        lineHeight = 19.5.sp,
                        color = colors.ink2,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            OnboardingPrimaryButton(
                label = androidx.compose.ui.res.stringResource(R.string.perm_boundaries_action),
                onClick = onContinue,
            )
            // Match the permission-step footer geometry without introducing a
            // second action on this informational screen.
            Spacer(modifier = Modifier.height(58.dp))
            OnboardingProgressDots(activeIndex = 4)
        }
    }
}

@Composable
fun ActiveScreen(
    onBack: () -> Unit,
    onOpen: () -> Unit,
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
                .padding(start = 32.dp, top = 12.dp, end = 32.dp, bottom = 60.dp),
        ) {
            OnboardingBackButton(onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(colors.brandSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CheckIcon,
                        contentDescription = null,
                        tint = colors.brand,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_active_title),
                    fontFamily = UiFamily,
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.9).sp,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.perm_active_body),
                    fontFamily = UiFamily,
                    fontSize = 15.sp,
                    lineHeight = 24.5.sp,
                    color = colors.ink2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            OnboardingPrimaryButton(
                label = androidx.compose.ui.res.stringResource(R.string.perm_active_action),
                onClick = onOpen,
            )
        }
    }
}
