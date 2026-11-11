package dev.dotclient.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal enum class Screen {
    HOME, NODES, SETTINGS, SPLIT_TUNNEL, ABOUT;

    val backDestination: Screen?
        get() = when (this) {
            HOME -> null
            NODES, SETTINGS -> HOME
            SPLIT_TUNNEL, ABOUT -> SETTINGS
        }

    val isTab get() = this == HOME || this == NODES || this == SETTINGS
}

@Composable
internal fun ScreenTransition(
    screen: Screen,
    modifier: Modifier = Modifier,
    content: @Composable (destination: Screen, active: Boolean) -> Unit,
) {
    val savedScreens = rememberSaveableStateHolder()
    val distance = with(LocalDensity.current) { 16.dp.roundToPx() }
    val layoutDirection = LocalLayoutDirection.current
    AnimatedContent(
        targetState = screen,
        modifier = modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            val offset = distance * direction * if (layoutDirection == LayoutDirection.Ltr) 1 else -1
            (fadeIn(DotMotion.tween()) + slideInHorizontally(DotMotion.tween()) { offset }) togetherWith
                (fadeOut(DotMotion.tween(DotMotion.Fast)) + slideOutHorizontally(DotMotion.tween()) { -offset })
        },
        label = "screen transition",
    ) { destination ->
        val active = destination == screen
        val input = if (active) Modifier else Modifier
            .clearAndSetSemantics { hideFromAccessibility() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
        savedScreens.SaveableStateProvider(destination) {
            Box(Modifier.fillMaxSize().then(input)) { content(destination, active) }
        }
    }
}

@Composable
internal fun BottomNavigation(screen: Screen, onSelect: (Screen) -> Unit) {
    val gap = 4.dp
    val barShape = RoundedCornerShape(30.dp)
    val indicatorShape = RoundedCornerShape(24.dp)
    val position = animateFloatAsState(
        targetValue = screen.ordinal.toFloat(),
        animationSpec = DotMotion.spring(visibilityThreshold = 0.001f),
        label = "active tab position",
    )
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 14.dp)
            .background(Color(0xFF0D0D0D), barShape)
            .border(1.dp, Color(0xFF292929), barShape)
            .padding(5.dp),
    ) {
        val tabWidth = (maxWidth - gap * 2) / 3
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.fillMaxHeight().width(tabWidth)
                    .offset { IntOffset(((tabWidth + gap).toPx() * position.value).roundToInt(), 0) }
                    .background(Color(0xFF202020), indicatorShape)
                    .border(1.dp, Color(0xFF3B3B3B), indicatorShape),
            )
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            NavItem(Screen.HOME, screen, Modifier.weight(1f), onSelect)
            NavItem(Screen.NODES, screen, Modifier.weight(1f), onSelect)
            NavItem(Screen.SETTINGS, screen, Modifier.weight(1f), onSelect)
        }
    }
}

@Composable
private fun NavItem(tab: Screen, screen: Screen, modifier: Modifier, onSelect: (Screen) -> Unit) {
    val active = tab == screen
    val textColor by animateColorAsState(
        targetValue = if (active) Color.White else Color(0xFF666666),
        animationSpec = DotMotion.tween(DotMotion.Fast),
        label = "tab label color",
    )
    val dot = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = DotMotion.tween(DotMotion.Fast),
        label = "tab dot opacity",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier.semantics { selected = active }
            .motionClickable(role = Role.Tab) { onSelect(tab) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.graphicsLayer { translationX = (dot.value - 1f) * 6.dp.toPx() * if (rtl) -1f else 1f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).graphicsLayer { alpha = dot.value }.background(DotRed, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(tab.name, color = textColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}
