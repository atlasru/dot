package dev.dotclient.android.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class NodeLatencyTester(private val context: Context) {
    suspend fun test(rawUri: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val config = convertProfile(rawUri)
            val file = File(context.cacheDir, "dot-url-test-${UUID.randomUUID()}.json")
            try {
                file.writeText(config.toString())
                val request = JSONObject()
                    .put("apiVersion", 1)
                    .put("method", "pingBatch")
                    .put(
                        "payload",
                        JSONObject()
                            .put("configs", JSONArray().put(JSONObject().put("configPath", file.absolutePath)))
                            .put("timeout", 5)
                            .put("url", TEST_URL),
                    )
                val response = JSONObject(LibXray.invoke(request.toString()))
                if (!response.optBoolean("success")) {
                    error(response.optString("error", "url test failed"))
                }
                val delay = extractDelay(response.opt("data"))
                when (delay) {
                    10_000L -> error("url test failed")
                    11_000L -> error("url test timeout")
                    in 0L..9_999L -> delay
                    else -> error("libXray returned no latency")
                }
            } finally {
                file.delete()
            }
        }
    }

    private fun convertProfile(rawUri: String): JSONObject {
        val request = JSONObject()
            .put("apiVersion", 1)
            .put("method", "convertShareLinksToXrayJson")
            .put("payload", JSONObject().put("text", rawUri))
        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success")) {
            error(response.optString("error", "failed to convert VLESS link"))
        }
        val generated = response.optString("data")
        if (generated.isBlank()) error("libXray returned an empty config")
        val config = JSONObject(generated)
        val shareUri = Uri.parse(rawUri)
        config.remove("metrics")
        config.optJSONArray("outbounds")?.let { outbounds ->
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                outbound.remove("sendThrough")
                val stream = outbound.optJSONObject("streamSettings") ?: continue
                if (!stream.optString("security").equals("reality", true)) continue
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }
                SERVER_ONLY_REALITY_KEYS.forEach(reality::remove)
                shareUri.getQueryParameter("fp")?.takeIf(String::isNotBlank)?.let { reality.put("fingerprint", it) }
                shareUri.getQueryParameter("sni")?.takeIf(String::isNotBlank)?.let { reality.put("serverName", it) }
                shareUri.getQueryParameter("pbk")?.takeIf(String::isNotBlank)?.let {
                    reality.put("password", it)
                    reality.put("publicKey", it)
                }
                shareUri.getQueryParameter("sid")?.let { reality.put("shortId", it) }
                shareUri.getQueryParameter("spx")?.takeIf(String::isNotBlank)?.let { reality.put("spiderX", it) }
                shareUri.getQueryParameter("pqv")?.takeIf(String::isNotBlank)?.let { reality.put("mldsa65Verify", it) }
            }
        }
        return config
    }

    private fun extractDelay(data: Any?): Long {
        fun fromObject(obj: JSONObject?): Long {
            if (obj == null) return -1L
            if (obj.has("delay")) return obj.optLong("delay", -1L)
            val nested = obj.optJSONObject("data")
            if (nested?.has("delay") == true) return nested.optLong("delay", -1L)
            val results = obj.optJSONArray("results")
            if (results != null && results.length() > 0) return fromObject(results.optJSONObject(0))
            return -1L
        }
        return when (data) {
            is JSONArray -> if (data.length() > 0) fromObject(data.optJSONObject(0)) else -1L
            is JSONObject -> fromObject(data)
            is String -> {
                val text = data.trim()
                runCatching { extractDelay(JSONArray(text)) }.getOrElse {
                    runCatching { extractDelay(JSONObject(text)) }.getOrDefault(-1L)
                }
            }
            else -> -1L
        }
    }

    companion object {
        const val TEST_URL = "http://cp.cloudflare.com/"
        private val SERVER_ONLY_REALITY_KEYS = listOf(
            "target", "dest", "type", "xver", "serverNames", "privateKey",
            "minClientVer", "maxClientVer", "maxTimeDiff", "shortIds", "mldsa65Seed",
            "limitFallbackUpload", "limitFallbackDownload",
        )
    }
}
