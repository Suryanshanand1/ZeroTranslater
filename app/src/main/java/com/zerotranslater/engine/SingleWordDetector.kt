package com.zerotranslater.engine

/**
 * Detects whether [input] is a single translatable word, suitable for a
 * meaning lookup.
 *
 * Rules (all must hold):
 * - trimmed length ≥ 1 and ≤ MAX_LEN (default 40)
 * - contains no whitespace
 * - consists only of ASCII letters (a-z, A-Z); hyphens and apostrophes are
 *   accepted because they appear in real single words like "mother-in-law"
 *   and "don't"
 */
object SingleWordDetector {

    private const val MAX_LEN = 40
    private val WORD_RE = Regex("""^[A-Za-z][a-zA-Z'-]*$""")

    fun isSingleWord(input: String): Boolean {
        val t = input.trim()
        return t.length in 1..MAX_LEN && ' ' !in t && '\t' !in t && '\n' !in t && WORD_RE.matches(t)
    }
}
