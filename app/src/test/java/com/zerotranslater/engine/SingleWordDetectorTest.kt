package com.zerotranslater.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleWordDetectorTest {

    @Test
    fun `single letter word is detected`() {
        assertTrue(SingleWordDetector.isSingleWord("a"))
        assertTrue(SingleWordDetector.isSingleWord("i"))
        assertTrue(SingleWordDetector.isSingleWord("A"))
    }

    @Test
    fun `normal words are detected`() {
        assertTrue(SingleWordDetector.isSingleWord("hello"))
        assertTrue(SingleWordDetector.isSingleWord("World"))
        assertTrue(SingleWordDetector.isSingleWord("TEST"))
    }

    @Test
    fun `hyphenated words are detected`() {
        assertTrue(SingleWordDetector.isSingleWord("mother-in-law"))
        assertTrue(SingleWordDetector.isSingleWord("well-being"))
    }

    @Test
    fun `apostrophe words are detected`() {
        assertTrue(SingleWordDetector.isSingleWord("don't"))
        assertTrue(SingleWordDetector.isSingleWord("it's"))
    }

    @Test
    fun `multi-word phrases are rejected`() {
        assertFalse(SingleWordDetector.isSingleWord("hello world"))
        assertFalse(SingleWordDetector.isSingleWord("the quick"))
        assertFalse(SingleWordDetector.isSingleWord("a b c"))
    }

    @Test
    fun `whitespace-only input is rejected`() {
        assertFalse(SingleWordDetector.isSingleWord("   "))
        assertFalse(SingleWordDetector.isSingleWord("\t\n"))
        assertFalse(SingleWordDetector.isSingleWord(""))
    }

    @Test
    fun `numbers are rejected`() {
        assertFalse(SingleWordDetector.isSingleWord("123"))
        assertFalse(SingleWordDetector.isSingleWord("100"))
        assertFalse(SingleWordDetector.isSingleWord("abc123"))
    }

    @Test
    fun `punctuation-only is rejected`() {
        assertFalse(SingleWordDetector.isSingleWord("!@#"))
        assertFalse(SingleWordDetector.isSingleWord("..."))
        assertFalse(SingleWordDetector.isSingleWord("---"))
    }

    @Test
    fun `very long words are rejected`() {
        val long = "a".repeat(41)
        assertFalse(SingleWordDetector.isSingleWord(long))
        assertTrue(SingleWordDetector.isSingleWord("a".repeat(40)))
    }

    @Test
    fun `trimming is applied`() {
        assertTrue(SingleWordDetector.isSingleWord("  hello  "))
        assertFalse(SingleWordDetector.isSingleWord("  hello world  "))
    }
}
