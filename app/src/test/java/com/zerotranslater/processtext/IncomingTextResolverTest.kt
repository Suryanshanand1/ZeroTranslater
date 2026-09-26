package com.zerotranslater.processtext

import com.zerotranslater.engine.ProcessTextLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating pill and the share sheet feed this activity through the same
 * resolver as PROCESS_TEXT, so these tests are what guarantee the length cap and
 * the blank-text guard behave identically on all three paths. A divergence here
 * would be invisible in the UI but would mean, for example, a 40,000-character
 * clipboard paste reaching the translator untruncated.
 */
class IncomingTextResolverTest {

    private fun resolve(raw: CharSequence?) = IncomingTextResolver.resolve(raw, readOnly = true)

    @Test
    fun `ordinary text passes through unchanged`() {
        val result = resolve("el gato negro duerme")
        assertTrue(result is IncomingText.Text)
        result as IncomingText.Text
        assertEquals("el gato negro duerme", result.text)
        assertEquals(false, result.truncated)
    }

    @Test
    fun `null is discarded`() {
        assertEquals(IncomingText.Empty, resolve(null))
    }

    @Test
    fun `empty string is discarded`() {
        assertEquals(IncomingText.Empty, resolve(""))
    }

    @Test
    fun `whitespace only is discarded`() {
        // A clipboard holding a single space or a newline is extremely common and
        // must not be handed to the translator.
        assertEquals(IncomingText.Empty, resolve("   "))
        assertEquals(IncomingText.Empty, resolve("\n\n\t "))
    }

    @Test
    fun `text at the limit is not truncated`() {
        val exact = "a".repeat(ProcessTextLimits.MAX_CHARS)
        val result = resolve(exact)
        assertTrue(result is IncomingText.Text)
        result as IncomingText.Text
        assertEquals(ProcessTextLimits.MAX_CHARS, result.text.length)
        assertEquals(false, result.truncated)
    }

    @Test
    fun `text one character over the limit is truncated`() {
        val overBy1 = "a".repeat(ProcessTextLimits.MAX_CHARS + 1)
        val result = resolve(overBy1)
        assertTrue(result is IncomingText.Text)
        result as IncomingText.Text
        assertEquals(ProcessTextLimits.MAX_CHARS, result.text.length)
        assertEquals(true, result.truncated)
    }

    @Test
    fun `a very long clipboard paste is capped rather than rejected`() {
        // NotebookLM can hand over a whole document. The user still wants a
        // translation, so this must truncate and flag rather than discard.
        val huge = "x".repeat(200_000)
        val result = resolve(huge)
        assertTrue(result is IncomingText.Text)
        result as IncomingText.Text
        assertEquals(ProcessTextLimits.MAX_CHARS, result.text.length)
        assertEquals(true, result.truncated)
    }

    @Test
    fun `a CharSequence that is not a String is accepted`() {
        // The share sheet and the clipboard both hand over CharSequence
        // implementations that are not String, notably SpannedString.
        val spanned: CharSequence = object : CharSequence {
            override val length = 5
            override fun get(index: Int) = "hello"[index]
            override fun subSequence(startIndex: Int, endIndex: Int) = "hello"
            override fun toString() = "hello"
        }
        val result = resolve(spanned)
        assertTrue(result is IncomingText.Text)
        assertEquals("hello", (result as IncomingText.Text).text)
    }
}
