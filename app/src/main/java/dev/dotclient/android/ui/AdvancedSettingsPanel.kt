package dev.dotclient.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dotclient.android.core.diagnostics.*
import dev.dotclient.android.core.settings.AppSettings
import org.json.JSONObject

@Composable
internal fun AdvancedSettingsPanel(state: DotUiState, viewModel: MainViewModel) {
    val context=LocalContext.current
    val settings=state.settings
    var section by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(next: AppSettings) = viewModel.updateSettings(next)
    fun edit(name: String, value: String) { editor=name; text=value; error=null }
    @Composable fun category(name: String, body: @Composable () -> Unit) {
        Text("${if(section==name) "−" else "+"}  $name",Modifier.fillMaxWidth().clickable { section=if(section==name) null else name }.padding(vertical=13.dp),style=MaterialTheme.typography.titleSmall)
        if(section==name) { body(); Spacer(Modifier.height(12.dp)) }
    }
    category("GENERAL") {
        ToggleSetting("Connect on app launch",settings.autoConnect,"Requires previously granted Android VPN permission") { update(settings.copy(autoConnect=it)) }
        Text("Disconnect stops VPN and pending retries. Foreground VPN notification is required by Android.",color=Color.Gray,style=MaterialTheme.typography.labelMedium)
    }
    category("CONNECTION") {
        ToggleSetting("Automatic reconnect",settings.reconnect,"Retry transient connection failures") { update(settings.copy(reconnect=it)) }
        ToggleSetting("Reconnect after network recovery",settings.reconnectOnNetwork,"When disabled, network loss disconnects VPN") { update(settings.copy(reconnectOnNetwork=it)) }
        ChoiceSetting("Retry base",settings.retrySeconds,listOf(1,2,5,10),{"${it}s"}) { update(settings.copy(retrySeconds=it)) }
        ChoiceSetting("Maximum retry delay",settings.maxRetrySeconds,listOf(10,30,60,120),{"${it}s"}) { update(settings.copy(maxRetrySeconds=it)) }
    }
    category("PING & DIAGNOSTICS") {
        val ping=settings.ping
        ChoiceSetting("Method",ping.method,PingMethod.entries.toList(),{it.label}) { update(settings.copy(ping=ping.copy(method=it))) }
        Text(when(ping.method) { PingMethod.PROXY_GET->"Complete HTTP response via proxy; confirms proxy operation."; PingMethod.PROXY_HEAD->"Response headers via proxy. HTTP 405 means the URL does not support HEAD."; PingMethod.TCP->"Server TCP connect only. Does not confirm proxy health; unavailable for HY2."; PingMethod.ICMP->"Real system ICMP. May be blocked by device permissions or the server." },color=Color.Gray,style=MaterialTheme.typography.labelMedium)
        if(ping.method.proxy) ActionSetting("Test URL",ping.url) { edit("URL",ping.url) }
        ChoiceSetting("Timeout",ping.timeoutSeconds,listOf(3,5,10,15),{"${it}s"}) { update(settings.copy(ping=ping.copy(timeoutSeconds=it))) }
        ChoiceSetting("Attempts",ping.attempts,listOf(1,2,3,5),{it.toString()}) { update(settings.copy(ping=ping.copy(attempts=it))) }
        ChoiceSetting("Aggregation",ping.aggregation,Aggregation.entries.toList(),{it.key}) { update(settings.copy(ping=ping.copy(aggregation=it))) }
        ChoiceSetting("Parallel tests",ping.parallelism,listOf(1,2,4,8),{it.toString()}) { update(settings.copy(ping=ping.copy(parallelism=it))) }
        ToggleSetting("Test on launch",ping.onStart,"Runs while VPN is disconnected") { update(settings.copy(ping=ping.copy(onStart=it))) }
        ChoiceSetting("Repeat",ping.repeatMinutes,listOf(0,1,5,15,30,60),{if(it==0) "off" else "${it}min"}) { update(settings.copy(ping=ping.copy(repeatMinutes=it))) }
        ToggleSetting("Selected node only",ping.selectedOnly,"Scope for scheduled tests; TEST ALL always tests the subscription") { update(settings.copy(ping=ping.copy(selectedOnly=it))) }
        Text("While connected, only the active tunnel can be tested. Core startup and config preparation are excluded from timing.",color=Color.Gray,style=MaterialTheme.typography.labelMedium)
        ActionSetting("Run diagnostics",if(ping.selectedOnly) "selected node" else "subscription") { if(ping.selectedOnly) state.selectedProfile?.let(viewModel::testNode) else viewModel.testAllNodes() }
        if(state.testingNodeIds.isNotEmpty()) ActionSetting("Cancel diagnostics","stop") { viewModel.cancelDiagnostics() }
        ActionSetting("Clear diagnostics","reset cache") { viewModel.clearDiagnostics() }
        state.selectedProfileId?.let { id -> state.pingResults[id]?.let { r -> Text("${r.method.label} · ${if(r.success) "${r.latencyMs} ms" else r.error} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.testedAtMs))}\n${r.samples.size}/${r.settings.attempts} successful attempts",color=Color.Gray,style=MaterialTheme.typography.labelMedium) } }
    }
    category("DNS & ROUTING") {
        ActionSetting("DNS server",settings.dns) { edit("DNS",settings.dns) }
        ToggleSetting("Bypass LAN",settings.bypassLan,"Route private addresses directly; applies after reconnect") { update(settings.copy(bypassLan=it)) }
        Text("DNS accepts a numeric IPv4/IPv6 resolver. App split tunneling is available below. Connection changes apply after reconnect.",color=Color.Gray,style=MaterialTheme.typography.labelMedium)
    }
    category("SUBSCRIPTIONS") {
        ToggleSetting("Refresh on launch",settings.refreshOnStart,"Failed refresh retains previous nodes") { update(settings.copy(refreshOnStart=it)) }
        ChoiceSetting("Refresh interval",settings.refreshHours,listOf(0,1,6,12,24),{if(it==0) "off" else "${it}h"}) { update(settings.copy(refreshHours=it)) }
        ChoiceSetting("Request timeout",settings.subscriptionTimeoutSeconds,listOf(5,10,20,30,60),{"${it}s"}) { update(settings.copy(subscriptionTimeoutSeconds=it)) }
        ActionSetting("User-Agent",settings.subscriptionUserAgent.ifEmpty { "dot default" }) { edit("User-Agent",settings.subscriptionUserAgent) }
        ActionSetting("Refresh all","now") { viewModel.refreshAllSubscriptions() }
        Text("Selection is preserved when its node remains available. Scheduled updates run while the app is open.",color=Color.Gray,style=MaterialTheme.typography.labelMedium)
    }
    category("NODES & AUTO") {
        Text("AUTO selects the lowest latency among proxies verified by all GET/HEAD attempts in the last 15 minutes. TCP/ICMP never confirm proxy operation. Manual selection takes priority.",color=Color.Gray,style=MaterialTheme.typography.labelMedium)
        ChoiceSetting("Maximum latency",settings.autoMaxLatencyMs,listOf(0L,100L,300L,500L,1000L),{if(it==0L) "unlimited" else "$it ms"}) { update(settings.copy(autoMaxLatencyMs=it)) }
        state.selectedProfile?.let { node -> ToggleSetting("Exclude selected node from AUTO",node.id in settings.autoExcluded,node.name) { enabled -> update(settings.copy(autoExcluded=if(enabled) settings.autoExcluded+node.id else settings.autoExcluded-node.id)) } }
    }
    category("LIST APPEARANCE") {
        ToggleSetting("Compact nodes",settings.compactNodes,"") { update(settings.copy(compactNodes=it)) }
        ToggleSetting("Show latency",settings.showLatency,"") { update(settings.copy(showLatency=it)) }
        ToggleSetting("Show protocol and transport",settings.showTransport,"") { update(settings.copy(showTransport=it)) }
    }
    category("ADVANCED") {
        ChoiceSetting("Core log level",settings.logLevel,listOf("none","error","warning"),{it}) { update(settings.copy(logLevel=it)) }
        Text("Verbose core logging is omitted to protect credentials. DNS, routing and log changes require reconnect.",color=Color.Gray,style=MaterialTheme.typography.labelMedium)
        ActionSetting("Export settings","copy JSON") { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dot settings",settings.json().toString(2))) }
        ActionSetting("Import settings","paste JSON") { edit("Import", "") }
        ActionSetting("Export diagnostics","copy redacted JSON") {
            val reports=state.pingResults.values.map { r -> JSONObject().put("method",r.method.key).put("latency_ms",r.latencyMs ?: JSONObject.NULL).put("tested_at_ms",r.testedAtMs).put("success",r.success).put("samples",org.json.JSONArray(r.samples)) }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dot diagnostics",org.json.JSONArray(reports).toString(2)))
        }
        ActionSetting("Reset advanced settings","confirm…") { edit("Reset", "") }
    }
    if(editor!=null) AlertDialog(onDismissRequest={editor=null},title={Text(editor!!)},text={Column { OutlinedTextField(value=text,onValueChange={text=it},label={Text(if(editor=="Reset") "Type RESET" else editor!!)},maxLines=8); error?.let { Text(it,color=MaterialTheme.colorScheme.error) } }},confirmButton={TextButton(onClick={
        try {
            val next=when(editor) { "URL"->settings.copy(ping=settings.ping.copy(url=text.trim())); "DNS"->settings.copy(dns=text.trim()); "User-Agent"->settings.copy(subscriptionUserAgent=text); "Import"->AppSettings.from(JSONObject(text)); "Reset"->{ require(text=="RESET") { "Type RESET to confirm" }; AppSettings() }; else->settings }
            next.validate(); update(next); editor=null
        } catch(e: Exception) { error=e.message ?: "Invalid settings" }
    }) {Text("save")}},dismissButton={TextButton(onClick={editor=null}) {Text("cancel")}})
}

@Composable private fun ToggleSetting(label: String, value: Boolean, detail: String, change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=5.dp)) { Column(Modifier.weight(1f)) { Text(label,style=MaterialTheme.typography.bodyMedium); if(detail.isNotEmpty()) Text(detail,color=Color.Gray,style=MaterialTheme.typography.labelMedium) }; Switch(value,change) }
}
@Composable private fun ActionSetting(label: String, value: String, action: ()->Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick=action).padding(vertical=9.dp)) { Text(label,style=MaterialTheme.typography.bodyMedium); Text(value,color=Color.Gray,style=MaterialTheme.typography.labelMedium) }
}
@Composable private fun <T> ChoiceSetting(label: String, value: T, options: List<T>, display: (T)->String, change: (T)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box { ActionSetting(label,display(value)) {open=true}; DropdownMenu(open,{open=false}) { options.forEach { option -> DropdownMenuItem(text={Text(display(option))},onClick={open=false; change(option)}) } } }
}
