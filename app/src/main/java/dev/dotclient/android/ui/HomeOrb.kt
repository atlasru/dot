// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dotclient.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.dotclient.android.vpn.VpnConnectionState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val OrbPixels = listOf(
    "00011111000", "00110001100", "01100000110", "11001010011", "10010101001",
    "10001010001", "10010101001", "11001010011", "01100000110", "00110001100", "00011111000",
)
private const val FullRotation = 360f

@Composable
internal fun HomeOrb(
    state: VpnConnectionState,
    requestingPermission: Boolean,
    enabled: Boolean,
    visible: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = visible && lifecycleState == Lifecycle.State.RESUMED
    val haptic = LocalHapticFeedback.current
    val previousState = remember { mutableStateOf(state) }
    val rotation = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val spinning = state.spinsHomeOrb()
    val connected = state == VpnConnectionState.CONNECTED
    val error = state == VpnConnectionState.ERROR

    LaunchedEffect(state, foreground) {
        val previous = previousState.value
        previousState.value = state
        if (!foreground) return@LaunchedEffect

        if (error && previous != state) haptic.performHapticFeedback(HapticFeedbackType.Reject)
        val durationScale = coroutineContext[MotionDurationScale]
        if (spinning) {
            shake.snapTo(0f)
            snapshotFlow { (durationScale?.scaleFactor ?: 1f) > 0f }.collectLatest { animate ->
                if (animate) {
                    spinHomeOrb(rotation, pulse)
                } else {
                    rotation.snapTo(0f)
                    pulse.snapTo(1f)
                }
            }
        } else if (durationScale?.scaleFactor == 0f) {
            rotation.snapTo(0f)
            pulse.snapTo(1f)
            shake.snapTo(0f)
        } else {
            coroutineScope {
                launch {
                    when {
                        connected && previous.spinsHomeOrb() -> rotation.animateTo(
                            rotation.value + FullRotation / 16,
                            tween(DotMotion.Slow, easing = LinearOutSlowInEasing),
                        )
                        state == VpnConnectionState.DISCONNECTING && previous != state -> rotation.animateTo(
                            rotation.value - FullRotation / 8,
                            DotMotion.tween(DotMotion.Slow),
                        )
                        !connected -> {
                            val upright = (rotation.value / FullRotation).roundToInt() * FullRotation
                            rotation.animateTo(upright, DotMotion.tween())
                            rotation.snapTo(0f)
                        }
                    }
                }
                launch {
                    if (connected && previous != state) pulse.animateTo(1.26f, DotMotion.tween(DotMotion.Fast))
                    pulse.animateTo(1f, DotMotion.spring(0.01f))
                }
                launch {
                    if (error && previous != state) {
                        shake.animateTo(0f, keyframes {
                            durationMillis = DotMotion.Normal
                            -4f at DotMotion.Normal / 5
                            3f at DotMotion.Normal * 2 / 5
                            -2f at DotMotion.Normal * 3 / 5
                            1f at DotMotion.Normal * 4 / 5
                        })
                    } else shake.snapTo(0f)
                }
            }
        }
    }

    val borderColor = animateColorAsState(
        if (connected) Color(0xFF444444) else MaterialTheme.colorScheme.outline,
        animationSpec = DotMotion.tween(), label = "orb border",
    )
    val pixelColor = animateColorAsState(
        if (connected) Color.White else Color(0xFF555555),
        animationSpec = DotMotion.tween(), label = "orb pixels",
    )
    val highlightColor = animateColorAsState(
        when {
            connected -> Color.White
            spinning -> Color(0xFFBDBDBD)
            else -> Color(0xFF555555)
        },
        animationSpec = DotMotion.tween(), label = "orb highlights",
    )
    val dotColor = animateColorAsState(
        when {
            connected || spinning || error -> accent
            state == VpnConnectionState.DISCONNECTING || state == VpnConnectionState.WAITING_FOR_NETWORK ||
                requestingPermission -> Color(0xFF8A8A8A)
            else -> Color(0xFF383838)
        },
        animationSpec = DotMotion.tween(), label = "orb center",
    )
    val dotSize = animateFloatAsState(
        if (connected) 1f else 2f / 3f, animationSpec = DotMotion.tween(), label = "orb center size",
    )
    val errorAlpha = animateFloatAsState(
        if (error) 1f else 0f, animationSpec = DotMotion.tween(DotMotion.Fast), label = "orb error",
    )

    Box(
        Modifier.size(142.dp)
            .motionClickable(enabled = enabled, role = Role.Button, pressedScale = 0.96f, onClick = onClick)
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .drawWithCache {
                val stroke = Stroke(1.dp.toPx())
                val radius = (size.minDimension - stroke.width) / 2f
                onDrawBehind { drawCircle(borderColor.value, radius, style = stroke) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(86.dp)
                .graphicsLayer { rotationZ = rotation.value }
                .drawWithCache {
                    val pixels = Path()
                    val highlights = Path()
                    val width = 6.dp.toPx()
                    val pitch = 8.dp.toPx()
                    OrbPixels.forEachIndexed { row, cells ->
                        cells.forEachIndexed { column, pixel ->
                            if (pixel == '1') {
                                val path = if ((row + column) % 4 == 0) highlights else pixels
                                path.addRect(Rect(column * pitch, row * pitch, column * pitch + width, row * pitch + width))
                            }
                        }
                    }
                    onDrawBehind {
                        drawPath(pixels, pixelColor.value)
                        drawPath(highlights, highlightColor.value)
                    }
                },
        )
        Box(
            Modifier.size(86.dp).drawWithCache {
                val cross = Path()
                val width = 6.dp.toPx()
                val pitch = 8.dp.toPx()
                for (row in OrbPixels.indices) {
                    for (column in OrbPixels.indices) {
                        if (row == column || row + column == OrbPixels.lastIndex) {
                            cross.addRect(Rect(column * pitch, row * pitch, column * pitch + width, row * pitch + width))
                        }
                    }
                }
                onDrawBehind {
                    if (errorAlpha.value > 0f) drawPath(cross, accent, alpha = errorAlpha.value)
                }
            },
        )
        Box(
            Modifier.size(12.dp)
                .graphicsLayer {
                    val scale = dotSize.value * pulse.value
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind { drawCircle(dotColor.value) },
        )
    }
}

internal fun VpnConnectionState.spinsHomeOrb(): Boolean =
    this == VpnConnectionState.CONNECTING || this == VpnConnectionState.RECONNECTING

private suspend fun spinHomeOrb(
    rotation: Animatable<Float, AnimationVector1D>,
    pulse: Animatable<Float, AnimationVector1D>,
) = coroutineScope {
    rotation.snapTo(rotation.value.mod(FullRotation))
    launch {
        rotation.animateTo(
            rotation.value + FullRotation,
            infiniteRepeatable(tween(DotMotion.Slow * 8, easing = LinearEasing)),
        )
    }
    pulse.animateTo(1.24f, infiniteRepeatable(DotMotion.tween(DotMotion.Slow), RepeatMode.Reverse))
}
