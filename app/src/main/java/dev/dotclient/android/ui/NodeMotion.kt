package dev.dotclient.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.ProxyNode

@Composable
internal fun rememberSortedNodeProfiles(state: DotUiState): List<ProxyNode> {
    val mode = state.selectedSortMode
    val results = state.pingResults.takeIf { mode == NodeSortMode.DELAY }
    val finishedBatch = state.diagnosticBatch?.takeIf {
        mode == NodeSortMode.DELAY && it.groupId == state.selectedSubscriptionId && !it.running
    }
    return remember(state.profiles, mode, results, finishedBatch) { state.sortedProfiles }
}

@Composable
internal fun NodeResultBadge(badge: String, color: Color) {
    val style = MaterialTheme.typography.labelMedium
    // Reserve space for the longest status while respecting the user's font scale.
    val width = with(LocalDensity.current) { style.fontSize.toDp() * 7.75f }
    val label = remember(badge, color) { badge to color }
    Crossfade(
        targetState = label,
        modifier = Modifier.width(width),
        animationSpec = DotMotion.tween(DotMotion.Fast),
        label = "node result",
    ) { (text, textColor) ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(text, color = textColor, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun NodeResultDetail(detail: String?) {
    AnimatedContent(
        targetState = detail,
        transitionSpec = {
            (fadeIn(DotMotion.tween(DotMotion.Fast)) togetherWith fadeOut(DotMotion.tween(DotMotion.Fast)))
                .using(SizeTransform { _, _ -> DotMotion.tween() })
        },
        label = "node test detail",
    ) { message ->
        if (message != null) {
            Text(
                message,
                color = Color(0xFF777777),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
