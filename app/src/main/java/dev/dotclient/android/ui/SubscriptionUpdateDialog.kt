package dev.dotclient.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.dotclient.android.core.model.VlessProfile
import dev.dotclient.android.core.subscription.NodeEdit

private val UpdateDialogRed = Color(0xFFFF2D2D)

@Composable
fun SubscriptionUpdateResultDialog(
    result: SubscriptionUpdateResult,
    onDismiss: () -> Unit,
) {
    var showDetails by remember(result) { mutableStateOf(false) }
    var showRawError by remember(result) { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF303030), RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                .padding(18.dp),
        ) {
            when (result) {
                is SubscriptionUpdateResult.Success -> SuccessContent(
                    result = result,
                    showDetails = showDetails,
                    onToggleDetails = { showDetails = !showDetails },
                    onDismiss = onDismiss,
                )
                is SubscriptionUpdateResult.Error -> ErrorContent(
                    result = result,
                    showRawError = showRawError,
                    onToggleRawError = { showRawError = !showRawError },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun SuccessContent(
    result: SubscriptionUpdateResult.Success,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text("SUBSCRIPTION UPDATED", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        result.subscriptionName,
        color = Color(0xFF777777),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(16.dp))

    if (!result.diff.hasChanges) {
        Text("NO CHANGES", color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyMedium)
    } else {
        ChangeLine("+", "ADDED", result.diff.added.size)
        ChangeLine("~", "EDITED", result.diff.edited.size)
        ChangeLine("−", "DELETED", result.diff.deleted.size, deleted = true)
    }

    Spacer(Modifier.height(8.dp))
    Text("${result.totalNodes} NODES TOTAL", color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)

    if (showDetails && result.diff.hasChanges) {
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .border(1.dp, Color(0xFF252525), RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (result.diff.added.isNotEmpty()) {
                item { DetailHeader("ADDED") }
                items(result.diff.added, key = { "a:${it.id}" }) { DetailProfile("+", it) }
            }
            if (result.diff.edited.isNotEmpty()) {
                item { DetailHeader("EDITED") }
                items(result.diff.edited, key = { "e:${it.after.id}" }) { DetailEdit(it) }
            }
            if (result.diff.deleted.isNotEmpty()) {
                item { DetailHeader("DELETED") }
                items(result.diff.deleted, key = { "d:${it.id}" }) { DetailProfile("−", it, deleted = true) }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (result.diff.hasChanges) {
            DialogAction(if (showDetails) "HIDE DETAILS" else "DETAILS", onToggleDetails)
            Spacer(Modifier.padding(horizontal = 4.dp))
        }
        DialogAction("OK", onDismiss, primary = true)
    }
}

@Composable
private fun ErrorContent(
    result: SubscriptionUpdateResult.Error,
    showRawError: Boolean,
    onToggleRawError: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text("UPDATE FAILED", color = UpdateDialogRed, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    Text(result.subscriptionName, color = Color(0xFF777777), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(16.dp))

    if (showRawError) {
        Text("RAW ERROR", color = Color(0xFF777777), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .border(1.dp, Color(0xFF252525), RoundedCornerShape(2.dp))
                    .padding(10.dp),
            ) {
                item {
                    Text(
                        result.rawError,
                        color = Color(0xFF9A9A9A),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    } else {
        Text(result.userMessage, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Text("Your existing nodes were kept.", color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
    }

    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        DialogAction(if (showRawError) "BACK" else "VIEW RAW ERROR", onToggleRawError)
        Spacer(Modifier.padding(horizontal = 4.dp))
        DialogAction("OK", onDismiss, primary = true)
    }
}

@Composable
private fun ChangeLine(symbol: String, label: String, count: Int, deleted: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$symbol  $label", color = if (deleted && count > 0) UpdateDialogRed else Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), color = Color(0xFF777777), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailHeader(text: String) {
    Text(text, modifier = Modifier.padding(top = 7.dp, bottom = 3.dp), color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun DetailProfile(symbol: String, profile: VlessProfile, deleted: Boolean = false) {
    Text(
        "$symbol ${profile.name}",
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = if (deleted) UpdateDialogRed else Color(0xFFB0B0B0),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DetailEdit(edit: NodeEdit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("~ ${edit.after.name}", color = Color(0xFFB0B0B0), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(edit.changedFields.joinToString(", "), color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit, primary: Boolean = false) {
    Text(
        label,
        Modifier
            .border(1.dp, if (primary) Color(0xFF555555) else Color(0xFF303030), RoundedCornerShape(2.dp))
            .background(if (primary) Color(0xFF141414) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (primary) Color.White else Color(0xFF9A9A9A),
        style = MaterialTheme.typography.labelMedium,
    )
}
