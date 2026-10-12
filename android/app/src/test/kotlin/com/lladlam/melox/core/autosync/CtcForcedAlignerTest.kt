package com.lladlam.melox.core.autosync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins [CtcForcedAligner] to the Python reference engine.
 *
 * `autosync/ctc_vectors.json` is produced by MeloX-AutoSync
 * (`src/autosync/alignment/ctc.py::forced_align`) and covers the cases the
 * device pipeline depends on: plain sequences, identical adjacent tokens,
 * infeasible requests, a collapsed alignment, a non-zero blank index and a
 * 32-symbol vocabulary shaped like wav2vec2-base-960h.
 *
 * If this test fails, the device would produce different word timings than the
 * engine the models were validated against.
 */
class CtcForcedAlignerTest {

    private fun readVectors(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("autosync/ctc_vectors.json")
            ?: error("autosync/ctc_vectors.json missing from test resources")
        return stream.use { JSONObject(it.readBytes().decodeToString()) }
    }

    @Test
    fun matchesPythonReferenceForEveryCase() {
        val root = readVectors()
        val cases = root.getJSONArray("cases")
        assertTrue("expected reference cases", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val t = case.getInt("T")
            val c = case.getInt("C")
            val blank = case.getInt("blank")

            val raw = case.getJSONArray("logProbs")
            val logProbs = Array(t) { frame ->
                val row = raw.getJSONArray(frame)
                DoubleArray(c) { symbol -> row.getDouble(symbol) }
            }
            val targetsJson = case.getJSONArray("targets")
            val targets = List(targetsJson.length()) { targetsJson.getInt(it) }

            val expected = case.getJSONObject("expected")
            val actual = CtcForcedAligner.align(logProbs, targets, blank)

            assertEquals("$name: feasible", expected.getBoolean("feasible"), actual.feasible)
            assertEquals(
                "$name: requiredFrames",
                expected.getInt("requiredFrames"),
                actual.requiredFrames,
            )
            val expectedReasons = expected.getJSONArray("reasons")
            assertEquals("$name: reason count", expectedReasons.length(), actual.reasons.size)
            for (reasonIndex in 0 until expectedReasons.length()) {
                assertEquals(
                    "$name: reason[$reasonIndex]",
                    expectedReasons.getString(reasonIndex),
                    actual.reasons[reasonIndex],
                )
            }
            assertEquals(
                "$name: totalLogProb",
                expected.getDouble("totalLogProb"),
                actual.totalLogProb,
                abs(expected.getDouble("totalLogProb")) * 1e-9 + 1e-6,
            )
            assertEquals(
                "$name: meanScore",
                expected.getDouble("meanScore"),
                actual.meanScore,
                1e-9,
            )

            val expectedSpans = expected.getJSONArray("spans")
            assertEquals("$name: span count", expectedSpans.length(), actual.spans.size)
            for (spanIndex in 0 until expectedSpans.length()) {
                val expectedSpan = if (expectedSpans.isNull(spanIndex)) null else expectedSpans.getJSONObject(spanIndex)
                val actualSpan = actual.spans[spanIndex]
                if (expectedSpan == null) {
                    assertEquals("$name: span[$spanIndex] should be null", null, actualSpan)
                    continue
                }
                requireNotNull(actualSpan) { "$name: span[$spanIndex] unexpectedly null" }
                assertEquals("$name: span[$spanIndex].token", expectedSpan.getInt("token"), actualSpan.token)
                assertEquals("$name: span[$spanIndex].startFrame", expectedSpan.getInt("startFrame"), actualSpan.startFrame)
                assertEquals("$name: span[$spanIndex].endFrame", expectedSpan.getInt("endFrame"), actualSpan.endFrame)
                assertEquals(
                    "$name: span[$spanIndex].score",
                    expectedSpan.getDouble("score"),
                    actualSpan.score,
                    1e-9,
                )
            }
        }
    }
}
