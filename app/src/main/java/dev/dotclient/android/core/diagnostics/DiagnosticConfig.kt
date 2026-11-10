package dev.dotclient.android.core.diagnostics

import android.net.Uri
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import dev.dotclient.android.vpn.Hysteria2XrayConfig
import java.net.URISyntaxException

internal object DiagnosticConfig {
    fun proxyOutbound(rawUri: String): JSONObject {
        val outbounds = convertProfile(rawUri).getJSONArray("outbounds")
        return (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.firstOrNull {
            it.optString("protocol") in listOf("vless", "hysteria")
        } ?: throw ProbeFailure("Proxy configuration has no supported outbound", PingStatus.FAIL)
    }

    fun batch(outbounds: List<JSONObject>, ports: List<Int>): JSONObject {
        require(outbounds.isNotEmpty() && outbounds.size == ports.size && ports.distinct().size == ports.size)
        val inbounds = JSONArray()
        // A routing mismatch must fail. The default outbound cannot be direct or a different node.
        val proxies = JSONArray().put(JSONObject().put("tag", "blocked").put("protocol", "blackhole"))
        val rules = JSONArray()
        outbounds.zip(ports).forEachIndexed { index, (outbound, port) ->
            val tag = "diagnostic-$index"
            proxies.put(JSONObject(outbound.toString()).put("tag", tag))
            inbounds.put(JSONObject().put("tag", tag).put("listen", "127.0.0.1")
                .put("port", port).put("protocol", "http").put("settings", JSONObject()))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(tag)).put("outboundTag", tag))
        }
        return JSONObject().put("log", JSONObject().put("loglevel", "none")).put("inbounds", inbounds)
            .put("outbounds", proxies).put("routing", JSONObject().put("rules", rules))
    }

    private fun convertProfile(rawUri: String): JSONObject {
        if (rawUri.startsWith("hy2://", true) || rawUri.startsWith("hysteria2://", true)) {
            try {
                return Hysteria2XrayConfig.fromUri(rawUri)
            } catch (_: IllegalArgumentException) {
                throw ProbeFailure("Invalid or unsupported Hysteria2 configuration", PingStatus.FAIL)
            } catch (_: URISyntaxException) {
                throw ProbeFailure("Invalid Hysteria2 URI", PingStatus.FAIL)
            } catch (error: IllegalStateException) {
                when (val reason = error.message) {
                    "Hysteria2 host is missing", "Invalid Hysteria2 insecure flag" ->
                        throw ProbeFailure(reason, PingStatus.FAIL)
                    else -> throw error
                }
            }
        }
        val request = JSONObject()
            .put("apiVersion", 1)
            .put("method", "convertShareLinksToXrayJson")
            .put("payload", JSONObject().put("text", rawUri))
        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success")) {
            throw conversionFailure(response.optString("error"))
        }
        val generated = response.optString("data")
        if (generated.isBlank()) throw ProbeFailure("Diagnostics core returned an empty configuration")
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

    private val SERVER_ONLY_REALITY_KEYS = listOf(
        "target", "dest", "type", "xver", "serverNames", "privateKey",
        "minClientVer", "maxClientVer", "maxTimeDiff", "shortIds", "mldsa65Seed",
        "limitFallbackUpload", "limitFallbackDownload",
    )
}
