package com.lladlam.melox.core.autosync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Chinese CIF post-processing to the published reference.
 *
 * `autosync/zh_cif_vectors.json` is produced from MeloX-AutoSync
 * `android_export/zh/_work/refs.npz` by running
 * `postprocess_reference.py::ts_prediction_lfr6_standard` — the specification
 * the Android side must reproduce. It carries the truncated `us_alphas` /
 * `us_peaks` and the expected per-token spans for 33 cases, including silence,
 * the 400-sample minimum, a 30 s input and token counts from 1 to 200.
 */
class ZhCifPostprocessTest {

    private fun readVectors(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("autosync/zh_cif_vectors.json")
            ?: error("autosync/zh_cif_vectors.json missing from test resources")
        return stream.use { JSONObject(it.readBytes().decodeToString()) }
    }

    @Test
    fun matchesReferenceForEveryCase() {
        val cases = readVectors().getJSONArray("cases")
        assertTrue("expected reference cases", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val tokenCount = case.getInt("tokenCount")

            val alphaArray = case.getJSONArray("usAlphas")
            val peakArray = case.getJSONArray("usPeaks")
            val alphas = FloatArray(alphaArray.length()) { alphaArray.getDouble(it).toFloat() }
            val peaks = FloatArray(peakArray.length()) { peakArray.getDouble(it).toFloat() }

            val expected = case.getJSONArray("expected")
            val actual = ZhCifPostprocess.tokens(alphas, peaks, tokenCount)

            assertEquals("$name: token count", expected.length(), actual.size)
            for (tokenIndex in 0 until expected.length()) {
                val span = expected.getJSONArray(tokenIndex)
                // getLong keeps this on the primitive overload: getInt would box
                // Integer against Long and fail even when both are 670.
                assertEquals("$name: token[$tokenIndex].startMs", span.getLong(0), actual[tokenIndex].startMs)
                assertEquals("$name: token[$tokenIndex].endMs", span.getLong(1), actual[tokenIndex].endMs)
            }
        }
    }
}
