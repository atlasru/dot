package dev.dotclient.android.core.settings

import android.content.Context
import dev.dotclient.android.core.diagnostics.PingSettings
import dev.dotclient.android.core.diagnostics.PingResult
import org.json.JSONObject
import org.json.JSONArray

data class AppSettings(
    val ping: PingSettings = PingSettings(), val autoConnect: Boolean = false,
    val autoMaxLatencyMs: Long = 0, val autoExcluded: Set<String> = emptySet(),
    val compactNodes: Boolean = false, val showLatency: Boolean = true, val showTransport: Boolean = true,
    val dns: String = "1.1.1.1", val bypassLan: Boolean = false, val logLevel: String = "warning",
    val reconnect: Boolean = true, val reconnectOnNetwork: Boolean = true,
    val retrySeconds: Int = 1, val maxRetrySeconds: Int = 30,
    val refreshOnStart: Boolean = false, val refreshHours: Int = 0,
    val subscriptionTimeoutSeconds: Int = 20, val subscriptionUserAgent: String = "",
) {
    fun validate() {
        ping.validate()
        require(autoMaxLatencyMs in 0..60_000 && autoExcluded.size <= 10000)
        require(logLevel in listOf("none","error","warning"))
        // Numeric DNS only: no accidental hostname lookup on the UI thread.
        require(dns.matches(Regex("[0-9a-fA-F:.]+")) && runCatching { java.net.InetAddress.getByName(dns) }.isSuccess) { "DNS must be an IPv4 or IPv6 address" }
        require(retrySeconds in listOf(1,2,5,10) && maxRetrySeconds in listOf(10,30,60,120) && retrySeconds <= maxRetrySeconds)
        require(refreshHours in listOf(0,1,6,12,24) && subscriptionTimeoutSeconds in listOf(5,10,20,30,60))
        require(subscriptionUserAgent.length <= 256 && subscriptionUserAgent.all { it in ' '..'~' }) { "User-Agent must be printable ASCII" }
    }
    fun json() = JSONObject().put("schema_version",1).put("ping",ping.json()).put("auto_connect",autoConnect).put("auto_max_latency_ms",autoMaxLatencyMs).put("auto_excluded",JSONArray(autoExcluded.toList())).put("compact_nodes",compactNodes).put("show_latency",showLatency).put("show_transport",showTransport).put("dns",dns).put("bypass_lan",bypassLan).put("log_level",logLevel).put("reconnect",reconnect).put("reconnect_on_network",reconnectOnNetwork).put("retry_seconds",retrySeconds).put("max_retry_seconds",maxRetrySeconds).put("refresh_on_start",refreshOnStart).put("refresh_hours",refreshHours).put("subscription_timeout_seconds",subscriptionTimeoutSeconds).put("subscription_user_agent",subscriptionUserAgent)
    companion object {
        fun from(j: JSONObject): AppSettings {
            require(j.optInt("schema_version",1)==1) { "Unsupported settings version" }
            val excluded=j.optJSONArray("auto_excluded") ?: JSONArray()
            return AppSettings(PingSettings.from(j.optJSONObject("ping") ?: JSONObject()), j.optBoolean("auto_connect"),j.optLong("auto_max_latency_ms"),List(excluded.length()) {excluded.getString(it)}.toSet(),j.optBoolean("compact_nodes"),j.optBoolean("show_latency",true),j.optBoolean("show_transport",true),j.optString("dns","1.1.1.1"),j.optBoolean("bypass_lan"),j.optString("log_level","warning"),j.optBoolean("reconnect",true),j.optBoolean("reconnect_on_network",true),j.optInt("retry_seconds",1),j.optInt("max_retry_seconds",30),j.optBoolean("refresh_on_start"),j.optInt("refresh_hours"),j.optInt("subscription_timeout_seconds",20),j.optString("subscription_user_agent","")).also { it.validate() }
        }
    }
}
class SettingsRepository(context: Context) {
    private val prefs=context.getSharedPreferences("dot_settings",Context.MODE_PRIVATE)
    fun load(): AppSettings = AppSettings.from(JSONObject(prefs.getString("settings","{}")!!))
    fun save(settings: AppSettings) {
        settings.validate()
        val previous=prefs.getString("settings",null)
        if(!prefs.edit().putString("settings",settings.json().toString()).commit()) {
            prefs.edit().putString("settings",previous).commit()
            error("Could not save settings")
        }
    }
    fun results(): Map<String,PingResult> = runCatching { val j=JSONObject(prefs.getString("diagnostics","{}")!!); j.keys().asSequence().associateWith { PingResult.from(j.getJSONObject(it)) } }.getOrDefault(emptyMap())
    fun saveResults(results: Map<String,PingResult>) { val j=JSONObject(); results.forEach { (id,r)->j.put(id,r.json()) }; prefs.edit().putString("diagnostics",j.toString()).apply() }
}
