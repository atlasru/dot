package dev.dotclient.android.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

internal val SettingsSelectionSpec = DotMotion.tween<Color>(DotMotion.Fast)

@Composable
internal fun Modifier.settingsEntrance(initialScale: Float = 0.98f): Modifier {
    val progress = remember { Animatable(if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f, DotMotion.tween())
    }
    return graphicsLayer {
        val fraction = progress.value
        alpha = fraction
        scaleX = initialScale + (1f - initialScale) * fraction
        scaleY = scaleX
    }
}

@Composable
internal fun AnimatedSettingValue(
    value: String,
    color: Color,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(DotMotion.tween(DotMotion.Fast)) togetherWith fadeOut(DotMotion.tween(DotMotion.Fast)))
                .using(SizeTransform { _, _ -> DotMotion.tween() })
        },
        label = "Setting value",
    ) { displayedValue ->
        Text(displayedValue, color = color, style = style, maxLines = maxLines)
    }
}
