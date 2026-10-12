package com.lladlam.melox.core.autosync

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class AutoSyncStreamingResamplerTest {
    @Test fun streamingMatchesIndependentBatchFilterAcrossRatesAndBoundaries() {
        for (rate in listOf(8000, 16000, 44100, 48000, 96000)) {
            for (length in listOf(0, 1, 17, 63, 1003, 18001)) {
                val input = FloatArray(length) { (0.3 * sin(it * 0.07) + 0.2 * cos(it * 0.43)).toFloat() }
                val expected = reference(input, rate, 16000)
                for (chunk in listOf(1, 31, 1152, 8192)) {
                    val actual = streaming(input, rate, chunk)
                    assertArrayEquals("rate=$rate length=$length chunk=$chunk", expected, actual, 2e-5f)
                }
            }
        }
    }

    @Test fun antiAliasingRejectsAboveTargetNyquist() {
        fun rms(frequency: Int): Double {
            val input = FloatArray(48000) { sin(2 * PI * frequency * it / 48000).toFloat() }
            val result = streaming(input, 48000, 1152)
            return sqrt(result.sliceArray(100 until result.size - 100).map { it.toDouble() * it }.average())
        }
        assertTrue(rms(1000) > 0.65)
        assertTrue("12 kHz must not alias into 16 kHz output", rms(12000) < 0.01)
    }

    @Test fun impulseKeepsItsTimestampAndLongStreamDoesNotDrift() {
        val input = FloatArray(48000 * 4)
        input[48000] = 1f
        val result = streaming(input, 48000, 137)
        assertEquals(64000, result.size)
        assertEquals(16000, result.indices.maxBy { abs(result[it]) })
    }

    @Test fun pcmFileReadsExactWindowsAndDeletesOnClose() {
        val file = File.createTempFile("autosync-pcm-test", ".tmp")
        val values = FloatArray(10000) { it / 10000f }
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        file.writeBytes(bytes.array())
        AutoSyncPcmFile(file, values.size, 16000).use {
            assertArrayEquals(values.copyOfRange(111, 9999), it.readWindow(111, 9999), 0f)
            assertEquals(0, it.readWindow(10000, 10000).size)
        }
        assertFalse(file.exists())
    }

    private fun streaming(input: FloatArray, rate: Int, chunk: Int): FloatArray {
        val result = ArrayList<Float>()
        val resampler = AutoSyncStreamingResampler(rate, 16000) { values, size ->
            for (index in 0 until size) result.add(values[index])
        }
        var offset = 0
        while (offset < input.size) {
            val end = minOf(offset + chunk, input.size)
            resampler.append(input.copyOfRange(offset, end))
            offset = end
        }
        resampler.finish()
        return result.toFloatArray()
    }

    /** Deliberately full-buffer reference: independent of streaming history/phase/blocking. */
    private fun reference(input: FloatArray, rate: Int, target: Int): FloatArray {
        if (input.isEmpty()) return input
        var signal = input
        val ratio = target.toDouble() / rate
        if (target < rate) {
            val kernel = FloatArray(63)
            var sum = 0.0
            for (i in kernel.indices) {
                val x = i - 31.0
                val a = 0.9 * ratio
                kernel[i] = ((if (x == 0.0) a else sin(PI * a * x) / (PI * x)) *
                    (0.54 - 0.46 * cos(2 * PI * i / 62))).toFloat()
                sum += kernel[i]
            }
            for (i in kernel.indices) kernel[i] = (kernel[i] / sum).toFloat()
            signal = FloatArray(input.size) { i ->
                var total = 0f
                for (k in kernel.indices) if (i + k - 31 in input.indices) total += input[i + k - 31] * kernel[k]
                total
            }
        }
        return FloatArray((input.size * ratio).toInt().coerceAtLeast(1)) { i ->
            val pos = i / ratio
            val base = pos.toInt().coerceAtMost(signal.lastIndex)
            val a = signal[base]
            val b = signal[minOf(base + 1, signal.lastIndex)]
            a + (b - a) * (pos - pos.toInt()).toFloat()
        }
    }
}
