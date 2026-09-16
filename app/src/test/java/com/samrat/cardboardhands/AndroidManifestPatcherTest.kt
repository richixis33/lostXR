package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestPatcherTest {
    private fun manifest(name: String = "open-saber-manifest.bin"): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun lowersTargetSdkSoPackageVisibilityStopsHidingTheRuntimeBroker() {
        val result = AndroidManifestPatcher.patch(manifest())
        assertTrue(result.changes.toString(), result.changes.any { it.startsWith("targetSdk 34 → 29") })
        // Patching again finds nothing left to change.
        assertEquals(emptyList<String>(), AndroidManifestPatcher.patch(result.bytes).changes)
    }

    @Test
    fun clearsRequiredHeadsetFeatures() {
        // Same manifest with the headset feature marked required, the way Quest builds ship it.
        val result = AndroidManifestPatcher.patch(manifest("quest-style-manifest.bin"))
        assertTrue(result.changes.toString(), result.changes.any { it.contains("android.hardware.vr.headtracking") })
        // Nothing is left to clear on a second pass.
        assertEquals(emptyList<String>(), AndroidManifestPatcher.patch(result.bytes).changes)
    }
}
