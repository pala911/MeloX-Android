package com.lladlam.melox.core.autosync

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zero-phase, 63-tap anti-aliased rate conversion with bounded working memory.
 * Only filtered samples used by the output grid are evaluated. The half-kernel
 * lookahead is flushed with zero padding at EOF, not added to the timestamps.
 */
internal class AutoSyncStreamingResampler(
    private val sourceRate: Int,
    private val targetRate: Int,
    private val output: (FloatArray, Int) -> Unit,
) {
    init {
        require(sourceRate > 0 && targetRate > 0)
    }

    private val kernel = if (targetRate < sourceRate) lowPassKernel() else null
    private val half = if (kernel == null) 0 else TAPS / 2
    private val history = FloatArray(128)
    private val block = FloatArray(4096)
    private var blockSize = 0
    private var inputCount = 0L
    private var outputCount = 0L
    private var finished = false

    fun append(samples: FloatArray, size: Int = samples.size) {
        check(!finished)
        require(size in 0..samples.size)
        for (index in 0 until size) {
            history[inputCount.toInt() and (history.size - 1)] = samples[index]
            inputCount++
            emitReady(flush = false)
        }
    }

    fun finish() {
        check(!finished)
        emitReady(flush = true)
        flushBlock()
        finished = true
    }

    private fun emitReady(flush: Boolean) {
        val finalCount = if (inputCount == 0L) 0L else
            (inputCount * targetRate / sourceRate).coerceAtLeast(1L)
        while (true) {
            if (flush && outputCount >= finalCount) return
            // Integer arithmetic avoids accumulating fractional phase drift.
            val numerator = outputCount * sourceRate
            val base = numerator / targetRate
            val remainder = numerator % targetRate
            val future = base + half + if (remainder == 0L) 0 else 1
            if (!flush && future >= inputCount) return
            val first = filtered(base)
            val value = if (remainder == 0L) first else {
                val second = if (base + 1 < inputCount) filtered(base + 1) else first
                first + (second - first) * (remainder.toFloat() / targetRate)
            }
            block[blockSize++] = value
            outputCount++
            if (blockSize == block.size) flushBlock()
        }
    }

    private fun filtered(index: Long): Float {
        val coefficients = kernel ?: return sample(index)
        var sum = 0f
        for (tap in coefficients.indices) sum += sample(index + tap - half) * coefficients[tap]
        return sum
    }

    private fun sample(index: Long): Float {
        if (index < 0L || index >= inputCount) return 0f
        check(inputCount - index <= history.size) { "resampler history underrun" }
        return history[index.toInt() and (history.size - 1)]
    }

    private fun flushBlock() {
        if (blockSize == 0) return
        output(block, blockSize)
        blockSize = 0
    }

    private fun lowPassKernel(): FloatArray {
        val cutoff = 0.9 * targetRate.toDouble() / sourceRate
        val coefficients = FloatArray(TAPS)
        var sum = 0.0
        for (index in coefficients.indices) {
            val x = index - TAPS / 2
            val sinc = if (abs(x.toDouble()) < 1e-6) cutoff else sin(PI * cutoff * x) / (PI * x)
            val window = 0.54 - 0.46 * cos(2.0 * PI * index / (TAPS - 1))
            coefficients[index] = (sinc * window).toFloat()
            sum += coefficients[index]
        }
        for (index in coefficients.indices) coefficients[index] = (coefficients[index] / sum).toFloat()
        return coefficients
    }

    private companion object {
        const val TAPS = 63
    }
}
