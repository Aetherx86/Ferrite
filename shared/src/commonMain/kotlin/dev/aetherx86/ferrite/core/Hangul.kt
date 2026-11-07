package dev.aetherx86.ferrite.core

private const val SYLLABLE_BASE = 0xAC00
private const val SYLLABLE_COUNT = 11172
private const val PER_INITIAL = 588

private val INITIALS = charArrayOf(
    'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
)

private fun isConsonant(c: Char) = c in INITIALS

/** The initial consonant of a syllable, or the character itself. */
fun initialOf(c: Char): Char {
    val code = c.code - SYLLABLE_BASE
    return if (code in 0 until SYLLABLE_COUNT) INITIALS[code / PER_INITIAL] else c
}

/**
 * How well a name matches what was typed, or null if it does not. Lower is better.
 *
 * The query can mix full syllables and bare initial consonants, so both "삼각지" and "ㅅㄱㅈ" find 삼각지, and so
 * does "삼ㄱ". An exact name beats a prefix, which beats a match further in.
 */
fun searchScore(name: String, query: String): Int? {
    val q = query.filterNot { it.isWhitespace() }.lowercase()
    if (q.isEmpty()) return null
    val n = name.filterNot { it.isWhitespace() }.lowercase()
    if (n == q) return 0
    if (q.length > n.length) return null
    for (start in 0..n.length - q.length) {
        var matched = true
        for (i in q.indices) {
            val typed = q[i]
            val actual = n[start + i]
            val ok = if (isConsonant(typed)) initialOf(actual) == typed else actual == typed
            if (!ok) {
                matched = false
                break
            }
        }
        if (matched) return 1 + start * 2 + (n.length - q.length)
    }
    return null
}
