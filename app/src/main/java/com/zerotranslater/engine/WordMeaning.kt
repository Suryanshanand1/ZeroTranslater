package com.zerotranslater.engine

/**
 * A single part-of-speech definition for a word.
 *
 * @param pos One of "n" (noun), "v" (verb), "a" (adjective), "adv" (adverb).
 * @param definition Short gloss from WordNet or the online dictionary API.
 */
data class Sense(val pos: String, val definition: String)

/**
 * Meanings for a single word, fetched from local cache or the online API.
 *
 * @param word The original (lowercased) word.
 * @param senses Up to 8 (pos, definition) pairs, deduplicated.
 */
data class WordMeaning(val word: String, val senses: List<Sense>) {
    val isEmpty: Boolean get() = senses.isEmpty()
}
