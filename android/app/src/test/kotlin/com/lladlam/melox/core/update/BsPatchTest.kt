package com.lladlam.melox.core.update

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture is produced by `tools/make_apk_patch.sh` (the `bsdiff4` reference
 * implementation) for a blob that is inserted into and modified in the middle, so
 * every following byte shifts — the exact case a naive block delta cannot handle.
 */
class BsPatchTest {

    private fun resource(name: String): File =
        File(javaClass.classLoader!!.getResource("hotupdate/$name")!!.toURI())

    @Test
    fun appliesBsdiffPatchProducedByTheReferenceImplementation() {
        val old = resource("old.bin")
        val patch = resource("patch.bin")
        val expected = resource("new.sha256").readText().trim()
        val output = File.createTempFile("bspatch", ".bin")
        try {
            BsPatch.apply(old, patch, output)

            val actual = MessageDigest.getInstance("SHA-256").digest(output.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual)
        } finally {
            output.delete()
        }
    }

    /**
     * Full-size rehearsal against two published APKs, gated on environment
     * variables so CI stays fast:
     *
     *   MELOX_DELTA_OLD_APK, MELOX_DELTA_PATCH, MELOX_DELTA_NEW_SHA256
     */
    @Test
    fun reconstructsRealReleasePairWhenProvided() {
        val oldPath = System.getenv("MELOX_DELTA_OLD_APK")
        val patchPath = System.getenv("MELOX_DELTA_PATCH")
        val expected = System.getenv("MELOX_DELTA_NEW_SHA256")
        org.junit.Assume.assumeTrue(
            "set MELOX_DELTA_OLD_APK/MELOX_DELTA_PATCH/MELOX_DELTA_NEW_SHA256 to run",
            oldPath != null && patchPath != null && expected != null,
        )
        val output = File.createTempFile("bspatch-real", ".apk")
        try {
            BsPatch.apply(File(oldPath!!), File(patchPath!!), output)
            val actual = MessageDigest.getInstance("SHA-256").digest(output.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual)
        } finally {
            output.delete()
        }
    }

    @Test
    fun rejectsGarbagePatch() {
        val junk = File.createTempFile("junk", ".bin").apply { writeBytes(ByteArray(64)) }
        try {
            val error = runCatching {
                BsPatch.apply(resource("old.bin"), junk, File.createTempFile("out", ".bin"))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException && error.message == "not a BSDIFF40 patch")
        } finally {
            junk.delete()
        }
    }
}
