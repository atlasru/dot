package dev.dotclient.android.core.subscription

import java.security.SecureRandom

object SubscriptionIdentity {
    const val VALIDATION_ERROR = "HWID must be 10–64 characters: ASCII letters, digits, = or -."
    private val random = SecureRandom()
    private val hex = "0123456789abcdef"

    fun generate(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return buildString(36) {
            append("dot-")
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0xf])
            }
        }
    }

    fun isValid(hwid: String): Boolean = hwid.length in 10..64 && hwid.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '=' || it == '-'
    }
}
