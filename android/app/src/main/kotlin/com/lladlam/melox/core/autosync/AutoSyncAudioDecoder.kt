package com.lladlam.melox.core.autosync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.os.Process
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

/** Decodes off the playback thread, streaming anti-aliased mono PCM to a temporary file. */
internal object AutoSyncAudioDecoder {
    private const val TAG = "MeloXAutoSync"

    suspend fun decode(
        file: File,
        targetSampleRate: Int = 16_000,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
        timeoutMs: Long = 20_000L,
        shouldContinue: (suspend () -> Boolean)? = null,
        mp3FramesPerBuffer: Int = 16,
    ): AutoSyncPcmFile {
        require(targetSampleRate > 0 && startMs >= 0L && endMs > startMs)
        require(mp3FramesPerBuffer in 1..16)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val temporary = File.createTempFile("autosync-pcm-", ".tmp", file.parentFile)
        var published = false
        val worker = HandlerThread("MeloXAutoSyncDecode", Process.THREAD_PRIORITY_DEFAULT)
        try {
            worker.start()
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("AutoSync: source has no audio track")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("audio MIME unavailable")
            // Android's MP3 seek estimates do not preserve a sample-exact origin
            // (even with preroll). Decode its intro without retaining it; other
            // indexed containers can seek with a short decoder warm-up.
            if (startMs > 0L && mime != "audio/mpeg") extractor.seekTo((startMs - 1000L).coerceAtLeast(0L) * 1000L,
                MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            // AOSP C2SoftMp3Dec::calculateOutSize/process explicitly loops over
            // all MP3 frames in a buffer. Other codecs keep one access unit per
            // input: their contracts need not support concatenated packets.
            val batchSize = if (mime == "audio/mpeg" && decoder.name == "c2.android.mp3.decoder")
                mp3FramesPerBuffer else 1
            val packet = if (batchSize > 1) ByteBuffer.allocate(8192) else null
            var inputEnded = false
            var sourceRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var samples = 0
            val startedAt = SystemClock.elapsedRealtime()
            var lastReportAt = startedAt
            var feedNs = 0L
            var convertNs = 0L
            var readNs = 0L
            var queueNs = 0L
            var advanceNs = 0L
            var inputBuffers = 0
            val completion = CompletableDeferred<Unit>()

            FileOutputStream(temporary).channel.use { output ->
                val bytes = ByteBuffer.allocate(4096 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                var resampler: AutoSyncStreamingResampler? = null
                val converter = PcmConverter()
                fun resampler(): AutoSyncStreamingResampler = resampler ?: AutoSyncStreamingResampler(
                    sourceRate, targetSampleRate,
                ) { block, size ->
                    bytes.clear()
                    bytes.asFloatBuffer().put(block, 0, size)
                    bytes.limit(size * Float.SIZE_BYTES)
                    while (bytes.hasRemaining()) output.write(bytes)
                    samples += size
                }.also { resampler = it }

                fun reportProgress() {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReportAt >= 5000L) {
                        Log.i(TAG, "decoding ${samples * 1000L / targetSampleRate}ms audio in ${now - startedAt}ms " +
                            "feed=${feedNs / 1_000_000} read=${readNs / 1_000_000} queue=${queueNs / 1_000_000} " +
                            "advance=${advanceNs / 1_000_000} convert=${convertNs / 1_000_000}ms buffers=$inputBuffers async")
                        lastReportAt = now
                    }
                }
                decoder.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        if (inputEnded || completion.isCompleted) return
                        try {
                            val feedAt = System.nanoTime()
                            val buffer = checkNotNull(codec.getInputBuffer(index))
                            buffer.clear()
                            val pts = extractor.sampleTime
                            var size = 0
                            for (frame in 0 until batchSize) {
                                val readAt = System.nanoTime()
                                val framePts = extractor.sampleTime
                                // Feed past the requested end to flush decoder
                                // delay; only cropped output enters the PCM file.
                                val read = if (framePts < 0L ||
                                    (endMs != Long.MAX_VALUE && framePts >= endMs * 1000L + 500_000L)) -1
                                    else extractor.readSampleData(packet ?: buffer, if (packet == null) size else 0)
                                readNs += System.nanoTime() - readAt
                                if (read < 0) break
                                if (size + read > buffer.capacity()) {
                                    check(size > 0) { "compressed frame exceeds codec input capacity" }
                                    break // Do not advance; this packet belongs to the next buffer.
                                }
                                if (packet != null) {
                                    packet.position(0)
                                    packet.limit(read)
                                    buffer.put(packet)
                                    packet.clear()
                                }
                                size += read
                                val advanceAt = System.nanoTime()
                                extractor.advance()
                                advanceNs += System.nanoTime() - advanceAt
                            }
                            val queueAt = System.nanoTime()
                            if (size == 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, pts, 0)
                                queueNs += System.nanoTime() - queueAt
                                inputBuffers++
                            }
                            feedNs += System.nanoTime() - feedAt
                        } catch (error: Throwable) {
                            completion.completeExceptionally(error)
                        }
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        try {
                            val newRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sourceRate)
                            check(resampler == null || newRate == sourceRate) { "audio sample rate changed mid-stream" }
                            sourceRate = newRate
                            channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            Log.i(TAG, "decoder=${codec.name} mime=$mime rate=$sourceRate channels=$channels " +
                                "encoding=$encoding range=$startMs..${endMs}ms streaming->$targetSampleRate batch=$batchSize async")
                        } catch (error: Throwable) {
                            completion.completeExceptionally(error)
                        }
                    }

                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (completion.isCompleted) return
                        try {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val convertAt = System.nanoTime()
                                    converter.consume(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, encoding,
                                        sourceRate, info.presentationTimeUs, startMs, endMs, resampler())
                                    convertNs += System.nanoTime() - convertAt
                                }
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                            reportProgress()
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                resampler?.finish()
                                completion.complete(Unit)
                            }
                        } catch (error: Throwable) {
                            completion.completeExceptionally(error)
                        }
                    }

                    override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                        completion.completeExceptionally(error)
                    }
                }, Handler(worker.looper))
                try {
                    decoder.configure(inputFormat, null, null, 0)
                    decoder.start()
                    withTimeout(timeoutMs) {
                        while (!completion.isCompleted) {
                            currentCoroutineContext().ensureActive()
                            if (shouldContinue?.invoke() == false) {
                                throw CancellationException("AutoSync track changed or disabled")
                            }
                            withTimeoutOrNull(500L) { completion.await() }
                        }
                        completion.await()
                    }
                } finally {
                    // Stop callbacks before the output channel / extractor are closed.
                    runCatching { decoder.stop() }
                    runCatching { decoder.release() }
                    codec = null
                    worker.quitSafely()
                    worker.join()
                }
            }
            Log.i(TAG, "PCM ready samples=$samples bytes=${temporary.length()} total=${SystemClock.elapsedRealtime() - startedAt}ms " +
                "feed=${feedNs / 1_000_000} read=${readNs / 1_000_000} queue=${queueNs / 1_000_000} " +
                "advance=${advanceNs / 1_000_000} convert=${convertNs / 1_000_000}ms buffers=$inputBuffers async")
            return AutoSyncPcmFile(temporary, samples, targetSampleRate).also { published = true }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            if (!published) temporary.delete()
            worker.quitSafely()
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    /** Reuses block buffers; never allocates arrays proportional to the song duration. */
    private class PcmConverter {
        private var shorts = ShortArray(0)
        private var floats = FloatArray(0)
        private var mono = FloatArray(0)
        private var framesWritten = 0L
        private val silence = FloatArray(4096)

        fun consume(
            buffer: ByteBuffer,
            channels: Int,
            encoding: Int,
            rate: Int,
            ptsUs: Long,
            startMs: Long,
            endMs: Long,
            resampler: AutoSyncStreamingResampler,
        ) {
            require(channels > 0)
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> error("unsupported PCM encoding $encoding")
            }
            val frames = buffer.remaining() / (bytesPerSample * channels)
            // MediaExtractor seeks to a sync frame BEFORE the requested range.
            // Crop using output PTS, not input packets, to preserve the lyric origin.
            var first = frameAt(startMs * 1000L - ptsUs, rate).coerceIn(0L, frames.toLong()).toInt()
            val last = if (endMs == Long.MAX_VALUE) frames else
                frameAt(endMs * 1000L - ptsUs, rate).coerceIn(0L, frames.toLong()).toInt()
            if (last <= first) return
            // Gapless trimming can advance the first output PTS. Keep the PCM
            // file anchored to startMs rather than silently shifting all audio
            // earlier. Likewise preserve genuine PTS gaps and discard overlap.
            val origin = ((ptsUs - startMs * 1000L) * rate.toDouble() / 1_000_000.0).roundToLong()
            val nextFrame = origin + first
            if (nextFrame > framesWritten) {
                var gap = nextFrame - framesWritten
                while (gap > 0L) {
                    val size = minOf(gap, silence.size.toLong()).toInt()
                    resampler.append(silence, size)
                    framesWritten += size
                    gap -= size
                }
            } else if (nextFrame < framesWritten) {
                first += minOf(framesWritten - nextFrame, (last - first).toLong()).toInt()
            }
            if (last <= first) return
            val values = frames * channels
            if (mono.size < last - first) mono = FloatArray(last - first)
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    if (shorts.size < values) shorts = ShortArray(values)
                    buffer.asShortBuffer().get(shorts, 0, values)
                    for (frame in first until last) {
                        var sum = 0f
                        for (channel in 0 until channels) sum += shorts[frame * channels + channel]
                        mono[frame - first] = sum / (32768f * channels)
                    }
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    if (floats.size < values) floats = FloatArray(values)
                    buffer.asFloatBuffer().get(floats, 0, values)
                    for (frame in first until last) {
                        var sum = 0f
                        for (channel in 0 until channels) sum += floats[frame * channels + channel].coerceIn(-1f, 1f)
                        mono[frame - first] = sum / channels
                    }
                }
                else -> {
                    for (frame in first until last) {
                        var sum = 0f
                        for (channel in 0 until channels) sum += (buffer.get(frame * channels + channel).toInt() and 255) - 128
                        mono[frame - first] = sum / (128f * channels)
                    }
                }
            }
            resampler.append(mono, last - first)
            framesWritten += last - first
        }

        private fun frameAt(deltaUs: Long, rate: Int): Long =
            // Buffer PTS is rounded to integer microseconds. Rounding back to
            // the sample grid avoids dropping a frame solely due to that loss.
            if (deltaUs <= 0L) 0L else (deltaUs * rate + 500_000L) / 1_000_000L
    }
}
