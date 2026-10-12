package com.lladlam.melox.core.autosync

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Temporary 16 kHz mono PCM. Only one lyric window is materialised on the heap. */
internal class AutoSyncPcmFile(
    private val file: File,
    val sampleCount: Int,
    val sampleRate: Int,
) : AutoCloseable {
    private val channel = RandomAccessFile(file, "r").channel

    fun readWindow(startSample: Int, endSample: Int): FloatArray {
        require(startSample in 0..sampleCount && endSample in startSample..sampleCount)
        val result = FloatArray(endSample - startSample)
        val bytes = ByteBuffer.allocate(minOf(result.size, 4096) * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        channel.position(startSample.toLong() * Float.SIZE_BYTES)
        var offset = 0
        while (offset < result.size) {
            val size = minOf(result.size - offset, 4096)
            bytes.clear()
            bytes.limit(size * Float.SIZE_BYTES)
            while (bytes.hasRemaining()) check(channel.read(bytes) >= 0) { "truncated AutoSync PCM" }
            bytes.flip()
            bytes.asFloatBuffer().get(result, offset, size)
            offset += size
        }
        return result
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            file.delete()
        }
    }
}
