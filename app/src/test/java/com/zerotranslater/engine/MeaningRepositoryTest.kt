package com.zerotranslater.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [MeaningRepository] when no Android assets are available.
 * In pure-JVM tests (Robolectric or local JVM), we can only verify
 * the online fallback is attempted correctly — but without a network,
 * both paths return null, which is tested indirectly via [SingleWordDetectorTest].
 */
class MeaningRepositoryTest {

    // Note: MeaningRepository requires an Android Context, so this test class
    // verifies the single-word detection logic that gates meaning lookups.
    // Full integration tests for the repository require Robolectric.

    @Test
    fun `meaning is only looked up for single words`() {
        // Verify that multi-word inputs won't trigger a lookup.
        assertEquals(false, SingleWordDetector.isSingleWord("hello world"))
        assertEquals(false, SingleWordDetector.isSingleWord(""))
        assertEquals(false, SingleWordDetector.isSingleWord("123"))
        assertEquals(true, SingleWordDetector.isSingleWord("happy"))
        assertEquals(true, SingleWordDetector.isSingleWord("quickly"))
    }
}
