package dev.dotclient.android.core.diagnostics

import dev.dotclient.android.core.parser.*
import dev.dotclient.android.core.model.*
import dev.dotclient.android.core.settings.AppSettings
import dev.dotclient.android.vpn.RuntimeSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsAndTransportTest {
    @Test fun `concurrent field edits preserve current settings and remapped AUTO exclusions`() {
        val base=AppSettings()
        val first=base.copy(dns="8.8.8.8",autoExcluded=setOf("remapped-node"))
        val next=base.copy(showLatency=false,ping=base.ping.copy(attempts=5))
        val merged=dev.dotclient.android.core.settings.mergeSettings(first,base,next)
        assertEquals("8.8.8.8",merged.dns)
        assertFalse(merged.showLatency)
        assertEquals(5,merged.ping.attempts)
        assertEquals(setOf("remapped-node"),merged.autoExcluded)
        val old=Hysteria2UriParser.parse("hy2://secret@example.com#old").getOrThrow()
        val renamed=Hysteria2UriParser.parse("hy2://secret@example.com#new").getOrThrow()
        val diff=dev.dotclient.android.core.subscription.SubscriptionDiffer.calculate(listOf(old),listOf(renamed))
        assertEquals(setOf(renamed.id),base.copy(autoExcluded=setOf(old.id)).remapExclusions(diff,setOf(renamed.id)).autoExcluded)
    }
    @Test fun `AUTO rejects direct stale excluded failed and over threshold results`() {
        val a=Hysteria2UriParser.parse("hy2://secret@a.example#A").getOrThrow()
        val b=Hysteria2UriParser.parse("hy2://secret@b.example#B").getOrThrow()
        val settings=PingSettings()
        fun result(ms: Long, method: PingMethod=PingMethod.PROXY_GET, age: Long=0) = PingResult(settings.copy(method=method),ms,System.currentTimeMillis()-age,true,null,listOf(ms))
        fun select(results: Map<String,PingResult>, excluded: Set<String> = emptySet(), threshold: Long=0) = dev.dotclient.android.core.reliability.AutoNodeSelector.selectVerified(listOf(a,b),results,settings,excluded,threshold)
        assertEquals(b,select(mapOf(a.id to result(100),b.id to result(50))))
        assertNull(select(mapOf(a.id to result(1,PingMethod.TCP))))
        assertNull(select(mapOf(a.id to result(1,age=900_001))))
        assertNull(select(mapOf(a.id to result(1)),setOf(a.id)))
        assertNull(select(mapOf(a.id to result(200)),threshold=100))
        assertNull(select(mapOf(a.id to result(1).copy(success=false))))
    }
    @Test fun `settings migrate validate and roundtrip without node credentials`() {
        val settings=AppSettings.from(JSONObject())
        assertEquals(settings,AppSettings.from(settings.json()))
        assertTrue(settings.reconnect)
        assertEquals("1.1.1.1",settings.dns)
        assertTrue(runCatching { settings.copy(dns="not a numeric resolver").validate() }.isFailure)
        assertTrue(runCatching { settings.copy(dns="deadbeef").validate() }.isFailure)
        assertTrue(runCatching { settings.copy(dns="999.1.1.1").validate() }.isFailure)
        settings.copy(dns="2001:4860:4860::8888").validate()
        assertTrue(runCatching { settings.copy(subscriptionUserAgent="bad\r\nheader").validate() }.isFailure)
        assertTrue(runCatching { AppSettings.from(JSONObject().put("schema_version",2)) }.isFailure)
    }
    @Test fun `HY2 survives stored URI reloads with stable identity and UDP display`() {
        val uri="hy2://secret@example.com:443?sni=example.com#saved"
        val first=Hysteria2UriParser.parse(uri).getOrThrow()
        val reloaded=SubscriptionDecoder.decode(uri).profiles.single()
        assertEquals(first.id,reloaded.id)
        assertEquals(ProxyNode.Transport.UDP,reloaded.effectiveTransport)
        assertEquals(ProxyNode.Transport.UDP,first.copy(transport=ProxyNode.Transport.TCP).effectiveTransport)
        val tcp=VlessUriParser.parse("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=tcp").getOrThrow()
        assertEquals(ProxyNode.Transport.TCP,tcp.effectiveTransport)
        assertEquals(tcp.id,VlessUriParser.parse(tcp.rawUri).getOrThrow().id)
    }
    @Test fun `runtime settings preserve proxy outbound and apply DNS LAN and log level`() {
        val raw="""{"outbounds":[{"tag":"proxy","protocol":"vless"}],"routing":{"rules":[{"outboundTag":"proxy"}]}}"""
        val config=JSONObject(RuntimeSettings.apply(raw,AppSettings(dns="8.8.8.8",bypassLan=true,logLevel="error")))
        assertEquals("vless",config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertEquals("8.8.8.8",config.getJSONObject("dns").getJSONArray("servers").getString(0))
        assertEquals("dot-lan",config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        assertEquals("error",config.getJSONObject("log").getString("loglevel"))
    }
}
