package com.lladlam.melox.playback

import android.content.Context
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import com.lladlam.melox.core.music.model.TrackAvailability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.MusicProviderRegistry
import com.lladlam.melox.core.music.provider.PlaybackCapability
import com.lladlam.melox.core.music.provider.SearchCapability
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigDefaults
import com.lladlam.melox.core.remoteconfig.MeloXRemoteFallbackConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object CrossProviderPlaybackPreferences {
    private const val PreferencesName = "melox_playback"
    private const val EnabledKey = "cross_provider_unavailable_fallback"

    fun enabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        .getBoolean(EnabledKey, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(EnabledKey, enabled)
            .apply()
    }
}

object CrossProviderPlaybackRuntime {
    private val sourceByNeteaseSong = ConcurrentHashMap<Long, MusicSource>()

    fun record(songId: Long, source: MusicSource) {
        sourceByNeteaseSong[songId] = source
    }

    fun sourceFor(songId: Long?): MusicSource? = songId?.let(sourceByNeteaseSong::get)

    fun clear(songId: Long? = null) {
        if (songId == null) sourceByNeteaseSong.clear() else sourceByNeteaseSong.remove(songId)
    }
}

internal data class CrossProviderFallbackRequest(
    val songId: Long,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val quality: AudioQualityTier,
)

internal data class CrossProviderFallbackResult(
    val source: MusicSource,
    val resourceId: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val actualQuality: AudioQualityTier,
    val expiresAtEpochMs: Long?,
)

internal data class FallbackCandidateScore(
    val candidate: MusicTrack,
    val score: Int,
)

class CrossProviderPlaybackFallbackResolver(
    private val enabledProvider: () -> Boolean,
    private val registryProvider: () -> MusicProviderRegistry?,
    private val cacheIdentityProvider: () -> String = { "" },
    private val eventLogger: (String) -> Unit = {},
    private val fallbackConfigProvider: () -> MeloXRemoteFallbackConfig = {
        MeloXRemoteConfigDefaults.Config.fallback
    },
) {
    fun cacheIdentity(): String = if (enabledProvider()) {
        val fallback = fallbackConfigProvider()
        "enabled:${fallback.order.joinToString(",")}:${fallback.disabledProviders.sorted().joinToString(",")}:" +
            "${fallback.timeoutMs}:${cacheIdentityProvider()}"
    } else {
        "disabled"
    }

    internal fun resolve(request: CrossProviderFallbackRequest): CrossProviderFallbackResult? {
        if (!enabledProvider() || request.title.isBlank() || request.artist.isBlank()) {
            eventLogger("skipped song=${request.songId}: disabled or missing metadata")
            return null
        }
        val fallbackConfig = fallbackConfigProvider()
        if (!fallbackConfig.enabled) {
            eventLogger("skipped song=${request.songId}: remotely disabled")
            return null
        }
        val order = fallbackConfig.order.withIndex().associate { (index, source) -> source to index }
        val providers = registryProvider()?.providers.orEmpty()
            .filter(::isEligibleFallbackProvider)
            .filterNot { it.source.storageValue in fallbackConfig.disabledProviders }
            .sortedBy { order[it.source.storageValue] ?: Int.MAX_VALUE }
        if (providers.isEmpty()) {
            eventLogger("skipped song=${request.songId}: no eligible providers")
            return null
        }

        val artists = splitArtists(request.artist)
        val primaryArtist = artists.firstOrNull() ?: return null
        val sourceTrack = MusicTrack(
            id = MusicResourceId(MusicSource.Netease, request.songId.toString()),
            title = request.title,
            artists = artists.map { MusicArtistRef(name = it) },
            durationMs = request.durationMs?.takeIf { it > 0L },
            availability = TrackAvailability.Unavailable,
            providerMetadata = ProviderTrackMetadata.Netease(request.songId),
        )
        // 参考 NeriPlayer：同一首歌轮换多种查询串（标题+歌手 / 歌手+标题 / 纯标题），
        // 主查询搜不到或搜不准时由后续轮次兜底。
        val queries = buildSearchQueries(request.title, primaryArtist)
        val providerPriority = providers.withIndex()
            .associate { (index, provider) -> provider.source to index }

        return runBlocking(Dispatchers.IO) {
            val resolved = withTimeoutOrNull(fallbackConfig.timeoutMs.toLong()) {
                val attempted = mutableSetOf<MusicResourceId>()
                val loginBlocked = mutableSetOf<MusicSource>()
                var attempts = 0
                for ((round, query) in queries.withIndex()) {
                    val candidates = coroutineScope {
                        providers.map { provider ->
                            async {
                                val search = provider as SearchCapability
                                val outcome = runCatching {
                                    withTimeoutOrNull(SearchTimeoutMs) {
                                        search.searchSongs(query, page = 1, pageSize = SearchPageSize)
                                    }
                                }
                                val page = outcome.getOrNull()
                                when {
                                    outcome.isFailure -> {
                                        eventLogger(
                                            "search ${provider.source.storageValue} failed: " +
                                                describeError(outcome.exceptionOrNull()),
                                        )
                                        emptyList<MusicTrack>()
                                    }
                                    page == null -> {
                                        eventLogger("search ${provider.source.storageValue} timed out")
                                        emptyList<MusicTrack>()
                                    }
                                    else -> {
                                        eventLogger(
                                            "search ${provider.source.storageValue}: ${page.items.size} candidates",
                                        )
                                        page.items
                                    }
                                }
                            }
                        }.awaitAll().flatten()
                    }
                    val ranked = rankCandidates(sourceTrack, candidates, providerPriority)
                        .filterNot { it.candidate.id in attempted }
                    attempted += ranked.map { it.candidate.id }
                    val top = ranked.firstOrNull()
                    val topLabel = top?.let { "${it.candidate.id.source.storageValue}:${it.score}" } ?: "-"
                    eventLogger(
                        "scored matches song=${request.songId} query=${round + 1}: ${ranked.size} top=$topLabel",
                    )
                    for (match in ranked) {
                        if (attempts >= MaxPlaybackAttempts) break
                        val provider = providers.firstOrNull { it.source == match.candidate.id.source } ?: continue
                        if (provider.source in loginBlocked) continue
                        val playback = provider as PlaybackCapability
                        val attempt = runCatching {
                            playback.resolvePlayback(match.candidate, request.quality)
                        }
                        val resolution = attempt.getOrNull()
                        attempts++
                        if (resolution is PlaybackResolution.Playable) {
                            eventLogger(
                                "resolved song=${request.songId} via ${provider.source.storageValue} " +
                                    "score=${match.score}",
                            )
                            return@withTimeoutOrNull CrossProviderFallbackResult(
                                source = provider.source,
                                resourceId = match.candidate.id.value,
                                url = resolution.url,
                                requestHeaders = resolution.requestHeaders,
                                actualQuality = resolution.actualQuality ?: resolution.requestedQuality,
                                expiresAtEpochMs = resolution.expiresAtEpochMs,
                            )
                        }
                        // 要登录才能播是账号级问题，本轮起这个源的其余候选不再浪费请求。
                        if (resolution == PlaybackResolution.LoginRequired) loginBlocked += provider.source
                        val reason = if (attempt.isSuccess) {
                            describeResolution(resolution)
                        } else {
                            describeError(attempt.exceptionOrNull())
                        }
                        eventLogger(
                            "playback ${provider.source.storageValue} rejected: $reason score=${match.score}",
                        )
                    }
                    if (attempts >= MaxPlaybackAttempts) break
                    if (loginBlocked.isNotEmpty() && loginBlocked.containsAll(providers.map { it.source })) break
                }
                null
            }
            if (resolved == null) eventLogger("unresolved or timed out song=${request.songId}")
            resolved
        }
    }

    private fun isEligibleFallbackProvider(provider: MusicProvider): Boolean =
        provider.source in EligibleSources && provider is SearchCapability && provider is PlaybackCapability

    companion object {
        val EligibleSources = setOf(
            MusicSource.QQMusic,
            MusicSource.Kugou,
            MusicSource.Kuwo,
            MusicSource.Bilibili,
        )

        internal fun splitArtists(value: String): List<String> = value
            .split(Regex("\\s*(?:/|、|，|,)\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)
    }
}

// ---- NeriPlayer 式候选打分（参考 _neri/recon/NeteaseAutoSourceSwitch.kt）----
// 标题包含 +55 / 词全命中 +35 / 词部分命中 +18，歌手关联 +25，名单完全一致 +10，
// 时长差 <=8s +30、<=20s +22、<=45s +12、超过两倍 -15；70 分才接受。
private const val MinAcceptScore = 70
private const val MaxPlaybackAttempts = 12
private const val SearchPageSize = 10
private const val SearchTimeoutMs = 2_500L

private const val TitleHitScore = 55
private const val TitleTokenFullScore = 35
private const val TitleTokenPartialScore = 18
private const val ArtistHitScore = 25
private const val CompleteArtistScore = 10
private const val DurationCloseScore = 30
private const val DurationFairScore = 22
private const val DurationLooseScore = 12
private const val DurationWayOffScore = -15

private val scoreNonTextRegex = Regex("[^\\p{L}\\p{N}]+")
private val scoreWhitespaceRegex = Regex("\\s+")

internal fun buildSearchQueries(title: String, primaryArtist: String): List<String> = listOf(
    "$title $primaryArtist",
    "$primaryArtist $title",
    title,
).map { scoreWhitespaceRegex.replace(it.trim(), " ").trim() }
    .filter(String::isNotBlank)
    .distinct()

internal fun rankCandidates(
    source: MusicTrack,
    candidates: List<MusicTrack>,
    providerPriority: Map<MusicSource, Int>,
): List<FallbackCandidateScore> = candidates
    .distinctBy { it.id }
    .mapNotNull { candidate -> scoreCandidate(source, candidate)?.let { FallbackCandidateScore(candidate, it) } }
    .filter { it.score >= MinAcceptScore }
    .sortedWith(
        compareByDescending<FallbackCandidateScore> { it.score }
            .thenBy { providerPriority[it.candidate.id.source] ?: Int.MAX_VALUE },
    )

internal fun scoreCandidate(source: MusicTrack, candidate: MusicTrack): Int? {
    val sourceTitle = normalizeScoreText(source.title)
    val candidateTitle = normalizeScoreText(candidate.title)
    if (sourceTitle.isBlank() || candidateTitle.isBlank()) return null

    val sourceArtists = source.artists.map { normalizeScoreText(it.name) }.filter(String::isNotBlank)
    val candidateArtists = candidate.artists.map { normalizeScoreText(it.name) }.filter(String::isNotBlank)
    val artistHit = hasArtistRelation(sourceArtists, candidateArtists, candidateTitle)
    // 两边都有歌手信息却毫无关联（同名曲/串歌）直接丢弃：纯标题+时长分会把错误曲目顶上来。
    // 例外：bilibili 的 "artist" 是 UP 主名，本来就不是歌曲歌手，拿它做硬门等于把 bili 兜底
    // 全部挡死（旧的 SpotifyTrackMatcher 正是这么挡的）—— bili 只靠标题+时长判定。
    if (candidate.id.source != MusicSource.Bilibili &&
        sourceArtists.isNotEmpty() && candidateArtists.isNotEmpty() && !artistHit
    ) {
        return null
    }

    val compactSourceTitle = compactScoreText(sourceTitle)
    val titleScore = if (compactSourceTitle.length >= 2 && compactScoreText(candidateTitle).contains(compactSourceTitle)) {
        TitleHitScore
    } else {
        tokenOverlapScore(candidateTitle, sourceTitle)
    }

    var score = titleScore
    if (artistHit) score += ArtistHitScore
    score += durationSimilarityScore(source.durationMs, candidate.durationMs)
    if (sourceArtists.isNotEmpty() && sourceArtists.toSet() == candidateArtists.toSet()) {
        // 名单完全一致再加分：没有时长可消歧时，靠它把完整名单的候选顶到前面。
        score += CompleteArtistScore
    }
    return score
}

internal fun normalizeScoreText(value: String): String = scoreWhitespaceRegex
    .replace(scoreNonTextRegex.replace(value.lowercase(), " "), " ")
    .trim()

private fun compactScoreText(value: String): String = normalizeScoreText(value).replace(" ", "")

private fun hasArtistRelation(
    sourceArtists: List<String>,
    candidateArtists: List<String>,
    normalizedCandidateTitle: String,
): Boolean {
    val compactTitle = compactScoreText(normalizedCandidateTitle)
    return sourceArtists.any { sourceArtist ->
        val compactSourceArtist = compactScoreText(sourceArtist)
        candidateArtists.any { candidateArtist ->
            candidateArtist == sourceArtist ||
                (sourceArtist.length >= 2 && candidateArtist.contains(sourceArtist)) ||
                (candidateArtist.length >= 2 && sourceArtist.contains(candidateArtist))
        } || (compactSourceArtist.length >= 2 && compactTitle.contains(compactSourceArtist))
    }
}

private fun tokenOverlapScore(normalizedCandidateTitle: String, normalizedSourceTitle: String): Int {
    val tokens = normalizedSourceTitle.split(' ').filter { it.length >= 2 }
    if (tokens.isEmpty()) return 0
    val hits = tokens.count { normalizedCandidateTitle.contains(it) }
    return when {
        hits == tokens.size -> TitleTokenFullScore
        hits > 0 -> TitleTokenPartialScore
        else -> 0
    }
}

private fun durationSimilarityScore(sourceMs: Long?, candidateMs: Long?): Int {
    val sourceDuration = sourceMs ?: 0L
    val candidateDuration = candidateMs ?: 0L
    if (sourceDuration <= 0L || candidateDuration <= 0L) return 0
    val diffMs = abs(candidateDuration - sourceDuration)
    return when {
        diffMs <= 8_000L -> DurationCloseScore
        diffMs <= 20_000L -> DurationFairScore
        diffMs <= 45_000L -> DurationLooseScore
        candidateDuration > sourceDuration * 2 -> DurationWayOffScore
        else -> 0
    }
}

// release 包开了 R8，javaClass.simpleName 是 sg5/ch5 这类混淆名，这里按类型给出可读标签。
private fun describeResolution(resolution: PlaybackResolution?): String = when (resolution) {
    null -> "error"
    PlaybackResolution.LoginRequired -> "login-required"
    PlaybackResolution.SubscriptionRequired -> "subscription-required"
    PlaybackResolution.RegionRestricted -> "region-restricted"
    PlaybackResolution.CopyrightRestricted -> "copyright-restricted"
    is PlaybackResolution.Unavailable -> "unavailable:${resolution.reason?.take(60) ?: "-"}"
    is PlaybackResolution.Playable -> "playable"
    is PlaybackResolution.Preview -> "preview"
    else -> "unknown"
}

// 抛出的异常才是根因所在（"酷狗音乐请求失败：HTTP 404"、bilibili 的 message），
// 只打 simpleName 等于把线索扔了。
private fun describeError(error: Throwable?): String {
    if (error == null) return "error"
    val message = error.message?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
    return if (message.isBlank()) {
        error.javaClass.simpleName
    } else {
        "${error.javaClass.simpleName}: ${message.take(80)}"
    }
}
