package com.zerotranslater.processtext

import com.zerotranslater.engine.ProcessTextLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTextIntentParserTest {

    @Test
    fun `null extra is empty`() {
        assertEquals(ProcessTextIntentParser.Result.Empty, ProcessTextIntentParser.parse(null, false))
    }

    @Test
    fun `empty string is empty`() {
        assertEquals(ProcessTextIntentParser.Result.Empty, ProcessTextIntentParser.parse("", false))
    }

    @Test
    fun `whitespace only is empty`() {
        // A user can select a run of spaces; showing an empty sheet would be a bug.
        assertEquals(
            ProcessTextIntentParser.Result.Empty,
            ProcessTextIntentParser.parse("   \n\t  ", false),
        )
    }

    @Test
    fun `normal text passes through untouched`() {
        val result = ProcessTextIntentParser.parse("hello world", readOnly = true)
        assertTrue(result is ProcessTextIntentParser.Result.Ready)
        result as ProcessTextIntentParser.Result.Ready
        assertEquals("hello world", result.text)
        assertTrue(result.readOnly)
        assertFalse(result.truncated)
    }

    @Test
    fun `text at exactly the limit is not truncated`() {
        val exact = "a".repeat(ProcessTextLimits.MAX_CHARS)
        val result = ProcessTextIntentParser.parse(exact, false) as
            ProcessTextIntentParser.Result.Ready
        assertEquals(ProcessTextLimits.MAX_CHARS, result.text.length)
        assertFalse(result.truncated)
    }

    @Test
    fun `text one character over the limit is truncated`() {
        val over = "a".repeat(ProcessTextLimits.MAX_CHARS + 1)
        val result = ProcessTextIntentParser.parse(over, false) as
            ProcessTextIntentParser.Result.Ready
        assertEquals(ProcessTextLimits.MAX_CHARS, result.text.length)
        assertTrue(result.truncated)
    }

    @Test
    fun `a whole chapter is capped rather than translated`() {
        val chapter = "lorem ipsum ".repeat(20_000)
        assertTrue(chapter.length > ProcessTextLimits.MAX_CHARS)
        val result = ProcessTextIntentParser.parse(chapter, false) as
            ProcessTextIntentParser.Result.Ready
        assertEquals(ProcessTextLimits.MAX_CHARS, result.text.length)
        assertTrue(result.truncated)
        assertTrue(result.text.startsWith("lorem ipsum "))
    }

    @Test
    fun `charsequence is accepted not just string`() {
        val result = ProcessTextIntentParser.parse(StringBuilder("from a builder"), false) as
            ProcessTextIntentParser.Result.Ready
        assertEquals("from a builder", result.text)
    }
}
