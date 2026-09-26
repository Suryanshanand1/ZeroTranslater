package com.zerotranslater

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies the manifest wiring that makes the system-wide action work.
 *
 * This cannot be unit tested in isolation: the thing under test is a merged
 * AndroidManifest.xml, and a typo in an intent filter, a missing
 * `android.intent.category.DEFAULT`, or a wrong activity label would all produce
 * an app that builds and passes every other test but never appears in the
 * selection menu. Robolectric reads the real merged manifest, so this catches
 * exactly that class of mistake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessTextManifestTest {

    private val packageManager: PackageManager
        get() = RuntimeEnvironment.getApplication().packageManager

    private fun processTextIntent() = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, "hello")
    }

    @Test
    fun `process text intent resolves to the overlay activity`() {
        val resolved = packageManager.resolveActivity(
            processTextIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertNotNull(
            "No activity handles PROCESS_TEXT; the selection-menu entry will be missing",
            resolved,
        )
        assertEquals(
            "com.zerotranslater.ProcessTextActivity",
            resolved!!.activityInfo.name,
        )
    }

    @Test
    fun `the launcher activity does not hijack process text`() {
        val resolved = packageManager.resolveActivity(
            processTextIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(
            "PROCESS_TEXT must not resolve to the launcher",
            resolved!!.activityInfo.name != "com.zerotranslater.MainActivity",
        )
    }

    @Test
    fun `overlay is labelled for the selection menu`() {
        val resolved = packageManager.resolveActivity(
            processTextIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val label = resolved!!.activityInfo.loadLabel(packageManager).toString()
        assertEquals(
            "The label is what the system renders next to Copy/Cut/Paste",
            "Ztranslate",
            label,
        )
    }

    @Test
    fun `overlay activity is exported`() {
        val resolved = packageManager.resolveActivity(
            processTextIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(
            "A non-exported activity is invisible to the system on API 31+",
            resolved!!.activityInfo.exported,
        )
    }

    @Test
    fun `overlay is excluded from recents`() {
        val resolvedInfo = packageManager.resolveActivity(
            processTextIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
        )!!.activityInfo

        val info = packageManager.getActivityInfo(
            ComponentName(resolvedInfo.packageName, resolvedInfo.name),
            0,
        )
        // android:excludeFromRecents is not a boolean field on ActivityInfo; it is
        // a bit in the flags field, hence the mask.
        assertTrue(
            "Without this the overlay leaves a ghost entry in the recents list",
            info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0,
        )
    }

    @Test
    fun `main activity is still the launcher`() {
        // No MATCH_DEFAULT_ONLY here: that flag restricts matches to filters that
        // declare CATEGORY_DEFAULT, and a LAUNCHER filter correctly does not.
        // Using it would make this test fail for a reason that has nothing to do
        // with the manifest being wrong.
        val matches = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        assertEquals("The app must have exactly one launcher entry", 1, matches.size)
        assertEquals("com.zerotranslater.MainActivity", matches[0].activityInfo.name)
    }
}
