package com.lladlam.melox.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.lladlam.melox.core.audio.MusicQuality
import com.lladlam.melox.core.audio.MusicQualityRuntime
import com.lladlam.melox.core.audio.NeteaseQualityClient
import com.lladlam.melox.core.audio.NeteasePlaybackUnavailableException
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.network.NeteaseSearchClient
import com.lladlam.melox.core.music.provider.PlaybackAccountStore
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class NeteasePlaybackResolver(
    private val cookieProvider: () -> String = { "" },
    @Suppress("UNUSED_PARAMETER")
    private val client: NeteaseSearchClient = NeteaseSearchClient(cookieProvider = cookieProvider),
    private val localSourceProvider: (Long) -> Uri? = { null },
    private val providerLocalSourceProvider: (MusicResourceId) -> Uri? = { null },
    private val crossProviderFallback: CrossProviderPlaybackFallbackResolver? = null,
    private val chkszPlayback: ChkszPlaybackResolver? = null,
    private val lxUserPlayback: LxUserPlaybackResolver? = null,
    private val providerPlaybackEnabled: (com.lladlam.melox.core.music.model.MusicSource) -> Boolean = { true },
    private val thirdPartySourcesEnabled: () -> Boolean = { true },
) : ResolvingDataSource.Resolver {
    private data class ResolveKey(
        val songId: Long,
        val quality: MusicQuality,
        val cookieHeader: String,
        val fallbackIdentity: String,
        val metadataIdentity: String,
    )
    private data class ResolvedRequest(
        val uri: Uri,
        val headers: Map<String, String> = emptyMap(),
        val expiresAtEpochMs: Long? = null,
        val cacheIdentity: String = "netease",
        /**
         * True when this is a stand-in that a later attempt may improve - currently a
         * trial clip the third-party sources could not replace. Caching it would make
         * one transient failure stick for the rest of the session.
         */
        val provisional: Boolean = false,
    )

    private val cacheLock = Any()
    private val resolvedUris = object : LinkedHashMap<ResolveKey, ResolvedRequest>(MAX_RESOLVED_URIS, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ResolveKey, ResolvedRequest>?): Boolean =
            size > MAX_RESOLVED_URIS
    }
    private val inFlight = ConcurrentHashMap<ResolveKey, CompletableFuture<ResolvedRequest>>()
    private val qualityClient = NeteaseQualityClient(cookieProvider = cookieProvider)

    @Volatile
    private var providerDelegate: ProviderPlaybackResolver? = null

    /**
     * Resolves the same source used by ExoPlayer for offline analysis. Keeping
     * this path in the resolver guarantees that AutoMix never analyses a
     * different quality (or a stale anonymous URL) from the playing deck.
     */
    fun resolveSongUri(
        songId: Long,
        quality: MusicQuality = MusicQualityRuntime.selected,
    ): Uri = resolveSongRequest(songId, quality, fallbackRequest = null, urgent = false).uri

    private fun resolveSongRequest(
        songId: Long,
        quality: MusicQuality,
        fallbackRequest: CrossProviderFallbackRequest?,
        urgent: Boolean,
    ): ResolvedRequest {
        localSourceProvider(songId)?.let {
            PlaybackStageRuntime.record(songId.toString(), PlaybackStageRuntime.LabelLocal)
            return ResolvedRequest(it)
        }
        val cookieHeader = cookieProvider()
        val key = ResolveKey(
            songId = songId,
            quality = quality,
            cookieHeader = cookieHeader,
            fallbackIdentity = crossProviderFallback?.cacheIdentity().orEmpty(),
            metadataIdentity = fallbackRequest?.let {
                "${it.title}\u001f${it.artist}\u001f${it.durationMs.orZero()}"
            }.orEmpty(),
        )
        cached(key)?.let { return it }
        val pending = CompletableFuture<ResolvedRequest>()
        val existing = inFlight.putIfAbsent(key, pending)
        if (existing != null) {
            if (existing.isCompletedExceptionally) {
                inFlight.remove(key, existing)
            } else {
                return runCatching { existing.get(20L, TimeUnit.SECONDS) }
                    .getOrElse { throw IOException("Unable to resolve playback source", it.cause ?: it) }
            }
        }
        return try {
            val resolved = try {
                // Official first, quality first: the Netease URL wins when it is a
                // complete stream at (or above) the quality the user picked. Otherwise
                // the third-party sources (LX then CHKSZ) and the cross-provider pool
                // (bilibili) get a chance - a candidate meeting the bar wins with the
                // third-party side preferred, and when neither meets it the higher
                // actual quality is compared.
                val source = qualityClient.playbackSourceBlocking(
                    songId = songId,
                    requestedQuality = quality,
                )
                if (quality == MusicQualityRuntime.selected) {
                    CrossProviderPlaybackRuntime.clear(songId)
                }
                val actual = source.quality
                val belowRequested = actual == null || actual.ordinal < quality.ordinal
                if (!source.isPreview && !belowRequested) {
                    ResolvedRequest(Uri.parse(source.url)).also {
                        PlaybackStageRuntime.record(songId.toString(), PlaybackStageRuntime.LabelNetease)
                    }
                } else {
                    Log.i(
                        TAG,
                        "Official source insufficient songId=$songId preview=${source.isPreview} " +
                            "actual=${actual?.apiLevel} requested=${quality.apiLevel}, trying third-party",
                    )
                    // A preview is worthless, so wait on it; a complete stream below
                    // the bar is only worth a brief look for something better.
                    val playableFallback = !source.isPreview
                    var thirdParty = resolveThirdParty(
                        songId,
                        quality,
                        fallbackRequest,
                        hasPlayableFallback = playableFallback,
                        urgent = urgent,
                    )
                    // The public mirrors answer `429 请求过于频繁` when several songs
                    // resolve at once, so one retry after a pause recovers songs that
                    // would otherwise be left on the trial clip.
                    if (thirdParty == null && source.isPreview) {
                        Thread.sleep(PREVIEW_RETRY_PAUSE_MS)
                        Log.i(TAG, "Official answer is a trial clip and third-party found nothing, retrying songId=$songId")
                        thirdParty = resolveThirdParty(songId, quality, fallbackRequest, urgent = urgent)
                    }
                    // bilibili only matters while the third-party side cannot satisfy
                    // the bar; a qualifying third-party result wins outright.
                    val fallback = if (thirdParty == null || !thirdParty.meetsRequested(quality)) {
                        crossProviderCandidate(songId, quality, fallbackRequest)
                    } else {
                        null
                    }
                    val chosen = selectCandidate(quality, thirdParty, fallback)
                    // 官方给的是完整流、且它的实际档已知时，候选必须不比官方差才换源 ——
                    // 否则「官方优先」名不副实：bilibili 在跨源内部豁免音质门（Hi-Res 请求
                    // 下它最高只能给 Lossless），三方关掉时会无竞争地把更好的官方流换掉。
                    // 官方是试听片段、或官方档未知时不做这个比较（片段本来就不可用）。
                    val candidateQuality = chosen?.actualQuality
                    val officialStillBetter = chosen != null && !source.isPreview && actual != null &&
                        candidateQuality != null && candidateQuality.ordinal < actual.ordinal
                    if (officialStillBetter) {
                        Log.i(
                            TAG,
                            "Keep official over fallback songId=$songId " +
                                "official=${actual?.name} candidate=${candidateQuality?.name}",
                        )
                    }
                    if (chosen != null && !officialStillBetter) {
                        // The third-party side records itself inside resolveThirdParty;
                        // a cross-provider pick only records once it actually wins.
                        chosen.fallbackSource?.let { applyFallbackRecords(songId, quality, chosen) }
                        chosen.request
                    } else {
                        // Nothing better than the official answer: keep it - a complete
                        // stream below the bar, or the trial clip as the last resort.
                        ResolvedRequest(Uri.parse(source.url), provisional = source.isPreview).also {
                            PlaybackStageRuntime.record(songId.toString(), PlaybackStageRuntime.LabelNetease)
                        }
                    }
                }
            } catch (error: NeteasePlaybackUnavailableException) {
                // The official API has nothing: third-party first, then bilibili, with
                // the same quality-first comparison; give up only when both are empty.
                val thirdParty = resolveThirdParty(songId, quality, fallbackRequest, urgent = urgent)
                val fallback = if (thirdParty == null || !thirdParty.meetsRequested(quality)) {
                    crossProviderCandidate(songId, quality, fallbackRequest)
                } else {
                    null
                }
                val chosen = selectCandidate(quality, thirdParty, fallback) ?: throw error
                chosen.fallbackSource?.let { applyFallbackRecords(songId, quality, chosen) }
                chosen.request
            }
            if (!resolved.provisional) {
                synchronized(cacheLock) { resolvedUris[key] = resolved }
            }
            pending.complete(resolved)
            resolved
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, pending)
        }
    }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (ProviderPlaybackResolver.isProviderTrackUri(uri)) {
            val delegate = providerDelegate()
                ?: throw IOException("YSYY provider playback runtime is not initialized")
            return delegate.resolveDataSpec(dataSpec)
        }
        if (uri.scheme != MELOX_SCHEME || uri.host != SONG_HOST) {
            return dataSpec
        }

        val songId = uri.lastPathSegment?.toLongOrNull()
            ?: throw IOException("Invalid YSYY song URI: $uri")
        localSourceProvider(songId)?.let { local ->
            PlaybackStageRuntime.record(songId.toString(), PlaybackStageRuntime.LabelLocal)
            return dataSpec.withUri(local)
        }
        val requestedQuality = MusicQuality.fromApiLevel(uri.getQueryParameter(QUALITY_QUERY))
            ?: MusicQualityRuntime.selected
        val currentCookieHeader = cookieProvider()
        val fallbackRequest = fallbackRequest(uri, songId, requestedQuality)
        val key = resolveKey(songId, requestedQuality, currentCookieHeader, fallbackRequest)

        val resolved = resolveSongRequest(songId, requestedQuality, fallbackRequest, urgent = true)
        val cacheKey = playbackCacheKey(
            songId = songId,
            quality = requestedQuality,
            cookieHeader = currentCookieHeader,
            sourceIdentity = "${resolved.cacheIdentity}:${crossProviderFallback?.cacheIdentity().orEmpty()}",
        )

        return dataSpec.buildUpon()
            .setUri(resolved.uri)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + resolved.headers)
            .setKey(cacheKey)
            .build()
    }

    override fun resolveReportedUri(uri: Uri): Uri {
        if (ProviderPlaybackResolver.isProviderTrackUri(uri)) {
            return providerDelegate()?.resolveReportedUri(uri) ?: uri
        }
        if (uri.scheme != MELOX_SCHEME || uri.host != SONG_HOST) return uri
        val songId = uri.lastPathSegment?.toLongOrNull() ?: return uri
        localSourceProvider(songId)?.let { return it }
        val requestedQuality = MusicQuality.fromApiLevel(uri.getQueryParameter(QUALITY_QUERY))
            ?: MusicQualityRuntime.selected
        val currentCookieHeader = cookieProvider()
        val fallbackRequest = fallbackRequest(uri, songId, requestedQuality)
        return cached(resolveKey(songId, requestedQuality, currentCookieHeader, fallbackRequest))?.uri ?: uri
    }

    fun prefetch(uri: Uri) {
        if (ProviderPlaybackResolver.isProviderTrackUri(uri)) {
            providerDelegate()?.prefetch(uri)
            return
        }
        if (uri.scheme != MELOX_SCHEME || uri.host != SONG_HOST) return
        val songId = uri.lastPathSegment?.toLongOrNull() ?: return
        val quality = MusicQuality.fromApiLevel(uri.getQueryParameter(QUALITY_QUERY))
            ?: MusicQualityRuntime.selected
        // Prefetching must not delay the track the user is actually waiting for.
        resolveSongRequest(songId, quality, fallbackRequest(uri, songId, quality), urgent = false)
    }

    /**
     * Resolves through the user's third-party sources: LX Music scripts first, then
     * CHKSZ. Returns null when third-party sources are off or both stages failed; the
     * LX measured quality rides along so the caller can compare it against bilibili
     * when neither side meets the requested quality.
     */
    private fun resolveThirdParty(
        songId: Long,
        quality: MusicQuality,
        fallbackRequest: CrossProviderFallbackRequest?,
        hasPlayableFallback: Boolean = false,
        urgent: Boolean = true,
    ): QualityCandidate<MusicQuality, ResolvedRequest>? {
        if (!thirdPartySourcesEnabled()) return null
        val lx = runCatching {
            lxUserPlayback?.resolve(
                songId = fallbackRequest?.songId ?: songId,
                title = fallbackRequest?.title.orEmpty(),
                artist = fallbackRequest?.artist.orEmpty(),
                durationMs = fallbackRequest?.durationMs,
                quality = quality.toCommonTier(),
                hasPlayableFallback = hasPlayableFallback,
                urgent = urgent,
            )
        }.onFailure { Log.w(TAG, "LX stage failed songId=$songId error=${it.javaClass.simpleName}") }
            .getOrNull()
        if (lx != null) {
            Log.i(TAG, "Resolve success stage=lx script=${lx.sourceId} quality=${lx.quality?.apiLevel}")
            PlaybackStageRuntime.record(songId.toString(), PlaybackStageRuntime.LabelLx)
            // The official answer for a trial clip is Standard, and without this the
            // player chip would keep claiming "标准" while a measured lossless stream
            // from the LX source is what actually plays.
            lx.quality?.let {
                MusicQualityRuntime.recordActual(songId = songId, requested = quality, actual = it)
            }
            return QualityCandidate(
                request = ResolvedRequest(
                    uri = Uri.parse(lx.url),
                    headers = lx.requestHeaders,
                    cacheIdentity = "lx-user:${lx.sourceId}",
                ),
                actualQuality = lx.quality,
            )
        }
        val chksz = runCatching { chkszPlayback?.resolve(songId, quality.toCommonTier()) }
            .onFailure { Log.w(TAG, "CHKSZ stage failed songId=$songId error=${it.javaClass.simpleName}") }
            .getOrNull()
        return chksz?.let {
            Log.i(TAG, "Resolve success stage=chksz")
            PlaybackStageRuntime.record(songId.toString(), PlaybackStageRuntime.LabelChksz)
            QualityCandidate(
                request = ResolvedRequest(
                    uri = Uri.parse(it.url),
                    cacheIdentity = "chksz:${chkszPlayback?.cacheIdentity()}",
                ),
                // CHKSZ answers at the requested tier or fails and reports no measured
                // quality, so treat it as meeting the bar (null-pass convention).
                actualQuality = null,
            )
        }
    }

    private fun crossProviderCandidate(
        songId: Long,
        quality: MusicQuality,
        fallbackRequest: CrossProviderFallbackRequest?,
    ): QualityCandidate<MusicQuality, ResolvedRequest>? {
        val fallback = fallbackRequest
            ?.copy(quality = quality.toCommonTier())
            ?.let { crossProviderFallback?.resolve(it) }
            ?: return null
        return QualityCandidate(
            request = ResolvedRequest(
                uri = Uri.parse(fallback.url),
                headers = fallback.requestHeaders,
                expiresAtEpochMs = fallback.expiresAtEpochMs,
                cacheIdentity = "${fallback.source.storageValue}:${fallback.resourceId}",
            ),
            actualQuality = fallback.actualQuality.toMusicQuality(quality),
            fallbackSource = fallback.source,
        )
    }

    private fun applyFallbackRecords(
        songId: Long,
        quality: MusicQuality,
        candidate: QualityCandidate<MusicQuality, ResolvedRequest>,
    ) {
        val source = candidate.fallbackSource ?: return
        candidate.actualQuality?.let {
            MusicQualityRuntime.recordActual(songId = songId, requested = quality, actual = it)
        }
        // Also unconditional: a resolution that runs for a non-selected quality still
        // plays from the fallback source someday, and the chip must not keep a stale
        // LX/CHKSZ label for it.
        if (quality == MusicQualityRuntime.selected) {
            CrossProviderPlaybackRuntime.record(songId, source)
        }
        PlaybackStageRuntime.record(songId.toString(), source.displayName)
    }

    private fun resolveKey(
        songId: Long,
        quality: MusicQuality,
        cookieHeader: String,
        fallbackRequest: CrossProviderFallbackRequest?,
    ) = ResolveKey(
        songId = songId,
        quality = quality,
        cookieHeader = cookieHeader,
        fallbackIdentity = crossProviderFallback?.cacheIdentity().orEmpty(),
        metadataIdentity = fallbackRequest?.let {
            "${it.title}\u001f${it.artist}\u001f${it.durationMs.orZero()}"
        }.orEmpty(),
    )

    private fun fallbackRequest(
        uri: Uri,
        songId: Long,
        quality: MusicQuality,
    ): CrossProviderFallbackRequest? {
        val title = uri.getQueryParameter(TITLE_QUERY)?.takeIf(String::isNotBlank) ?: return null
        val artist = uri.getQueryParameter(ARTIST_QUERY)?.takeIf(String::isNotBlank) ?: return null
        return CrossProviderFallbackRequest(
            songId = songId,
            title = title,
            artist = artist,
            durationMs = uri.getQueryParameter(DURATION_QUERY)?.toLongOrNull()?.takeIf { it > 0L },
            quality = quality.toCommonTier(),
            // 网易云是主源：回落候选里排除自己（EligibleSources 现已包含 Netease，
            // 供其它主源把网易云当候选，但这里不能自我搜索）。
            excludeSource = MusicSource.Netease,
        )
    }

    private fun cached(key: ResolveKey): ResolvedRequest? = synchronized(cacheLock) {
        resolvedUris[key]?.also { cached ->
            if (cached.expiresAtEpochMs?.let { System.currentTimeMillis() >= it } == true) {
                resolvedUris.remove(key)
                return@synchronized null
            }
        }
    }

    private fun providerDelegate(): ProviderPlaybackResolver? {
        providerDelegate?.let { return it }
        val registry = ProviderPlaybackRuntime.registryOrNull() ?: return null
        return synchronized(this) {
            providerDelegate ?: ProviderPlaybackResolver(
                neteaseResolver = this,
                providers = registry,
                localSourceProvider = providerLocalSourceProvider,
                authKeyProvider = ProviderPlaybackRuntime::authKey,
                providerPlaybackEnabled = providerPlaybackEnabled,
                chkszPlayback = chkszPlayback,
                lxUserPlayback = lxUserPlayback,
                thirdPartySourcesEnabled = thirdPartySourcesEnabled,
                crossProviderFallback = crossProviderFallback,
            ).also { providerDelegate = it }
        }
    }

    companion object {
        private const val MELOX_SCHEME = "melox"
        private const val SONG_HOST = "song"
        private const val QUALITY_QUERY = "quality"
        private const val TITLE_QUERY = "title"
        private const val ARTIST_QUERY = "artist"
        private const val DURATION_QUERY = "durationMs"
        private const val MAX_RESOLVED_URIS = 96
        private const val PLAYBACK_CACHE_VERSION = 3
        /** Pause before retrying third-party sources for a song the official API only clips. */
        private const val PREVIEW_RETRY_PAUSE_MS = 1_500L

        private fun playbackCacheKey(
            songId: Long,
            quality: MusicQuality,
            cookieHeader: String,
            sourceIdentity: String,
        ): String =
            "netease:v$PLAYBACK_CACHE_VERSION:$songId:${quality.apiLevel}:" +
                "${cookieHeader.hashCode().toUInt().toString(16)}:${sourceIdentity.hashCode().toUInt().toString(16)}"

        fun uriForSong(
            songId: Long,
            quality: MusicQuality = MusicQualityRuntime.selected,
            title: String? = null,
            artist: String? = null,
            durationMs: Long? = null,
        ): Uri = Uri.Builder()
            .scheme(MELOX_SCHEME)
            .authority(SONG_HOST)
            .appendPath(songId.toString())
            .appendQueryParameter(QUALITY_QUERY, quality.apiLevel)
            .apply {
                title?.takeIf(String::isNotBlank)?.let { appendQueryParameter(TITLE_QUERY, it) }
                artist?.takeIf(String::isNotBlank)?.let { appendQueryParameter(ARTIST_QUERY, it) }
                durationMs?.takeIf { it > 0L }?.let { appendQueryParameter(DURATION_QUERY, it.toString()) }
            }
            .build()
    }
}

private const val TAG = "YSYYNeteaseResolve"

private fun Long?.orZero(): Long = this ?: 0L

private fun AudioQualityTier.toMusicQuality(requested: MusicQuality): MusicQuality = when (this) {
    AudioQualityTier.Standard -> MusicQuality.Standard
    AudioQualityTier.High -> MusicQuality.High
    AudioQualityTier.Lossless -> MusicQuality.Lossless
    AudioQualityTier.HiResolution -> MusicQuality.HiResolution
    AudioQualityTier.Immersive -> requested.takeIf {
        it == MusicQuality.HighDefinitionSurround || it == MusicQuality.ImmersiveSurround
    } ?: MusicQuality.ImmersiveSurround
    AudioQualityTier.Master -> MusicQuality.UltraClearMaster
}
