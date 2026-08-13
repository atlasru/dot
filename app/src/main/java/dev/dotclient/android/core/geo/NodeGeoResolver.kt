package dev.dotclient.android.core.geo

import android.content.Context
import dev.dotclient.android.core.model.VlessProfile
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class NodeGeoSource {
    GEO_IP,
    NAME_FALLBACK,
}

data class NodeGeoLocation(
    val profileId: String,
    val countryCode: String,
    val countryName: String,
    val city: String?,
    val latitude: Double,
    val longitude: Double,
    val source: NodeGeoSource,
    val resolvedIp: String? = null,
)

class NodeGeoResolver(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("dot_node_geo", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()
    private val countryCenters = ConcurrentHashMap<String, CountryCenter>()

    suspend fun resolve(profile: VlessProfile): NodeGeoLocation? = withContext(Dispatchers.IO) {
        readCached(profile)?.let { return@withContext it }

        val resolvedIp = resolvePublicIp(profile.host)
        val location = resolvedIp
            ?.let { ip -> lookupIp(profile.id, ip) }
            ?: fallbackFromName(profile)

        location?.also { writeCached(profile, it) }
    }

    private fun resolvePublicIp(host: String): String? {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
        return runCatching {
            InetAddress.getAllByName(cleanHost)
                .firstOrNull(::isPublicAddress)
                ?.hostAddress
                ?.substringBefore('%')
        }.getOrNull()
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        if (address is Inet6Address) {
            val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            if (first and 0xFE == 0xFC) return false
        }
        return true
    }

    private fun lookupIp(profileId: String, ip: String): NodeGeoLocation? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("ipapi.co")
            .addPathSegment(ip)
            .addPathSegment("json")
            .build()
        val body = execute(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optBoolean("error")) return null

        val code = json.optString("country_code").uppercase(Locale.ROOT).takeIf { it.length == 2 } ?: return null
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null

        return NodeGeoLocation(
            profileId = profileId,
            countryCode = code,
            countryName = json.optString("country_name").ifBlank { countryDisplayName(code) },
            city = json.optString("city").trim().takeIf(String::isNotBlank),
            latitude = latitude,
            longitude = longitude,
            source = NodeGeoSource.GEO_IP,
            resolvedIp = ip,
        )
    }

    private fun fallbackFromName(profile: VlessProfile): NodeGeoLocation? {
        val code = countryCodeFromNodeName(profile.name) ?: return null
        val center = countryCenters[code] ?: lookupCountryCenter(code)?.also { countryCenters[code] = it } ?: return null
        return NodeGeoLocation(
            profileId = profile.id,
            countryCode = code,
            countryName = center.name,
            city = null,
            latitude = center.latitude,
            longitude = center.longitude,
            source = NodeGeoSource.NAME_FALLBACK,
        )
    }

    private fun lookupCountryCenter(code: String): CountryCenter? {
        readCachedCountry(code)?.let { return it }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("restcountries.com")
            .addPathSegments("v3.1/alpha")
            .addPathSegment(code)
            .addQueryParameter("fields", "cca2,name,latlng")
            .build()
        val body = execute(url) ?: return null
        val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
        val json = when (root) {
            is JSONObject -> root
            is JSONArray -> root.optJSONObject(0)
            else -> null
        } ?: return null

        val latLng = json.optJSONArray("latlng") ?: return null
        if (latLng.length() < 2) return null
        val latitude = latLng.optDouble(0, Double.NaN)
        val longitude = latLng.optDouble(1, Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null

        val center = CountryCenter(
            name = json.optJSONObject("name")?.optString("common").orEmpty().ifBlank { countryDisplayName(code) },
            latitude = latitude,
            longitude = longitude,
        )
        writeCachedCountry(code, center)
        return center
    }

    private fun execute(url: HttpUrl): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "dot-android/0.1")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.string()
        }
    }.getOrNull()

    private fun countryCodeFromNodeName(name: String): String? {
        flagCountryCode(name)?.let { return normalizeCountryCode(it) }

        val token = COUNTRY_TOKEN.findAll(name)
            .map { it.groupValues[1].uppercase(Locale.ROOT) }
            .firstOrNull { normalizeCountryCode(it) != null }
        if (token != null) return normalizeCountryCode(token)

        val normalized = name.lowercase(Locale.ROOT)
        COUNTRY_ALIASES.entries.firstOrNull { (alias, _) -> normalized.contains(alias) }?.let { return it.value }
        return null
    }

    private fun flagCountryCode(text: String): String? {
        val points = text.codePoints().toArray()
        for (index in 0 until points.lastIndex) {
            val first = points[index]
            val second = points[index + 1]
            if (first in REGIONAL_A..REGIONAL_Z && second in REGIONAL_A..REGIONAL_Z) {
                return buildString(2) {
                    append(('A'.code + first - REGIONAL_A).toChar())
                    append(('A'.code + second - REGIONAL_A).toChar())
                }
            }
        }
        return null
    }

    private fun normalizeCountryCode(code: String): String? {
        val mapped = when (code.uppercase(Locale.ROOT)) {
            "UK" -> "GB"
            else -> code.uppercase(Locale.ROOT)
        }
        return mapped.takeIf { it in ISO_COUNTRY_CODES }
    }

    private fun countryDisplayName(code: String): String = runCatching {
        Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)
    }.getOrDefault(code).ifBlank { code }

    private fun cacheKey(profile: VlessProfile): String {
        val fallbackCode = countryCodeFromNodeName(profile.name).orEmpty()
        return "node:${profile.host.lowercase(Locale.ROOT)}:$fallbackCode"
    }

    private fun readCached(profile: VlessProfile): NodeGeoLocation? {
        val raw = preferences.getString(cacheKey(profile), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val storedAt = json.optLong("storedAt", 0L)
        if (System.currentTimeMillis() - storedAt > CACHE_TTL_MS) return null

        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null

        val code = json.optString("countryCode").takeIf(String::isNotBlank) ?: return null
        val source = runCatching { NodeGeoSource.valueOf(json.optString("source")) }.getOrNull() ?: return null
        return NodeGeoLocation(
            profileId = profile.id,
            countryCode = code,
            countryName = json.optString("countryName").ifBlank { countryDisplayName(code) },
            city = json.optString("city").takeIf(String::isNotBlank),
            latitude = latitude,
            longitude = longitude,
            source = source,
            resolvedIp = json.optString("resolvedIp").takeIf(String::isNotBlank),
        )
    }

    private fun writeCached(profile: VlessProfile, location: NodeGeoLocation) {
        val json = JSONObject()
            .put("storedAt", System.currentTimeMillis())
            .put("countryCode", location.countryCode)
            .put("countryName", location.countryName)
            .put("city", location.city ?: "")
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("source", location.source.name)
            .put("resolvedIp", location.resolvedIp ?: "")
        preferences.edit().putString(cacheKey(profile), json.toString()).apply()
    }

    private fun readCachedCountry(code: String): CountryCenter? {
        val raw = preferences.getString("country:$code", null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return CountryCenter(
            name = json.optString("name").ifBlank { countryDisplayName(code) },
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun writeCachedCountry(code: String, center: CountryCenter) {
        preferences.edit().putString(
            "country:$code",
            JSONObject()
                .put("name", center.name)
                .put("latitude", center.latitude)
                .put("longitude", center.longitude)
                .toString(),
        ).apply()
    }

    private data class CountryCenter(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    )

    companion object {
        private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val REGIONAL_A = 0x1F1E6
        private const val REGIONAL_Z = 0x1F1FF
        private val ISO_COUNTRY_CODES = Locale.getISOCountries().toSet() + "XK"
        private val COUNTRY_TOKEN = Regex("(?:^|[^A-Za-z])([A-Za-z]{2})(?=$|[^A-Za-z])")
        private val COUNTRY_ALIASES = linkedMapOf(
            "united states" to "US", " usa" to "US", "america" to "US",
            "united kingdom" to "GB", "great britain" to "GB", "england" to "GB",
            "netherlands" to "NL", "holland" to "NL",
            "germany" to "DE", "deutschland" to "DE",
            "france" to "FR", "finland" to "FI", "sweden" to "SE", "norway" to "NO",
            "poland" to "PL", "spain" to "ES", "italy" to "IT", "switzerland" to "CH",
            "austria" to "AT", "czech" to "CZ", "romania" to "RO", "bulgaria" to "BG",
            "ukraine" to "UA", "russia" to "RU", "россия" to "RU",
            "japan" to "JP", "singapore" to "SG", "hong kong" to "HK", "korea" to "KR",
            "canada" to "CA", "brazil" to "BR", "australia" to "AU", "india" to "IN",
            "turkey" to "TR", "türkiye" to "TR", "israel" to "IL", "uae" to "AE",
        )
    }
}
