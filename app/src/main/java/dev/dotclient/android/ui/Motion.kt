package dev.dotclient.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

internal object DotMotion {
    const val Fast = 140
    const val Normal = 230
    const val Slow = 390

    fun <T> tween(durationMillis: Int = Normal): TweenSpec<T> =
        androidx.compose.animation.core.tween(durationMillis, easing = FastOutSlowInEasing)

    fun <T> spring(visibilityThreshold: T? = null): SpringSpec<T> =
        androidx.compose.animation.core.spring(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = visibilityThreshold,
        )

    val press = spring<Float>(visibilityThreshold = 0.001f)
}

@Composable
internal fun Modifier.motionClickable(
    enabled: Boolean = true,
    role: Role? = null,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (enabled && pressed.value) pressedScale else 1f,
        animationSpec = DotMotion.press,
        label = "press feedback",
    )
    return graphicsLayer {
        val value = scale.value
        scaleX = value
        scaleY = value
    }.clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}
