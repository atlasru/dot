package dev.dotclient.android.core.subscription

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class SubscriptionHttpException(
    val statusCode: Int,
) : IOException("Subscription server returned HTTP $statusCode")

class SubscriptionContentException(
    message: String,
) : IOException(message)

data class SubscriptionUpdateErrorInfo(
    val userMessage: String,
    val rawError: String,
)

object SubscriptionUpdateErrorFormatter {
    fun format(error: Throwable, subscriptionUrl: String): SubscriptionUpdateErrorInfo {
        val message = when (error) {
            is UnknownHostException -> "Could not resolve the subscription server."
            is SocketTimeoutException -> "The subscription server did not respond in time."
            is ConnectException -> "Could not connect to the subscription server."
            is SSLException -> "Could not establish a secure connection to the subscription server."
            is SubscriptionHttpException -> when (error.statusCode) {
                401, 403 -> "The subscription server rejected the request."
                404 -> "The subscription was not found."
                in 500..599 -> "The subscription server returned an error."
                else -> "The subscription server returned HTTP ${error.statusCode}."
            }
            is SubscriptionContentException -> error.message ?: "The subscription response could not be parsed."
            is IllegalArgumentException -> error.message ?: "The subscription settings are invalid."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Subscription update failed."
        }

        return SubscriptionUpdateErrorInfo(
            userMessage = message,
            rawError = SecretRedactor.raw(error.stackTraceToString(), subscriptionUrl),
        )
    }
}
