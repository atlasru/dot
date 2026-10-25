package dev.dotclient.android.core.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class PingMethod(val key: String, val label: String) {
    PROXY_GET("proxy_get", "Via Proxy GET"), PROXY_HEAD("proxy_head", "Via Proxy HEAD"),
    TCP("tcp", "TCP Ping"), ICMP("icmp", "ICMP Ping");
    val proxy get() = this == PROXY_GET || this == PROXY_HEAD
}
enum class Aggregation(val key: String) { MINIMUM("minimum"), AVERAGE("average"), MEDIAN("median"), LAST("last") }
data class PingSettings(
    val method: PingMethod = PingMethod.PROXY_GET,
    val url: String = "https://cp.cloudflare.com/generate_204",
    val timeoutSeconds: Int = 5, val attempts: Int = 3,
    val aggregation: Aggregation = Aggregation.MEDIAN, val parallelism: Int = 4,
    val onStart: Boolean = false, val repeatMinutes: Int = 0, val selectedOnly: Boolean = false,
) {
    fun validate() {
        require(timeoutSeconds in listOf(3,5,10,15) && attempts in listOf(1,2,3,5) && parallelism in listOf(1,2,4,8) && repeatMinutes in listOf(0,1,5,15,30,60)) { "Invalid diagnostics limits" }
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.fragment == null && url.length <= 2048) { "Test URL must be HTTP(S), without credentials or fragment" }
    }
    fun json() = JSONObject().put("method",method.key).put("url",url).put("timeout_seconds",timeoutSeconds).put("attempts",attempts).put("aggregation",aggregation.key).put("parallelism",parallelism).put("on_start",onStart).put("repeat_minutes",repeatMinutes).put("selected_only",selectedOnly)
    companion object {
        fun from(json: JSONObject) = PingSettings(
            method = PingMethod.entries.first { it.key == json.optString("method", "proxy_get") },
            url = json.optString("url", "https://cp.cloudflare.com/generate_204"),
            timeoutSeconds = json.optInt("timeout_seconds",5), attempts = json.optInt("attempts",3),
            aggregation = Aggregation.entries.first { it.key == json.optString("aggregation","median") },
            parallelism = json.optInt("parallelism",4), onStart = json.optBoolean("on_start"), repeatMinutes = json.optInt("repeat_minutes"), selectedOnly = json.optBoolean("selected_only"),
        ).also { it.validate() }
    }
}
fun aggregate(samples: List<Long>, mode: Aggregation): Long? {
    if (samples.isEmpty()) return null
    return when (mode) {
        Aggregation.MINIMUM -> samples.min()
        Aggregation.AVERAGE -> samples.sum() / samples.size
        Aggregation.LAST -> samples.last()
        Aggregation.MEDIAN -> samples.sorted().let { if (it.size % 2 == 0) (it[it.size/2-1]+it[it.size/2])/2 else it[it.size/2] }
    }
}
data class PingResult(
    val settings: PingSettings, val latencyMs: Long?, val testedAtMs: Long,
    val success: Boolean, val error: String?, val samples: List<Long>, val activeTunnel: Boolean = false,
) {
    val method get() = settings.method
    val url get() = settings.url.takeIf { method.proxy }
    fun verified(current: PingSettings) = success && method.proxy && settings == current && System.currentTimeMillis() - testedAtMs in 0..899_999
    fun json() = JSONObject().put("settings",settings.json()).put("latency_ms",latencyMs ?: JSONObject.NULL).put("tested_at_ms",testedAtMs).put("success",success).put("error",error ?: JSONObject.NULL).put("method",method.key).put("url",url ?: JSONObject.NULL).put("samples",JSONArray(samples)).put("active_tunnel",activeTunnel)
    companion object {
        fun from(j: JSONObject): PingResult {
            val samples=j.getJSONArray("samples")
            return PingResult(PingSettings.from(j.getJSONObject("settings")),if (j.isNull("latency_ms")) null else j.getLong("latency_ms"),j.getLong("tested_at_ms"),j.getBoolean("success"),if(j.isNull("error")) null else j.getString("error"),List(samples.length()) {samples.getLong(it)},j.optBoolean("active_tunnel"))
        }
    }
}
