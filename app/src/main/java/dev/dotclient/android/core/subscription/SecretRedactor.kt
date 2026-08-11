package dev.dotclient.android.core.subscription

object SecretRedactor {
    fun url(value: String): String {
        val marker = "/sub/user/"
        val index = value.indexOf(marker, ignoreCase = true)
        if (index < 0) return value.substringBefore('?')
        return value.substring(0, index + marker.length) + "••••••••"
    }

    fun vless(value: String): String =
        value.replace(Regex("vless://[^@]+@", RegexOption.IGNORE_CASE), "vless://***@")
}
