package com.lladlam.melox.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.lladlam.melox.core.audio.MusicQualityRuntime
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import com.lladlam.melox.core.music.provider.MusicProviderRegistry
import com.lladlam.melox.core.music.provider.PlaybackCapability
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Adds provider-aware `melox://track/...` URIs while delegating every legacy
 * `melox://song/<Long>` URI to the untouched NetEase resolver.
 */
@OptIn(UnstableApi::class)
class ProviderPlaybackResolver(
    private val neteaseResolver: NeteasePlaybackResolver,
    private val providers: MusicProviderRegistry,
    private val localSourceProvider: (MusicResourceId) -> Uri? = { null },
    private val authKeyProvider: (MusicSource) -> String = { "" },
    private val providerPlaybackEnabled: (MusicSource) -> Boolean = { true },
    private val chkszPlayback: ChkszPlaybackResolver? = null,
    private val lxUserPlayback: LxUserPlaybackResolver? = null,
    private val thirdPartySourcesEnabled: () -> Boolean = { true },
    private val crossProviderFallback: CrossProviderPlaybackFallbackResolver? = null,
) : ResolvingDataSource.Resolver {
    private data class ResolveKey(
        val requestUri: String,
        val authKey: String,
        val quality: AudioQualityTier,
    )
    private data class ResolvedRequest(
        val uri: Uri,
        val headers: Map<String, String>,
        val expiresAtEpochMs: Long? = null,
        /**
         * True when this is a stand-in a later attempt may improve - currently the
         * trial clip kept because nothing better was found. Caching it would make
         * one transient failure stick for the rest of the session, so it skips the
         * resolved-URI cache exactly like the NetEase chain's provisional answers.
         */
        val provisional: Boolean = false,
    )

    private val cacheLock = Any()
    private val resolvedUris = object : LinkedHashMap<ResolveKey, ResolvedRequest>(MAX_RESOLVED_URIS, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ResolveKey, ResolvedRequest>?): Boolean =
            size > MAX_RESOLVED_URIS
    }
    private val inFlight = ConcurrentHashMap<ResolveKey, CompletableFuture<ResolvedRequest>>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != MeloXScheme) return dataSpec
        if (uri.host == LegacySongHost) return neteaseResolver.resolveDataSpec(dataSpec)
        if (uri.host != ProviderTrackHost) return dataSpec
        val resolved = resolveProviderRequest(uri, urgent = true)
        return dataSpec.buildUpon()
            .setUri(resolved.uri)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + resolved.headers)
            // Provider CDN URLs are short-lived. Do not reuse bytes cached for an earlier resolution.
            .setKey(resolved.uri.toString())
            .build()
    }

    override fun resolveReportedUri(uri: Uri): Uri {
        if (uri.scheme != MeloXScheme) return uri
        if (uri.host == LegacySongHost) return neteaseResolver.resolveReportedUri(uri)
        if (uri.host != ProviderTrackHost) return uri
        val source = parseSource(uri) ?: return uri
        val quality = currentQuality(uri)
        return cached(ResolveKey(uri.toString(), authKeyProvider(source), quality))?.uri ?: uri
    }

    internal fun prefetch(uri: Uri) {
        // Prefetching must not delay the track the user is actually waiting for.
        if (isProviderTrackUri(uri)) resolveProviderRequest(uri, urgent = false)
    }

    private fun resolveProviderRequest(uri: Uri, urgent: Boolean): ResolvedRequest {
        val source = parseSource(uri)
            ?: throw IOException("Invalid YSYY provider source: $uri")
        if (!providerPlaybackEnabled(source)) {
            throw IOException("${source.displayName} 播放接口已由远程兼容性配置临时关闭")
        }
        val resourceValue = uri.pathSegments.getOrNull(1)
            ?.let(Uri::decode)
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Invalid YSYY provider track ID: $uri")
        val quality = currentQuality(uri)
        val key = ResolveKey(uri.toString(), authKeyProvider(source), quality)
        cached(key)?.let { return it }
        val pending = CompletableFuture<ResolvedRequest>()
        val existing = inFlight.putIfAbsent(key, pending)
        if (existing != null) {
            if (existing.isCompletedExceptionally) {
                inFlight.remove(key, existing)
            } else {
                return runCatching { existing.get(20L, TimeUnit.SECONDS) }
                    .getOrElse { throw IOException("Unable to resolve provider playback source", it.cause ?: it) }
            }
        }

        var trackOrNull: MusicTrack? = null
        return try {
            val id = MusicResourceId(source, resourceValue)
            val track = MusicTrack(
                id = id,
                title = uri.getQueryParameter(TrackTitleQuery).orEmpty(),
                artists = uri.getQueryParameter(TrackArtistsQuery).orEmpty().split('\u001f')
                    .filter(String::isNotBlank)
                    .map { com.lladlam.melox.core.music.model.MusicArtistRef(name = it) },
                durationMs = uri.getQueryParameter(TrackDurationQuery)?.toLongOrNull(),
                providerMetadata = providerMetadata(uri, id),
            )
            trackOrNull = track
            localSourceProvider(id)?.let { local ->
                PlaybackStageRuntime.record(PlaybackTrackIdentity.encode(id), PlaybackStageRuntime.LabelLocal)
                val result = ResolvedRequest(local, emptyMap())
                synchronized(cacheLock) { resolvedUris[key] = result }
                pending.complete(result)
                return result
            }
            Log.d(TAG, "resolve detail source=${source.storageValue} id=${resourceValue.take(8)} title=${track.title.take(40)} " +
                "artists=${track.artistText.take(60)} durationMs=${track.durationMs} metadata=${track.providerMetadata.javaClass.simpleName}")
            Log.i(TAG, "Resolve start source=${source.storageValue} quality=${quality.name} thirdParty=${thirdPartySourcesEnabled()}")
            // 官方（主源）先行：LX/CHKSZ 只在官方给试听片段、会员/版权受限或
            // 音质不达标时才花——与网易云链路同样是官方优先（需求：先找网易云）。
            Log.i(
                TAG,
                "Resolve official source=${source.storageValue} stage=provider chksz=${chkszPlayback?.cacheIdentity() ?: "unavailable"}",
            )
            val provider = providers.require(source)
            val playback = provider as? PlaybackCapability
                ?: throw IOException("${provider.displayName} 当前没有实现播放能力")
            val resolution = runBlocking(Dispatchers.IO) {
                withTimeout(20_000L) { playback.resolvePlayback(track, quality) }
            }
            val result = when (resolution) {
                is PlaybackResolution.Playable -> {
                    val actual = resolution.actualQuality ?: resolution.requestedQuality
                    val playable = ResolvedRequest(Uri.parse(resolution.url), resolution.requestHeaders, resolution.expiresAtEpochMs)
                    if (actual.ordinal >= quality.ordinal) {
                        ProviderPlaybackQualityRuntime.recordActual(id = id, requested = quality, actual = actual)
                        PlaybackStageRuntime.record(PlaybackTrackIdentity.encode(id), source.displayName)
                        playable
                    } else {
                        // 音质优先（与网易云链同一套）：官方流低于设置音质时
                        // LX→CHKSZ→bili 全部入场，selectCandidate 比质挑选；
                        // 都没有才接受这份额外打折的官方流（如实记录其真实音质）。
                        Log.i(
                            TAG,
                            "Official source insufficient source=${source.storageValue} " +
                                "actual=${actual.name} requested=${quality.name}, trying third-party",
                        )
                        val chosen = externalCandidate(track, quality, source, hasPlayableFallback = true, urgent = urgent)
                        if (chosen == null) {
                            playable.also {
                                ProviderPlaybackQualityRuntime.recordActual(id = id, requested = quality, actual = actual)
                                PlaybackStageRuntime.record(PlaybackTrackIdentity.encode(id), source.displayName)
                            }
                        } else {
                            // 三方在 resolveThirdParty 里自记；跨源只有真正胜出才记。
                            chosen.fallbackSource?.let { applyFallbackRecords(track, quality, chosen) }
                            chosen.request
                        }
                    }
                }
                is PlaybackResolution.Preview -> {
                    // Same case as Netease: the provider hands out a clip instead of
                    // failing, so the URL on its own is not an answer - spend the
                    // third-party sources (with the trial-clip retry) and the
                    // cross-provider pool before accepting it.
                    Log.i(TAG, "Provider served a trial clip source=${source.storageValue}, trying third-party")
                    val chosen = externalCandidate(
                        track,
                        quality,
                        source,
                        hasPlayableFallback = false,
                        urgent = urgent,
                        previewRetry = true,
                    )
                    if (chosen == null) {
                        // The replacement records LX/CHKSZ itself; the clip below still
                        // belongs to the provider and must overwrite a stale stage label.
                        // 三方和跨源回落都拿不到时才接受试听片段（避免拿 5 秒片段当正片播），
                        // 且 provisional 不入缓存：下次 prepare 重新解析，不把一次空手固化。
                        ResolvedRequest(Uri.parse(resolution.url), emptyMap(), provisional = true).also {
                            PlaybackStageRuntime.record(PlaybackTrackIdentity.encode(id), source.displayName)
                        }
                    } else {
                        chosen.fallbackSource?.let { applyFallbackRecords(track, quality, chosen) }
                        chosen.request
                    }
                }
                // 以下失败分支一律原样抛出，由 catch 统一跑 LX→CHKSZ→bili 比质
                // 选源（与网易云链官方抛异常的 catch 分支同款；上游 0.6.1 曾在这里
                // 丢掉第三方回落，我们保留它，且现在覆盖所有失败分支）。
                PlaybackResolution.LoginRequired -> throw IOException("${provider.displayName} 需要登录后播放")
                PlaybackResolution.SubscriptionRequired -> throw IOException("${provider.displayName} 当前歌曲需要对应会员权益")
                PlaybackResolution.RegionRestricted -> throw IOException("${provider.displayName} 当前地区不可播放")
                PlaybackResolution.CopyrightRestricted -> throw IOException("${provider.displayName} 当前版权不可播放")
                is PlaybackResolution.Unavailable -> throw IOException(
                    resolution.reason ?: "${provider.displayName} 暂时没有可播放音源",
                )
            }
            if (!result.provisional) {
                synchronized(cacheLock) { resolvedUris[key] = result }
            }
            pending.complete(result)
            result
        } catch (error: Throwable) {
            // 需求④ + 音源重设计：任意主源播放失败（VIP/地区/版权/登录/异常）都走
            // 与网易云链同一套外部选源——LX→CHKSZ 先花、bili 只在三方不满足时补、
            // selectCandidate 比质挑选；都拿不到就原样抛出原始错误。
            val fallbackTrack = trackOrNull
            if (fallbackTrack != null) {
                val chosen = externalCandidate(
                    fallbackTrack,
                    quality,
                    source,
                    hasPlayableFallback = false,
                    urgent = urgent,
                )
                if (chosen != null) {
                    chosen.fallbackSource?.let { applyFallbackRecords(fallbackTrack, quality, chosen) }
                    val recovered = chosen.request
                    synchronized(cacheLock) { resolvedUris[key] = recovered }
                    pending.complete(recovered)
                    return recovered
                }
            }
            pending.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, pending)
        }
    }

    /**
     * The external half of the NetEase chain, shared by every path a provider
     * resolution can take: the third-party sources (LX then CHKSZ) spend first,
     * bilibili only joins while they cannot satisfy the requested quality, and
     * [selectCandidate] picks by measured quality. Returns null when neither side
     * produced anything - the caller then keeps whatever the official resolution
     * offered (or rethrows its error).
     *
     * A resolve spends this at most once in practice: the success paths return
     * straight after calling it, and the failure paths funnel here through catch.
     */
    private fun externalCandidate(
        track: MusicTrack,
        quality: AudioQualityTier,
        source: MusicSource,
        hasPlayableFallback: Boolean,
        urgent: Boolean,
        previewRetry: Boolean = false,
    ): QualityCandidate<AudioQualityTier, ResolvedRequest>? {
        val thirdParty = if (source != MusicSource.Jellyfin && thirdPartySourcesEnabled()) {
            var candidate = resolveThirdParty(track, quality, source, hasPlayableFallback, urgent)
            if (candidate == null && previewRetry) {
                // The public mirrors answer `429 请求过于频繁` when several songs
                // resolve at once, so one retry after a pause recovers songs that
                // would otherwise be left on the trial clip.
                Thread.sleep(PREVIEW_RETRY_PAUSE_MS)
                Log.i(
                    TAG,
                    "Official answer is a trial clip and third-party found nothing, " +
                        "retrying id=${track.id.value}",
                )
                candidate = resolveThirdParty(track, quality, source, hasPlayableFallback, urgent)
            }
            candidate
        } else {
            null
        }
        // bilibili only matters while the third-party side cannot satisfy
        // the bar; a qualifying third-party result wins outright.
        val fallback = if (thirdParty == null || !thirdParty.meetsRequested(quality)) {
            crossProviderCandidate(track, quality, source)
        } else {
            null
        }
        return selectCandidate(quality, thirdParty, fallback)
    }

    /**
     * Spends the user's third-party sources for a provider track: LX Music scripts
     * first, then CHKSZ. Returns null when the sources are off or both stages
     * failed; the LX measured quality rides along as the candidate's actual quality
     * so the caller can compare it against bilibili when neither side meets the
     * requested quality.
     */
    private fun resolveThirdParty(
        track: MusicTrack,
        quality: AudioQualityTier,
        source: MusicSource,
        hasPlayableFallback: Boolean,
        urgent: Boolean,
    ): QualityCandidate<AudioQualityTier, ResolvedRequest>? {
        if (!thirdPartySourcesEnabled()) return null
        val stageKey = PlaybackTrackIdentity.encode(track.id)
        val lx = runCatching {
            lxUserPlayback?.resolve(track, quality, hasPlayableFallback = hasPlayableFallback, urgent = urgent)
        }
            .onFailure { Log.w(TAG, "LX membership fallback failed source=${source.storageValue}", it) }
            .getOrNull()
        if (lx != null) {
            PlaybackStageRuntime.record(stageKey, PlaybackStageRuntime.LabelLx)
            // The official answer for a trial clip is Standard, and without this the
            // player chip would keep claiming the requested tier while a measured
            // stream from the LX source is what actually plays.
            lx.quality?.let {
                ProviderPlaybackQualityRuntime.recordActual(
                    id = track.id,
                    requested = quality,
                    actual = it.toCommonTier(),
                )
            }
            Log.i(TAG, "Resolve success source=${source.storageValue} stage=lx quality=${lx.quality?.apiLevel}")
            return QualityCandidate(
                request = ResolvedRequest(Uri.parse(lx.url), lx.requestHeaders),
                actualQuality = lx.quality?.toCommonTier(),
            )
        }
        val chksz = runCatching { chkszPlayback?.resolve(track, quality) }
            .onFailure { Log.w(TAG, "CHKSZ membership fallback failed source=${source.storageValue}", it) }
            .getOrNull()
        return chksz?.let {
            PlaybackStageRuntime.record(stageKey, PlaybackStageRuntime.LabelChksz)
            Log.i(TAG, "Resolve success source=${source.storageValue} stage=chksz")
            QualityCandidate(
                request = ResolvedRequest(Uri.parse(it.url), emptyMap()),
                // CHKSZ answers at the requested tier or fails and reports no measured
                // quality, so treat it as meeting the bar (null-pass convention).
                actualQuality = null,
            )
        }
    }

    /**
     * 需求④ + 音源重设计：任意主源都复用同一套跨源回落（bilibili 固定优先、
     * 带回音质门槛，见 [CrossProviderPlaybackFallbackResolver]）。只构造候选、
     * 不写任何记录——记录由 [applyFallbackRecords] 在真正胜出时写入，
     * 以免一次落选的搜索覆盖三方已写好的舞台标签。
     */
    private fun crossProviderCandidate(
        track: MusicTrack,
        quality: AudioQualityTier,
        source: MusicSource,
    ): QualityCandidate<AudioQualityTier, ResolvedRequest>? {
        val resolver = crossProviderFallback ?: return null
        if (track.title.isBlank() || track.artistText.isBlank()) return null
        val result = runCatching {
            resolver.resolve(
                CrossProviderFallbackRequest(
                    songId = track.id.value.toLongOrNull() ?: 0L,
                    title = track.title,
                    artist = track.artistText,
                    durationMs = track.durationMs,
                    quality = quality,
                    excludeSource = source,
                ),
            )
        }.onFailure {
            Log.w(TAG, "Cross-provider fallback failed source=${source.storageValue}", it)
        }.getOrNull() ?: return null
        return QualityCandidate(
            request = ResolvedRequest(Uri.parse(result.url), result.requestHeaders, result.expiresAtEpochMs),
            actualQuality = result.actualQuality,
            fallbackSource = result.source,
        )
    }

    /**
     * A cross-provider pick records only once it wins [selectCandidate]: the measured
     * quality, the stage label (overwriting whatever the third-party side wrote while
     * searching), and the success log - same convention as the NetEase chain.
     */
    private fun applyFallbackRecords(
        track: MusicTrack,
        quality: AudioQualityTier,
        candidate: QualityCandidate<AudioQualityTier, ResolvedRequest>,
    ) {
        val source = candidate.fallbackSource ?: return
        candidate.actualQuality?.let {
            ProviderPlaybackQualityRuntime.recordActual(id = track.id, requested = quality, actual = it)
        }
        PlaybackStageRuntime.record(PlaybackTrackIdentity.encode(track.id), source.displayName)
        Log.i(
            TAG,
            "Resolve success source=${track.id.source.storageValue} stage=cross-provider " +
                "via=${source.storageValue} quality=${candidate.actualQuality?.name ?: "unknown"}",
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

    private fun currentQuality(uri: Uri): AudioQualityTier {
        // The quality selector updates this runtime before Media3 prepare(). That
        // allows a stable provider media identity to request a fresh VKey at the
        // newly selected tier instead of reusing its initial query parameter.
        val runtime = MusicQualityRuntime.selected.toCommonTier()
        return runtime.takeIf { MusicQualityRuntime.selected.apiLevel.isNotBlank() }
            ?: uri.getQueryParameter(QualityQuery)
                ?.let { raw -> AudioQualityTier.entries.firstOrNull { it.name == raw } }
            ?: AudioQualityTier.Standard
    }

    private fun parseSource(uri: Uri): MusicSource? {
        val raw = uri.pathSegments.firstOrNull() ?: return null
        return MusicSource.entries.firstOrNull { it.storageValue == raw }
    }

    private fun providerMetadata(uri: Uri, id: MusicResourceId): ProviderTrackMetadata = when (id.source) {
        MusicSource.Netease -> ProviderTrackMetadata.Netease(
            numericId = id.value.toLongOrNull()
                ?: throw IOException("Invalid NetEase track ID: ${id.value}"),
        )
        MusicSource.QQMusic -> ProviderTrackMetadata.QQMusic(
            songMid = id.value,
            mediaMid = uri.getQueryParameter(QQMediaMidQuery)?.takeIf(String::isNotBlank),
            numericSongId = uri.getQueryParameter(QQNumericIdQuery)?.toLongOrNull(),
        )
        MusicSource.Kugou -> ProviderTrackMetadata.Kugou(
            hash = id.value,
            albumAudioId = uri.getQueryParameter(KugouAlbumAudioIdQuery)?.toLongOrNull(),
            albumId = uri.getQueryParameter(KugouAlbumIdQuery)?.takeIf(String::isNotBlank),
        )
        MusicSource.Kuwo -> ProviderTrackMetadata.Kuwo(
            mid = id.value.toLongOrNull()
                ?: throw IOException("Invalid Kuwo track ID: ${id.value}"),
        )
        MusicSource.AppleMusic -> ProviderTrackMetadata.AppleMusic(
            catalogId = id.value,
            storefront = uri.getQueryParameter(AppleStorefrontQuery).orEmpty().ifBlank { "us" },
            previewUrl = uri.getQueryParameter(ApplePreviewUrlQuery)?.takeIf(String::isNotBlank),
        )
        MusicSource.Bilibili -> {
            val (bvid, cid) = com.lladlam.melox.core.provider.bilibili.BilibiliProvider.parseIdentity(id.value)
                ?: throw IOException("Invalid Bilibili track ID")
            ProviderTrackMetadata.Bilibili(bvid, cid)
        }
        MusicSource.Spotify -> ProviderTrackMetadata.Spotify(
            id.value,
            uri.getQueryParameter(SpotifyIsrcQuery)?.takeIf(String::isNotBlank),
        )
        MusicSource.YouTubeMusic -> ProviderTrackMetadata.Empty
        MusicSource.Jellyfin -> ProviderTrackMetadata.Empty
        MusicSource.Local -> ProviderTrackMetadata.Local(
            contentUri = uri.getQueryParameter("localContentUri").orEmpty(),
            fileKey = id.value,
        )
    }

    companion object {
        private const val MeloXScheme = "melox"
        private const val LegacySongHost = "song"
        private const val ProviderTrackHost = "track"
        private const val QualityQuery = "qualityTier"
        private const val QQMediaMidQuery = "qqMediaMid"
        private const val QQNumericIdQuery = "qqNumericId"
        private const val KugouAlbumAudioIdQuery = "kgAlbumAudioId"
        private const val KugouAlbumIdQuery = "kgAlbumId"
        private const val AppleStorefrontQuery = "appleStorefront"
        private const val ApplePreviewUrlQuery = "applePreviewUrl"
        private const val SpotifyTitleQuery = "spotifyTitle"
        private const val SpotifyArtistsQuery = "spotifyArtists"
        private const val SpotifyDurationQuery = "spotifyDurationMs"
        private const val SpotifyIsrcQuery = "spotifyIsrc"
        private const val TrackTitleQuery = "trackTitle"
        private const val TrackArtistsQuery = "trackArtists"
        private const val TrackDurationQuery = "trackDurationMs"
        private const val TAG = "YSYYThirdParty"
        private const val MAX_RESOLVED_URIS = 96
        /** Pause before retrying third-party sources for a song the provider only clips. */
        private const val PREVIEW_RETRY_PAUSE_MS = 1_500L

        fun isProviderTrackUri(uri: Uri): Boolean =
            uri.scheme == MeloXScheme && uri.host == ProviderTrackHost

        fun uriForTrack(
            track: MusicTrack,
            quality: AudioQualityTier,
        ): Uri = Uri.Builder()
            .scheme(MeloXScheme)
            .authority(ProviderTrackHost)
            .appendPath(track.id.source.storageValue)
            .appendPath(track.id.value)
            .appendQueryParameter(QualityQuery, quality.name)
            .appendQueryParameter(TrackTitleQuery, track.title)
            .appendQueryParameter(TrackArtistsQuery, track.artists.joinToString("\u001f") { it.name })
            .apply { track.durationMs?.let { appendQueryParameter(TrackDurationQuery, it.toString()) } }
            .apply {
                when (val metadata = track.providerMetadata) {
                    is ProviderTrackMetadata.QQMusic -> {
                        metadata.mediaMid?.takeIf(String::isNotBlank)?.let {
                            appendQueryParameter(QQMediaMidQuery, it)
                        }
                        metadata.numericSongId?.let {
                            appendQueryParameter(QQNumericIdQuery, it.toString())
                        }
                    }
                    is ProviderTrackMetadata.Kugou -> {
                        metadata.albumAudioId?.let {
                            appendQueryParameter(KugouAlbumAudioIdQuery, it.toString())
                        }
                        metadata.albumId?.takeIf(String::isNotBlank)?.let {
                            appendQueryParameter(KugouAlbumIdQuery, it)
                        }
                    }
                    is ProviderTrackMetadata.AppleMusic -> {
                        appendQueryParameter(AppleStorefrontQuery, metadata.storefront)
                        metadata.previewUrl?.takeIf(String::isNotBlank)?.let {
                            appendQueryParameter(ApplePreviewUrlQuery, it)
                        }
                    }
                    is ProviderTrackMetadata.Bilibili -> Unit
                    is ProviderTrackMetadata.Spotify -> {
                        appendQueryParameter(SpotifyTitleQuery, track.title)
                        appendQueryParameter(
                            SpotifyArtistsQuery,
                            track.artists.joinToString("\u001f") { it.name },
                        )
                        track.durationMs?.let { appendQueryParameter(SpotifyDurationQuery, it.toString()) }
                        metadata.isrc?.takeIf(String::isNotBlank)?.let {
                            appendQueryParameter(SpotifyIsrcQuery, it)
                        }
                    }
                    is ProviderTrackMetadata.Local -> {
                        appendQueryParameter("localContentUri", metadata.contentUri)
                    }
                    else -> Unit
                }
            }
            .build()
    }
}
