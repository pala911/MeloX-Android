package com.lladlam.melox.playback

import android.content.Context
import android.util.Log
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.audio.MusicQuality
import com.lladlam.melox.core.provider.lxuser.LxUserSourceStore
import com.lladlam.melox.core.network.NeteaseSearchClient
import com.lladlam.melox.core.lyrics.LrcLyricsParser
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class LxUserPlaybackResult(
    val sourceId: String,
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    /**
     * Tier measured from the resolved link, when it could be determined. Callers
     * use it to report what is actually playing, because the quality the source
     * was *asked* for says nothing about the file it hands back.
     */
    val quality: MusicQuality? = null,
)

/** Resolves a song through locally installed LX Music user API scripts. */
class LxUserPlaybackResolver(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun cacheIdentity(): String = LxUserSourceStore.list(appContext).joinToString("|") { it.id }

    internal fun resolveArtwork(track: MusicTrack): String? =
        resolveAction(track, "pic")?.let { value ->
            when (value) {
                is String -> value
                is Map<*, *> -> sequenceOf("url", "picUrl", "artworkUrl")
                    .mapNotNull { value[it]?.toString() }.firstOrNull { it.startsWith("http") }
                else -> null
            }
        }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    internal fun resolveLyrics(track: MusicTrack): LyricsDocument? {
        val value = resolveAction(track, "lyric") ?: return null
        val data = (value as? Map<*, *>) ?: return (value as? String)?.let(LrcLyricsParser::parse)
        val lyric = data["lyric"]?.toString().orEmpty().ifBlank { data["lrc"]?.toString().orEmpty() }
        if (lyric.isBlank()) return null
        return LrcLyricsParser.parse(
            lrc = lyric,
            translation = data["tlyric"]?.toString().orEmpty().ifBlank { data["translation"]?.toString().orEmpty() },
            romanization = data["rlyric"]?.toString().orEmpty().ifBlank { data["romalrc"]?.toString().orEmpty() },
        )
    }

    private fun resolveAction(track: MusicTrack, action: String): Any? {
        val sourceCode = when (track.id.source) {
            MusicSource.QQMusic -> "tx"
            MusicSource.Kugou -> "kg"
            MusicSource.Kuwo -> "kw"
            MusicSource.Netease -> "wy"
            else -> return null
        }
        val song = standardMusicInfo(track, sourceCode, AudioQualityTier.Standard.toLxQuality())
        for (record in LxUserSourceStore.list(appContext)) {
            val script = LxUserSourceStore.script(appContext, record.id) ?: continue
            val result = runCatching {
                LxUserRuntimeSession.withRuntime(record.id, script) { runtime ->
                    if (!runtime.supports(sourceCode, action)) {
                        Log.d(TAG, "LX $action skipped script=${record.id} source=$sourceCode")
                        null
                    } else {
                        runtime.callAction(action, song + mapOf(
                            "source" to sourceCode,
                            "type" to "128k",
                            "musicInfo" to song,
                        ))
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "LX $action failed script=${record.id} detail=${error.safeLogMessage()}")
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    internal fun resolve(
        songId: Long,
        title: String,
        artist: String,
        durationMs: Long?,
        quality: AudioQualityTier,
        hasPlayableFallback: Boolean = false,
        urgent: Boolean = true,
    ): LxUserPlaybackResult? {
        return resolve(
            MusicTrack(
                id = com.lladlam.melox.core.music.model.MusicResourceId(MusicSource.Netease, songId.toString()),
                title = title,
                artists = listOf(com.lladlam.melox.core.music.model.MusicArtistRef(name = artist)),
                durationMs = durationMs,
            ),
            quality,
            hasPlayableFallback,
            urgent,
        )
    }

    /**
     * @param hasPlayableFallback true when the caller already holds a complete stream
     *   it can fall back to. Hunting for a better tier is then a bonus, so the search
     *   gets a short leash; when the only alternative is a 30 s trial clip it is worth
     *   waiting much longer.
     * @param urgent false for background work such as prefetching, so a track the user
     *   actually tapped is not queued behind upcoming ones.
     */
    internal fun resolve(
        track: MusicTrack,
        quality: AudioQualityTier,
        hasPlayableFallback: Boolean = false,
        urgent: Boolean = true,
    ): LxUserPlaybackResult? {
        val sourceCode = when (track.id.source) {
            MusicSource.QQMusic -> "tx"
            MusicSource.Kugou -> "kg"
            MusicSource.Kuwo -> "kw"
            MusicSource.Netease -> null
            else -> return null
        }
        val title = track.title
        val artist = track.artistText
        // Legacy `melox://song/<id>` URIs may only carry a song id. Those can still be
        // resolved through the `wy` source, but matching by name across every source
        // would be guesswork, so only `wy` is attempted in that case.
        val candidateSources = if (title.isBlank() || artist.isBlank()) {
            listOf("wy")
        } else {
            // The track's own source is the only one that can be matched by id, so
            // it is worth spending before any name-based lookup.
            listOfNotNull(sourceCode) + LX_SOURCES.filterNot { it == sourceCode }
        }
        val resourceId = track.id.value
        val lxQuality = quality.toLxQuality()
        val song = standardMusicInfo(track, sourceCode, lxQuality)
        Log.d(TAG, "LX resolve start provider=${track.id.source.storageValue} id=${resourceId.take(8)} quality=$lxQuality " +
            "source=$sourceCode title=${title.take(40)} artist=${artist.take(40)}")
        val actionArgs = mapOf<String, Any?>(
            "id" to resourceId,
            "songId" to resourceId,
            "mid" to resourceId,
            "songmid" to resourceId,
            "hash" to resourceId,
            "name" to title,
            "title" to title,
            "artist" to artist,
            "singer" to artist,
            "duration" to track.durationMs?.div(1_000L),
            "durationMs" to track.durationMs,
            "quality" to lxQuality,
            "musicInfo" to song,
        )
        for (record in LxUserSourceStore.list(appContext)) {
            val script = LxUserSourceStore.script(appContext, record.id) ?: continue
            var phase = "load"
            val result: LxUserPlaybackResult? = runCatching {
                LxUserRuntimeSession.withRuntime(record.id, script, urgent) { runtime ->
                    phase = "request"
                    val start = android.os.SystemClock.elapsedRealtime()
                    val deadline = start + if (hasPlayableFallback) {
                        RESOLVE_BUDGET_WITH_FALLBACK_MS
                    } else {
                        RESOLVE_BUDGET_MS
                    }
                    // Quality first, source second. Every source gets a chance at the
                    // best quality before anything settles for a lower one; iterating
                    // the other way round let the first source's 128k link win over
                    // another source's lossless one.
                    var bestUrl: String? = null
                    var bestRank = -1
                    var probes = 0
                    // A source that already handed back a link has shown us its best;
                    // asking it again one tier lower almost always returns the same
                    // file, so remember it and skip the repeat.
                    val answered = mutableSetOf<String>()
                    for (requestedQuality in lxQualityFallbacks(lxQuality)) {
                        val need = lxQualityRank(requestedQuality)
                        for (source in candidateSources) {
                            if (source in answered) continue
                            if (!runtime.supports(source, "musicUrl")) continue
                            // Once something playable is in hand, stop hunting much
                            // sooner: the user is waiting on a song, not on a tier.
                            val softDeadline = if (bestUrl == null) deadline else start + FALLBACK_BUDGET_MS
                            if (android.os.SystemClock.elapsedRealtime() > softDeadline) {
                                Log.w(TAG, "LX budget exhausted script=${record.id} quality=$requestedQuality source=$source best=$bestRank")
                                return@withRuntime bestUrl?.let {
                                    LxUserPlaybackResult(record.id, it, quality = rankToMusicQuality(bestRank))
                                }
                            }
                            LxUserRuntimeSession.awaitRequestSlot(MIN_REQUEST_GAP_MS)
                            val sourceQuality = runtime.qualityFor(source, requestedQuality)
                            // Scripts read `musicInfo.source` and `musicInfo.quality`, so
                            // the nested music info has to describe the source and the
                            // quality currently being tried - not the top one.
                            val sourceSong = standardMusicInfo(track, source, requestedQuality)
                            Log.d(TAG, "LX candidate script=${record.id} source=$source requested=$requestedQuality actual=$sourceQuality")
                            // Give the action whatever is left of this song's budget
                            // rather than a fixed slice: a source that answers slowly
                            // should still get its answer in, but it may not overshoot
                            // the time the user is willing to wait.
                            val actionBudget = (softDeadline - android.os.SystemClock.elapsedRealtime())
                                .coerceAtLeast(MIN_ACTION_TIMEOUT_MS)
                            val value = runCatching {
                                runtime.callAction(
                                    "musicUrl",
                                    sourceSong + mapOf(
                                        "source" to source,
                                        "type" to sourceQuality,
                                        "musicInfo" to sourceSong,
                                    ),
                                    actionBudget,
                                )
                            }.onFailure {
                                Log.w(TAG, "LX candidate failed script=${record.id} source=$source quality=$sourceQuality detail=${it.safeLogMessage()}")
                            }.getOrNull()
                            val url = when (value) {
                                is String -> value
                                is Map<*, *> -> value["url"]?.toString()
                                else -> null
                            }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            // Most scripts hand back the raw API body, so the tier that
                            // actually came back is usually one of these fields.
                            val reported = (value as? Map<*, *>)?.let { body ->
                                sequenceOf("quality", "type", "level", "br", "bitrate")
                                    .mapNotNull { body[it]?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                                    .firstOrNull()
                            }
                            // Most scripts hand back a bare URL with no quality field, so
                            // the requested tier says nothing about what will actually play.
                            // When the duration is known we probe the CDN (one ranged GET)
                            // and read the total size off Content-Range to derive the true
                            // bitrate. That probe hits the final CDN, not the rate-limited
                            // LX API, so it costs nothing against the "4 requests / 2s" rule.
                            val reportedRank = lxQualityRank(reported)
                            val rank = if (reportedRank >= 0 || url == null || track.durationMs == null || probes >= MAX_PROBES) {
                                reportedRank
                            } else {
                                probes++
                                probeQualityRank(url, track.durationMs, record.id)
                            }
                            Log.d(TAG, "LX candidate result script=${record.id} source=$source quality=$sourceQuality " +
                                "url=${url != null} reported=$reported rank=$rank need=$need link=${url?.take(220)}")
                            if (url == null) continue
                            answered += source
                            // Several public mirrors accept any quality you ask for
                            // and quietly serve 128k, so the requested tier is not
                            // proof of what will play. Only accept a link that is at
                            // least as good as the tier being tried, and keep the best
                            // miss around in case nothing reaches the bar.
                            //
                            // Lossless from `wy` is taken straight away: these mirrors
                            // rarely hold a 24-bit master of a track the official API
                            // only serves as a clip, and hunting one across the other
                            // platforms costs the user seconds for nothing.
                            if (rank < 0 || rank >= need || (source == "wy" && rank >= LOSSLESS_RANK)) {
                                return@withRuntime LxUserPlaybackResult(
                                    record.id,
                                    url,
                                    quality = rankToMusicQuality(rank),
                                )
                            }
                            if (rank > bestRank) {
                                bestRank = rank
                                bestUrl = url
                            }
                        }
                    }
                    bestUrl?.let { LxUserPlaybackResult(record.id, it, quality = rankToMusicQuality(bestRank)) }
                }
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "LX failed source=${track.id.source.storageValue} script=${record.id} phase=$phase " +
                        "error=${error.javaClass.simpleName} detail=${error.safeLogMessage()}",
                )
            }.getOrNull()
            if (result != null) {
                Log.i(TAG, "LX resolved source=${track.id.source.storageValue} script=${record.id} link=${result.url.take(220)}")
                return result
            }
            Log.i(TAG, "LX exhausted source=${track.id.source.storageValue} script=${record.id}")
        }
        if (track.id.source != MusicSource.Netease && title.isNotBlank()) {
            resolveViaNeteaseMatch(track, quality, urgent)?.let { return it }
        }
        Log.i(TAG, "LX unresolved source=${track.id.source.storageValue} scripts=${LxUserSourceStore.list(appContext).size}")
        return null
    }

    private fun resolveViaNeteaseMatch(
        track: MusicTrack,
        quality: AudioQualityTier,
        urgent: Boolean,
    ): LxUserPlaybackResult? {
        val query = track.title.trim()
        val candidates = runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                NeteaseSearchClient().searchSongs(query, limit = 50)
            }
        }.onFailure { Log.w(TAG, "LX Netease match search failed title=${track.title.take(40)} detail=${it.safeLogMessage()}") }
            .getOrNull().orEmpty()
        val match = candidates
            .asSequence()
            .filter { normalizeLxText(it.name) == normalizeLxText(track.title) }
            .filter { candidate -> track.durationMs == null || candidate.durationMs <= 0L || kotlin.math.abs(candidate.durationMs - track.durationMs) <= 8_000L }
            .sortedWith(compareByDescending<com.lladlam.melox.core.model.SearchSong> {
                val requestedArtist = normalizeLxText(track.artistText)
                if (requestedArtist.isNotBlank() && normalizeLxText(it.artists).contains(requestedArtist)) 1 else 0
            }.thenBy { candidate -> kotlin.math.abs(candidate.durationMs - (track.durationMs ?: candidate.durationMs)) })
            .firstOrNull()
        if (match == null) {
            Log.w(TAG, "LX Netease match not found title=${track.title.take(40)} candidates=${candidates.size}")
            return null
        }

        val neteaseTrack = MusicTrack(
            id = MusicResourceId(MusicSource.Netease, match.id.toString()),
            title = match.name,
            artists = listOf(MusicArtistRef(name = match.artists)),
            durationMs = match.durationMs,
            artworkUrl = match.artworkUrl,
            providerMetadata = ProviderTrackMetadata.Netease(match.id),
        )
        Log.i(TAG, "LX Netease match source=${track.id.source.storageValue} id=${track.id.value.take(8)} -> ${match.id}")
        return resolveNeteaseTrack(neteaseTrack, quality, urgent)
    }

    private fun resolveNeteaseTrack(
        track: MusicTrack,
        quality: AudioQualityTier,
        urgent: Boolean,
    ): LxUserPlaybackResult? {
        for (record in LxUserSourceStore.list(appContext)) {
            val script = LxUserSourceStore.script(appContext, record.id) ?: continue
            val result = runCatching {
                LxUserRuntimeSession.withRuntime(record.id, script, urgent) { runtime ->
                    val start = android.os.SystemClock.elapsedRealtime()
                    val deadline = start + RESOLVE_BUDGET_MS
                    lxQualityFallbacks(quality.toLxQuality()).asSequence().mapNotNull { requestedQuality ->
                        LxUserRuntimeSession.awaitRequestSlot(MIN_REQUEST_GAP_MS)
                        if (android.os.SystemClock.elapsedRealtime() > deadline) return@mapNotNull null
                        val sourceQuality = runtime.qualityFor("wy", requestedQuality)
                        // Same rule as the main loop: the nested music info must carry
                        // the quality being tried, otherwise the script keeps asking
                        // for the top quality and the fallbacks never happen.
                        val song = standardMusicInfo(track, "wy", requestedQuality)
                        val actionBudget = (deadline - android.os.SystemClock.elapsedRealtime())
                            .coerceAtLeast(MIN_ACTION_TIMEOUT_MS)
                        val response = runCatching {
                            runtime.callAction("musicUrl", song + mapOf(
                                "source" to "wy",
                                "type" to sourceQuality,
                                "musicInfo" to song,
                            ), actionBudget)
                        }.onFailure { Log.w(TAG, "LX Netease candidate failed quality=$sourceQuality detail=${it.safeLogMessage()}") }.getOrNull()
                        val url = when (response) {
                            is String -> response
                            is Map<*, *> -> response["url"]?.toString()
                            else -> null
                        }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        url?.let {
                            val rank = track.durationMs?.let { duration ->
                                probeQualityRank(it, duration, record.id)
                            } ?: -1
                            LxUserPlaybackResult(record.id, it, quality = rankToMusicQuality(rank))
                        }
                    }.firstOrNull()
                }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private companion object {
        /**
         * Sources to try, in order, for a track whose own source has no match.
         * Kuwo is deliberately absent: its car-head-unit endpoint answers with
         * 22-60 kbps fragments, so querying it only costs time.
         */
        val LX_SOURCES = listOf("wy", "kg", "tx", "mg")
        /**
         * Cap while the only alternative is the official trial clip. A source costs
         * 1-4 s because the scripts call a telemetry endpoint before their real
         * request, so this has to cover a couple of attempts. Giving up early here
         * leaves the user on a 30 s clip, which is worth waiting to avoid.
         */
        const val RESOLVE_BUDGET_MS = 10_000L
        /**
         * Cap when the caller already holds a complete stream. Chasing a higher tier is
         * then a bonus, and the user should not wait long for it.
         */
        const val RESOLVE_BUDGET_WITH_FALLBACK_MS = 3_000L
        /** Shorter cap once a third-party link exists and we are only chasing a better tier. */
        const val FALLBACK_BUDGET_MS = 3_000L
        /**
         * Pause between actions. Each action fires two to three upstream requests of
         * its own, so the sources' own "no more than 4 requests per 2 s" rule needs a
         * wider gap than the action count suggests; at 400 ms the mirror answered
         * `429 请求过于频繁` and the affected songs fell back to the official stream.
         */
        const val MIN_REQUEST_GAP_MS = 800L
    }
}

/** Log tag shared by the resolver class and its file-level helpers. */
private const val TAG = "MeloXThirdParty"
/** Floor for one action so a nearly exhausted budget still lets a request finish. */
private const val MIN_ACTION_TIMEOUT_MS = 1_500L
/** [lxQualityRank] bucket for a lossless (16-bit) file. */
private const val LOSSLESS_RANK = 3
/** At most this many CDN probes per resolve; each one is a single ranged GET. */
private const val MAX_PROBES = 4
/**
 * Give up on a probe after this long. The probe runs on the shared LX thread, so a
 * slow CDN would otherwise stall the next song's resolve behind it.
 */
private const val PROBE_TIMEOUT_MS = 2000

/** Maps a measured [lxQualityRank] back onto the tier the UI should show. */
private fun rankToMusicQuality(rank: Int): MusicQuality? = when (rank) {
    1 -> MusicQuality.Standard
    2 -> MusicQuality.High
    3 -> MusicQuality.Lossless
    4 -> MusicQuality.HiResolution
    else -> null
}

/**
 * Coarse bucket for "how good is this file really", shared by the requested
 * quality and whatever a source reports back: 1 = 128k, 2 = 320k,
 * 3 = lossless, 4 = hi-res. `-1` means "no idea", which callers treat as
 * acceptable so an unknown-but-playable link is never thrown away.
 */
private fun lxQualityRank(raw: String?): Int {
    val value = raw?.lowercase(Locale.ROOT) ?: return -1
    if (value.isEmpty() || value == "null" || value == "undefined") return -1
    if (value.all(Char::isDigit)) {
        val bitrate = value.toLongOrNull() ?: return -1
        return when {
            bitrate < 200_000L -> 1
            bitrate < 500_000L -> 2
            bitrate < 1_000_000L -> 3
            else -> 4
        }
    }
    return when (value) {
        "128k", "128", "standard", "l", "pq" -> 1
        "320k", "320", "exhigh", "high", "h", "hq" -> 2
        "flac", "lossless", "sq", "999", "999k" -> 3
        "flac24bit", "hires", "hi-res", "hr", "atmos", "atmos_plus",
        "master", "sky", "jyeffect", "jymaster", "zq", "super",
        -> 4
        else -> -1
    }
}

private fun lxQualityFallbacks(requested: String): List<String> = when (requested) {
    "flac24bit" -> listOf("flac24bit", "flac", "320k", "128k")
    "flac" -> listOf("flac", "320k", "128k")
    "320k" -> listOf("320k", "128k")
    else -> listOf(requested)
}

private fun normalizeLxText(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s\\p{Punct}·•，。！？、（）()\\[\\]【】]"), "")


private fun standardMusicInfo(
    track: MusicTrack,
    sourceCode: String?,
    quality: String,
): Map<String, Any?> {
    val durationSeconds = track.durationMs?.coerceAtLeast(0L)?.div(1_000L)
    val interval = durationSeconds?.let { seconds ->
        String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
    val metadata = when (val provider = track.providerMetadata) {
        is com.lladlam.melox.core.music.model.ProviderTrackMetadata.QQMusic -> mapOf(
            // LX's canonical model keeps QQ's mid, numeric id and media mid
            // separately. User sources rely on all three fields.
            "songId" to track.id.value,
            "id" to (provider.numericSongId ?: 0L),
            "strMediaMid" to (provider.mediaMid ?: track.id.value),
            "albumMid" to (track.album?.id?.value ?: ""),
        )
        is com.lladlam.melox.core.music.model.ProviderTrackMetadata.Kugou -> mapOf(
            "songId" to track.id.value,
            "hash" to provider.hash,
            "albumId" to provider.albumId,
        )
        is com.lladlam.melox.core.music.model.ProviderTrackMetadata.Kuwo -> mapOf(
            "songId" to provider.mid,
        )
        else -> mapOf("songId" to track.id.value)
    } + mapOf(
        "qualitys" to listOf(quality),
        "_qualitys" to emptyMap<String, Any?>(),
        "albumName" to (track.album?.name ?: ""),
        "picUrl" to track.artworkUrl,
    )
    return mapOf(
        "id" to track.id.value,
        "name" to track.title,
        "singer" to track.artistText,
        "artist" to track.artistText,
        "source" to sourceCode,
        "interval" to interval,
        "meta" to metadata,
        "songId" to track.id.value,
        "songmid" to track.id.value,
        "mid" to track.id.value,
        "hash" to track.id.value,
        "title" to track.title,
        "duration" to durationSeconds,
        "durationMs" to track.durationMs,
        "quality" to quality,
    )
}

private fun AudioQualityTier.toLxQuality(): String = when (this) {
    AudioQualityTier.Standard -> "128k"
    AudioQualityTier.High -> "320k"
    AudioQualityTier.Lossless -> "flac"
    AudioQualityTier.HiResolution, AudioQualityTier.Immersive, AudioQualityTier.Master -> "flac24bit"
}

private fun Throwable.safeLogMessage(): String = message.orEmpty()
    .replace(Regex("https?://\\S+"), "<url>")
    .replace(Regex("(?i)(apikey|api_key|token|key)=([^&\\s]+)"), "$1=<redacted>")
    .replace('\n', ' ')
    .take(240)
    .ifBlank { "none" }

/**
 * Derives the real quality of a resolved link by asking the CDN for its total
 * size (a single `Range: bytes=0-0` GET, which returns the full length in
 * `Content-Range` without downloading the audio) and dividing by the track
 * duration. Returns [lxQualityRank]'s bucket, or `-1` when the size cannot be
 * determined or the probe times out.
 */
private fun probeQualityRank(url: String, durationMs: Long, scriptId: String): Int {
    try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-0")
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        conn.connect()
        val total = run {
            val cr = conn.getHeaderField("Content-Range")
            if (!cr.isNullOrBlank() && cr.contains('/')) {
                cr.substring(cr.lastIndexOf('/') + 1).toLongOrNull()
            } else {
                conn.contentLengthLong.takeIf { it > 0L }
            }
        }
        conn.disconnect()
        if (total == null || total <= 0L) return -1
        val seconds = (durationMs.coerceAtLeast(1L) / 1000L).coerceAtLeast(1L)
        val bitrate = (total * 8L) / seconds
        Log.d(TAG, "LX probe script=$scriptId total=$total bitrate=$bitrate rank=${lxQualityRank(bitrate.toString())}")
        return lxQualityRank(bitrate.toString())
    } catch (e: Throwable) {
        Log.w(TAG, "LX probe failed script=$scriptId detail=${e.safeLogMessage()}")
        return -1
    }
}
