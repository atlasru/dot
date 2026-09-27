package dev.dotclient.android.core.subscription

object SecretRedactor {
    fun url(value: String): String {
        val withoutQuery = value.substringBefore('?')
        val marker = "/sub/user/"
        val index = withoutQuery.indexOf(marker, ignoreCase = true)
        if (index < 0) return withoutQuery
        return withoutQuery.substring(0, index + marker.length) + "••••••••"
    }

    fun vless(value: String): String =
        value.replace(Regex("(vless|hy2|hysteria2)://[^@\\s]+@", RegexOption.IGNORE_CASE)) {
            "${it.groupValues[1]}://***@"
        }.replace(Regex("([?&](?:obfs-password|obfsPassword|salamanderPassword|password|auth|token|key)=)[^&#\\s]+", RegexOption.IGNORE_CASE), "$1***")

    fun raw(value: String, subscriptionUrl: String): String {
        var redacted = value.replace(subscriptionUrl, url(subscriptionUrl))
        redacted = redacted.replace(Regex("https://[^\\s)\\]}>]+", RegexOption.IGNORE_CASE)) { match ->
            url(match.value.trimEnd('.', ',', ';', ':'))
        }
        return vless(redacted)
    }
}
