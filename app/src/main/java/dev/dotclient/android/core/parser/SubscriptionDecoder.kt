package dev.dotclient.android.core.parser

import java.util.Base64
import dev.dotclient.android.core.model.VlessProfile

object SubscriptionDecoder {
    data class DecodeResult(
        val profiles: List<VlessProfile>,
        val rejectedLines: List<String>,
        val format: Format,
    ) {
        enum class Format { PLAINTEXT, BASE64, EMPTY, UNSUPPORTED }
    }

    fun decode(body: String): DecodeResult {
        val trimmed = body.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) return DecodeResult(emptyList(), emptyList(), DecodeResult.Format.EMPTY)

        parseLines(trimmed)?.let { return it.copy(format = DecodeResult.Format.PLAINTEXT) }

        val decoded = decodeBase64Lenient(trimmed)
        if (decoded != null) {
            parseLines(decoded)?.let { return it.copy(format = DecodeResult.Format.BASE64) }
        }

        return DecodeResult(emptyList(), listOf("Unsupported subscription response"), DecodeResult.Format.UNSUPPORTED)
    }

    private fun parseLines(text: String): DecodeResult? {
        val candidateLines = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)

        if (candidateLines.none { it.startsWith("vless://", ignoreCase = true) }) return null

        val profiles = mutableListOf<VlessProfile>()
        val rejected = mutableListOf<String>()

        candidateLines.forEach { line ->
            if (!line.startsWith("vless://", ignoreCase = true)) {
                rejected += line.take(160)
                return@forEach
            }
            VlessUriParser.parse(line)
                .onSuccess(profiles::add)
                .onFailure { rejected += redact(line) }
        }

        return DecodeResult(profiles, rejected, DecodeResult.Format.PLAINTEXT)
    }

    private fun decodeBase64Lenient(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
            ?: return null
        return runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    private fun redact(value: String): String =
        value.replace(Regex("vless://[^@]+@", RegexOption.IGNORE_CASE), "vless://***@")
}
