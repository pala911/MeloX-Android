package com.lladlam.melox.core.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Applies a `BSDIFF40` patch (the format produced by `bsdiff`, as shipped by the
 * `bsdiff4` Python package) to a file on disk.
 *
 * Only the *apply* half lives on the device: the release pipeline generates the
 * patch from the previously published APK and the new one (`tools/make_apk_patch.sh`).
 * The patch header declares the exact compressed length of each of its three
 * bzip2 streams, so the streams are sliced rather than parsed.
 *
 * The caller must verify the SHA-256 of the produced file against the published
 * APK hash before trusting it; this class only guarantees a faithful application
 * of the patch.
 */
internal object BsPatch {

    private val Magic = "BSDIFF40".toByteArray(Charsets.US_ASCII)

    fun apply(oldFile: File, patchFile: File, newFile: File) {
        val patch = patchFile.readBytes()
        require(patch.size >= 32 && patch.copyOfRange(0, 8).contentEquals(Magic)) {
            "not a BSDIFF40 patch"
        }
        val controlLength = readOffset(patch, 8)
        val diffLength = readOffset(patch, 16)
        val newSize = readOffset(patch, 24)
        require(controlLength >= 0 && diffLength >= 0 && newSize >= 0) { "corrupt patch header" }
        val controlStart = 32L
        val diffStart = controlStart + controlLength
        val extraStart = diffStart + diffLength
        require(extraStart <= patch.size) { "corrupt patch lengths" }

        val control = decompress(patch, controlStart, controlLength.toInt())
        val diff = decompress(patch, diffStart, diffLength.toInt())
        val extra = decompress(patch, extraStart, (patch.size - extraStart).toInt())

        RandomAccessFile(oldFile, "r").use { old ->
            newFile.outputStream().buffered(1 shl 16).use { out ->
                var controlPosition = 0
                var diffPosition = 0L
                var extraPosition = 0L
                var oldPosition = 0L
                var written = 0L
                val buffer = ByteArray(1 shl 16)
                while (written < newSize) {
                    val copyLength = readOffset(control, controlPosition)
                    val insertLength = readOffset(control, controlPosition + 8)
                    val seek = readOffset(control, controlPosition + 16)
                    controlPosition += 24
                    require(copyLength >= 0 && insertLength >= 0) { "corrupt control triple" }
                    require(written + copyLength + insertLength <= newSize) { "patch overruns output" }
                    require(diffPosition + copyLength <= diff.size.toLong()) { "patch diff is short" }
                    require(extraPosition + insertLength <= extra.size.toLong()) { "patch extra is short" }
                    val copyCount = copyLength.toInt()
                    val insertCount = insertLength.toInt()

                    // copyCount bytes of (old + diff)
                    var remaining = copyLength
                    var chunkStart = 0L
                    while (remaining > 0) {
                        val take = minOf(remaining, buffer.size.toLong()).toInt()
                        old.seek(oldPosition + chunkStart)
                        val read = old.read(buffer, 0, take)
                        for (index in 0 until take) {
                            val oldByte = if (index < read) buffer[index].toInt() else 0
                            val diffByte = diff[diffPosition.toInt() + chunkStart.toInt() + index].toInt()
                            buffer[index] = (oldByte + diffByte).toByte()
                        }
                        out.write(buffer, 0, take)
                        remaining -= take
                        chunkStart += take
                    }
                    oldPosition += copyCount.toLong()
                    diffPosition += copyCount.toLong()
                    written += copyCount.toLong()

                    out.write(extra, extraPosition.toInt(), insertCount)
                    extraPosition += insertCount.toLong()
                    written += insertCount.toLong()

                    oldPosition += seek
                    if (oldPosition < 0L) oldPosition = 0L
                }
            }
        }
    }

    /** `offtin`: little-endian sign-magnitude 64-bit. */
    private fun readOffset(bytes: ByteArray, position: Int): Long {
        require(position + 8 <= bytes.size) { "patch is truncated" }
        var value = (bytes[position + 7].toInt() and 0x7F).toLong()
        for (index in 6 downTo 0) {
            value = (value shl 8) or (bytes[position + index].toLong() and 0xFF)
        }
        return if (bytes[position + 7].toInt() and 0x80 != 0) -value else value
    }

    private fun decompress(patch: ByteArray, start: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        return BZip2CompressorInputStream(
            ByteArrayInputStream(patch, start.toInt(), length),
            true,
        ).use { it.readBytes() }
    }
}
