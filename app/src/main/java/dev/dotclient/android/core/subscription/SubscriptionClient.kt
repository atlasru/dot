package dev.dotclient.android.core.subscription

import dev.dotclient.android.BuildConfig
import dev.dotclient.android.core.parser.SubscriptionDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SubscriptionClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    suspend fun fetch(url: String): Result<SubscriptionDecoder.DecodeResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Subscription URL must use HTTPS" }

            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", "dot/${BuildConfig.VERSION_NAME} (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Subscription server returned HTTP ${response.code}")
                val body = response.body.string()
                SubscriptionDecoder.decode(body)
            }
        }
    }
}
