package com.zerotranslater.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit's translation models are English-pivoted, which is the single most
 * important fact about this app's storage and quality behaviour. These tests pin
 * it down so a refactor cannot quietly break the pack count shown in the UI.
 */
class LanguagePairTest {

    @Test
    fun `english to other needs only the target pack`() {
        assertEquals(setOf("de"), LanguagePair.requiredPacks("en", "de"))
        assertEquals(1, LanguagePair.packCount("en", "de"))
    }

    @Test
    fun `other to english needs only the source pack`() {
        assertEquals(setOf("de"), LanguagePair.requiredPacks("de", "en"))
        assertEquals(1, LanguagePair.packCount("de", "en"))
    }

    @Test
    fun `non-english pair pivots through english and needs three packs`() {
        assertEquals(setOf("de", "en", "fr"), LanguagePair.requiredPacks("de", "fr"))
        assertEquals(3, LanguagePair.packCount("de", "fr"))
    }

    @Test
    fun `pivot is only required when neither side is english`() {
        assertFalse(LanguagePair.requiresPivot("en", "ja"))
        assertFalse(LanguagePair.requiresPivot("ja", "en"))
        assertTrue(LanguagePair.requiresPivot("ja", "fr"))
    }

    @Test
    fun `identical languages are rejected`() {
        assertNotNull(LanguagePair.rejectionReason("en", "en"))
        assertNotNull(LanguagePair.rejectionReason("de", "de"))
        assertFalse(LanguagePair.isUsable("de", "de"))
    }

    @Test
    fun `known-bad pairs are rejected rather than silently mistranslated`() {
        // https://issuetracker.google.com/issues/369752306
        assertNotNull(LanguagePair.rejectionReason("ja", "ko"))
        assertNotNull(LanguagePair.rejectionReason("ko", "ja"))
    }

    @Test
    fun `ordinary pairs are accepted`() {
        assertNull(LanguagePair.rejectionReason("en", "de"))
        assertNull(LanguagePair.rejectionReason("de", "en"))
        assertTrue(LanguagePair.isUsable("en", "ja"))
    }

    @Test
    fun `display name falls back to the code for unknown tags`() {
        // "zz" is well-formed as a language subtag, so Locale resolves it, but the
        // platform has no display name for it. The fallback path kicks in and the
        // result is title-cased like every other label.
        assertEquals("Zz", LanguagePair.displayName("zz"))
    }

    // Note: displayName is only ever fed codes from TranslateLanguage.getAllLanguages()
    // or from language-id, so its behaviour on malformed tags is not worth pinning.
    // Locale.forLanguageTag is lenient and would silently drop the bad portions.

    @Test
    fun `auto-detect sentinel is labelled`() {
        assertEquals("Auto-detect", LanguagePair.displayName(LanguagePair.AUTO))
    }
}
