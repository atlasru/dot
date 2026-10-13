package dev.dotclient.android.core.subscription

import dev.dotclient.android.BuildConfig
import android.os.Build
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.parser.SubscriptionDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SubscriptionClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val osVersion: () -> String = { Build.VERSION.RELEASE },
) {
    private val http = client.newBuilder().addNetworkInterceptor { chain ->
        val original = chain.call().request().url
        val redirected = chain.request().url
        val request = if (original.scheme == redirected.scheme && original.host == redirected.host && original.port == redirected.port) {
            chain.request()
        } else {
            chain.request().newBuilder().apply {
                IDENTITY_HEADERS.forEach(::removeHeader)
            }.build()
        }
        chain.proceed(request)
    }.build()

    internal fun request(subscription: Subscription): Request {
        require(subscription.url.startsWith("https://")) { "Subscription URL must use HTTPS" }
        require(SubscriptionIdentity.isValid(subscription.hwid)) { SubscriptionIdentity.VALIDATION_ERROR }
        return Request.Builder()
            .url(subscription.url)
            .get()
            .header("Accept", "*/*")
            .header("User-Agent", "dot/${BuildConfig.VERSION_NAME} (Android)")
            .header("x-hwid", subscription.hwid)
            .header("x-device-os", "Android")
            .header("x-ver-os", osVersion().filter { it in ' '..'~' }.take(64).ifEmpty { "unknown" })
            .header("x-device-model", "dot Android")
            .build()
    }

    suspend fun fetch(subscription: Subscription): Result<SubscriptionDecoder.DecodeResult> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request(subscription)).execute().use { response ->
                if (!response.isSuccessful) throw SubscriptionHttpException(response.code)
                val body = response.body.string()
                SubscriptionDecoder.decode(body)
            }
        }
    }

    private companion object {
        val IDENTITY_HEADERS = listOf("x-hwid", "x-device-os", "x-ver-os", "x-device-model")
    }
}
