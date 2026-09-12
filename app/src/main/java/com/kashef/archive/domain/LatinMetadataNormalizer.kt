package com.kashef.archive.domain

data class CanonicalText(
    val latin: String,
    val original: String,
    val requiresReview: Boolean,
)

/**
 * Keeps Muse's visible archive Latin-only without destroying the source spelling.
 * Remote Latin aliases should always win; this deterministic transliteration is the offline fallback.
 */
object LatinMetadataNormalizer {
    private val persianRange = Regex("[\\u0600-\\u06ff]")
    private val letters = linkedMapOf(
        'ا' to "a", 'آ' to "a", 'أ' to "a", 'إ' to "e", 'ء' to "'", 'ئ' to "y", 'ؤ' to "v",
        'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch",
        'ح' to "h", 'خ' to "kh", 'د' to "d", 'ذ' to "z", 'ر' to "r", 'ز' to "z", 'ژ' to "zh",
        'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "'",
        'غ' to "gh", 'ف' to "f", 'ق' to "gh", 'ک' to "k", 'ك' to "k", 'گ' to "g",
        'ل' to "l", 'م' to "m", 'ن' to "n", 'و' to "v", 'ه' to "h", 'ة' to "h",
        'ی' to "y", 'ي' to "y", 'ى' to "a", '‌' to "-",
    )

    fun canonicalize(value: String, remoteLatinAlias: String? = null): CanonicalText {
        val clean = value.trim()
        val alias = remoteLatinAlias?.trim().orEmpty()
        if (alias.isNotBlank() && isLatin(alias)) {
            return CanonicalText(alias, clean.takeIf { it != alias }.orEmpty(), false)
        }
        if (clean.isBlank() || isLatin(clean)) return CanonicalText(clean, "", false)
        val latin = buildString {
            clean.forEach { char -> append(letters[char] ?: if (char.isWhitespace() || char.isDigit() || char in "-_'.,&()") char else ' ') }
        }
            .replace(Regex("\\s+"), " ")
            .replace(Regex("-+"), "-")
            .trim(' ', '-', '\'')
            .split(' ')
            .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
        return CanonicalText(latin.ifBlank { "Unknown" }, clean, true)
    }

    fun isLatin(value: String): Boolean = !persianRange.containsMatchIn(value)
}
