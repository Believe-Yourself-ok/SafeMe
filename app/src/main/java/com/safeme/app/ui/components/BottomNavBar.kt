package com.safeme.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.ui.theme.LocalAppColors

private val NavBarHeight = 66.dp
private val NavBarHorizontalMargin = 10.dp
private val NavBarBottomMargin = 10.dp
private val NavBarCornerRadius = 22.dp
private val NavBarShadowBlur = 24.dp
private val NavBarShadowOffset = 6.dp
private val NavIconSize = 23.dp
private val NavLabelSize = 10.sp
private val NavIconLabelGap = 3.dp

data class NavDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val showBadge: Boolean = false,
)

val MainNavDestinations = listOf(
    NavDestination("home", R.string.nav_home, NavHomeIcon),
    NavDestination("block", R.string.nav_block, NavBlockIcon),
    NavDestination("schedule", R.string.nav_focusme, NavScheduleIcon),
    NavDestination("profile", R.string.nav_profile, NavProfileIcon),
)

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    destinations: List<NavDestination> = MainNavDestinations,
) {
    val colors = LocalAppColors.current
    val navBarGlass = colors.surface.copy(alpha = 0.86f)
    val navBarBorderColor = colors.line.copy(alpha = 0.9f)
    val navBarShadowColor = colors.ink.copy(alpha = 0.08f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = NavBarHorizontalMargin)
            .padding(bottom = NavBarBottomMargin)
            .height(NavBarHeight)
            .blurredShadow(
                cornerRadius = NavBarCornerRadius,
                color = navBarShadowColor,
                blurRadius = NavBarShadowBlur,
                offsetY = NavBarShadowOffset,
            )
            .clip(RoundedCornerShape(NavBarCornerRadius))
            .background(navBarGlass)
            .border(
                width = 1.dp,
                color = navBarBorderColor,
                shape = RoundedCornerShape(NavBarCornerRadius),
            ),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            destinations.forEach { destination ->
                NavItem(
                    destination = destination,
                    selected = destination.route == currentRoute,
                    onClick = { onNavigate(destination.route) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    destination: NavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val color by animateColorAsState(
        targetValue = if (selected) colors.brand else colors.ink3,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "navItemColor",
    )
    val interactionSource = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val itemWidth: Dp = maxWidth
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(NavIconSize)) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = stringResource(destination.labelRes),
                    tint = color,
                    modifier = Modifier.fillMaxSize(),
                )
                if (destination.showBadge) {
                    val endOffset: Dp = (itemWidth / 2f) - 24.dp
                    BadgeDot(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 7.dp, end = endOffset.coerceAtLeast(0.dp)),
                    )
                }
            }
            Spacer(modifier = Modifier.height(NavIconLabelGap))
            Text(
                text = stringResource(destination.labelRes),
                color = color,
                fontSize = NavLabelSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BadgeDot(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .size(7.dp)
            .border(width = 1.5.dp, color = Color.White, shape = CircleShape)
            .background(colors.danger, CircleShape),
    )
}
