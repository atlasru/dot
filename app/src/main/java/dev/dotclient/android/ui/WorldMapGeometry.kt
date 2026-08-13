package dev.dotclient.android.ui

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class WorldGeoPoint(
    val longitude: Double,
    val latitude: Double,
)

internal data class WorldCountryShape(
    val rings: List<List<WorldGeoPoint>>,
)

internal class WorldMapGeometryRepository(context: Context) {
    private val cacheFile = File(
        context.applicationContext.cacheDir,
        "dot-world-countries-110m.geojson",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun load(): Result<List<WorldCountryShape>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = readFreshCache()
                ?: downloadGeoJson()?.also(::writeCache)
                ?: readAnyCache()
                ?: error("world map geometry unavailable")
            parseGeoJson(json)
        }
    }

    private fun readFreshCache(): String? {
        if (!cacheFile.isFile) return null
        if (System.currentTimeMillis() - cacheFile.lastModified() > CACHE_TTL_MS) return null
        return runCatching { cacheFile.readText() }.getOrNull()
    }

    private fun readAnyCache(): String? = runCatching {
        cacheFile.takeIf(File::isFile)?.readText()
    }.getOrNull()

    private fun writeCache(text: String) {
        runCatching { cacheFile.writeText(text) }
    }

    private fun downloadGeoJson(): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(GEOJSON_URL)
                .header("User-Agent", "dot-android/world-map")
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.string().takeIf(String::isNotBlank)
        }
    }.getOrNull()

    private fun parseGeoJson(text: String): List<WorldCountryShape> {
        val features = JSONObject(text).getJSONArray("features")
        val countries = ArrayList<WorldCountryShape>(features.length())

        for (featureIndex in 0 until features.length()) {
            val geometry = features
                .optJSONObject(featureIndex)
                ?.optJSONObject("geometry")
                ?: continue

            val rings = mutableListOf<List<WorldGeoPoint>>()
            when (geometry.optString("type")) {
                "Polygon" -> parsePolygon(geometry.optJSONArray("coordinates"), rings)
                "MultiPolygon" -> {
                    val polygons = geometry.optJSONArray("coordinates") ?: continue
                    for (polygonIndex in 0 until polygons.length()) {
                        parsePolygon(polygons.optJSONArray(polygonIndex), rings)
                    }
                }
            }

            if (rings.isNotEmpty()) countries += WorldCountryShape(rings)
        }
        return countries
    }

    private fun parsePolygon(
        polygon: JSONArray?,
        destination: MutableList<List<WorldGeoPoint>>,
    ) {
        if (polygon == null) return
        for (ringIndex in 0 until polygon.length()) {
            val ring = parseRing(polygon.optJSONArray(ringIndex))
            if (ring.size >= 3) destination += ring
        }
    }

    private fun parseRing(points: JSONArray?): List<WorldGeoPoint> {
        if (points == null) return emptyList()
        val ring = ArrayList<WorldGeoPoint>(points.length())
        for (pointIndex in 0 until points.length()) {
            val pair = points.optJSONArray(pointIndex) ?: continue
            if (pair.length() < 2) continue
            val longitude = pair.optDouble(0, Double.NaN)
            val latitude = pair.optDouble(1, Double.NaN)
            if (longitude.isFinite() && latitude.isFinite()) {
                ring += WorldGeoPoint(longitude, latitude)
            }
        }
        return ring
    }

    companion object {
        private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private const val GEOJSON_URL =
            "https://raw.githubusercontent.com/datasets/geo-boundaries-world-110m/refs/heads/main/countries.geojson"
    }
}
