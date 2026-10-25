package dev.dotclient.android.core.diagnostics

import android.net.Uri
import libXray.LibXray
import org.json.JSONObject
import dev.dotclient.android.vpn.Hysteria2XrayConfig

internal object DiagnosticConfig {
    fun convertProfile(rawUri: String): JSONObject {
        if (rawUri.startsWith("hy2://", true) || rawUri.startsWith("hysteria2://", true)) {
            return Hysteria2XrayConfig.fromUri(rawUri)
        }
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

    private val SERVER_ONLY_REALITY_KEYS = listOf(
        "target", "dest", "type", "xver", "serverNames", "privateKey",
        "minClientVer", "maxClientVer", "maxTimeDiff", "shortIds", "mldsa65Seed",
        "limitFallbackUpload", "limitFallbackDownload",
    )
}
