package dev.dotclient.android.vpn

import dev.dotclient.android.core.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject

internal object RuntimeSettings {
    fun apply(raw: String, settings: AppSettings): String {
        val config=JSONObject(raw)
        config.put("log",JSONObject().put("loglevel",settings.logLevel))
        config.put("dns",JSONObject().put("servers",JSONArray().put(settings.dns)))
        if(settings.bypassLan) {
            val outbounds=config.getJSONArray("outbounds")
            outbounds.put(JSONObject().put("tag","dot-lan").put("protocol","freedom"))
            val routing=config.optJSONObject("routing") ?: JSONObject()
            val existing=routing.optJSONArray("rules") ?: JSONArray()
            val rules=JSONArray().put(JSONObject().put("type","field").put("ip",JSONArray(listOf("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","127.0.0.0/8","169.254.0.0/16","fc00::/7","fe80::/10","::1/128"))).put("outboundTag","dot-lan"))
            for(i in 0 until existing.length()) rules.put(existing.get(i))
            routing.put("rules",rules); config.put("routing",routing)
        }
        return config.toString()
    }
}
