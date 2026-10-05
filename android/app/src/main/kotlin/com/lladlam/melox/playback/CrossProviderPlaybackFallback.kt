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
import com.lladlam.melox.core.music.provider.PageExpandableCapability
import com.lladlam.melox.core.music.provider.PlaybackCapability
import com.lladlam.melox.core.music.provider.SearchCapability
import com.lladlam.melox.core.provider.bilibili.BilibiliProvider
import com.lladlam.melox.core.provider.bilibili.bilibiliSearchDurationBucket
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigDefaults
import com.lladlam.melox.core.remoteconfig.MeloXRemoteFallbackConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object CrossProviderPlaybackPreferences {
    private const val PreferencesName = "ysyy_playback"
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

/**
 * Which stage actually served the URL behind a media id, so the player quality
 * chip can prefix the playing source (网易云 / LX / CHKSZ / 本地 / provider names).
 * A cross-provider fallback of a NetEase song is tracked separately by
 * [CrossProviderPlaybackRuntime] and wins over this map in the chip.
 *
 * Keys are [PlaybackTrackIdentity] media ids: a plain numeric string for
 * NetEase songs and `melox:<source>:<id>` for provider media, so the resolvers
 * that record and the chip that reads agree on the lookup key. Both the
 * resolver caches and this map live in one process, so a cache hit always has
 * a label recorded by the resolve that filled it.
 */
object PlaybackStageRuntime {
    const val LabelNetease = "网易云"
    const val LabelLx = "LX"
    const val LabelChksz = "CHKSZ"
    const val LabelLocal = "本地"

    private const val MaxTracked = 512
    private val stageByMediaId = ConcurrentHashMap<String, String>()

    fun record(mediaId: String, stage: String) {
        if (stageByMediaId.size >= MaxTracked) stageByMediaId.clear()
        stageByMediaId[mediaId] = stage
    }

    fun stageFor(mediaId: String?): String? = mediaId?.let(stageByMediaId::get)
}

internal data class CrossProviderFallbackRequest(
    val songId: Long,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val quality: AudioQualityTier,
    /** 曲目自己的来源：回落候选里要把主源自身排除掉（QQ 主源失败后不再搜 QQ）。 */
    val excludeSource: MusicSource? = null,
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
        // 项1 量化：源曲时长缺失会让 durationSimilarityScore 归 0（少至多 30 分，
        // 70 分门槛更难过）且跳过服务端 duration 桶筛 —— 先统计缺失率，
        // 再决定要不要做 Neri 式 maybeUpdateSongDuration 时长回写。
        val durationLabel = request.durationMs?.toString() ?: "null"
        eventLogger(
            "resolve song=${request.songId} durationMs=$durationLabel quality=${request.quality}",
        )
        val order = fallbackConfig.order.withIndex().associate { (index, source) -> source to index }
        val providers = registryProvider()?.providers.orEmpty()
            .filter(::isEligibleFallbackProvider)
            .filterNot { it.source.storageValue in fallbackConfig.disabledProviders }
            .filterNot { it.source == request.excludeSource }
            // 需求：先找网易云，bilibili 殿后兜底——远程配置的 order 只作同级参考；
            // 禁用任一源仍走 disabledProviders，远程可关。
            .sortedWith(
                compareBy<MusicProvider>(
                    { if (it.source == MusicSource.Netease) 0 else 1 },
                    { order[it.source.storageValue] ?: Int.MAX_VALUE },
                ),
            )
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
                // 逐候选尝试：Playable 且音质达标才接受（需求③；bilibili 兜底豁免，音质未知放行）。
                // suspend local fun：resolvePlayback 是挂起函数，只能在协程体里调。
                suspend fun attemptCandidates(ranked: List<FallbackCandidateScore>): CrossProviderFallbackResult? {
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
                            val actualQuality = resolution.actualQuality ?: resolution.requestedQuality
                            if (provider.source == MusicSource.Bilibili ||
                                actualQuality.ordinal >= request.quality.ordinal
                            ) {
                                eventLogger(
                                    "resolved song=${request.songId} via ${provider.source.storageValue} " +
                                        "score=${match.score} quality=${actualQuality.name}",
                                )
                                return CrossProviderFallbackResult(
                                    source = provider.source,
                                    resourceId = match.candidate.id.value,
                                    url = resolution.url,
                                    requestHeaders = resolution.requestHeaders,
                                    actualQuality = actualQuality,
                                    expiresAtEpochMs = resolution.expiresAtEpochMs,
                                )
                            }
                            // 低于请求音质不接受，换下一个候选（需求③；bilibili 不在此列）。
                            eventLogger(
                                "playback ${provider.source.storageValue} rejected: quality " +
                                    "${actualQuality.name} below requested ${request.quality.name} " +
                                    "score=${match.score}",
                            )
                            continue
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
                    return null
                }
                for ((round, query) in queries.withIndex()) {
                    // 全源并行搜索，但只先等排序第一的源（当前 pin 为网易云）——
                    // 不让另一源的 2.5s 搜索超时和慢解析拖慢兜底；其余源后台继续搜再收结果。
                    val phaseResult = coroutineScope {
                        val searches = providers.map { provider ->
                            provider to async {
                                val search = provider as SearchCapability
                                val outcome = runCatching {
                                    withTimeoutOrNull(SearchTimeoutMs) {
                                        if (provider is BilibiliProvider) {
                                            // 任务①b：bili 搜索先按目标时长带 duration 桶筛，
                                            // 搜空时 searchSongs 内部会去掉筛重搜（Neri 蓝本）。
                                            provider.searchSongs(
                                                query,
                                                page = 1,
                                                pageSize = BiliSearchPageSize,
                                                durationMs = request.durationMs,
                                            )
                                        } else {
                                            search.searchSongs(query, page = 1, pageSize = SearchPageSize)
                                        }
                                    }
                                }
                                // 优先源已成功返回或整体超时都会取消在跑的搜索：不算失败，不记日志。
                                if (outcome.exceptionOrNull() is CancellationException) {
                                    return@async emptyList<MusicTrack>()
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
                                        refineBiliCandidates(provider, page.items, sourceTrack)
                                    }
                                }
                            }
                        }
                        val (leadProvider, leadDeferred) = searches.first()
                        val leadRanked = rankCandidates(sourceTrack, leadDeferred.await(), providerPriority)
                            .filterNot { it.candidate.id in attempted }
                        attempted += leadRanked.map { it.candidate.id }
                        val leadTop = leadRanked.firstOrNull()
                            ?.let { "${it.candidate.id.source.storageValue}:${it.score}" } ?: "-"
                        eventLogger(
                            "scored matches song=${request.songId} query=${round + 1} " +
                                "phase=lead source=${leadProvider.source.storageValue}: " +
                                "${leadRanked.size} top=$leadTop",
                        )
                        val leadResult = attemptCandidates(leadRanked.take(LeadPhaseAttemptCap))
                        if (leadResult != null) return@coroutineScope leadResult
                        val restCandidates = searches.drop(1).map { it.second }.awaitAll().flatten()
                        val restRanked = rankCandidates(sourceTrack, restCandidates, providerPriority)
                            .filterNot { it.candidate.id in attempted }
                        attempted += restRanked.map { it.candidate.id }
                        val restTop = restRanked.firstOrNull()
                            ?.let { "${it.candidate.id.source.storageValue}:${it.score}" } ?: "-"
                        eventLogger(
                            "scored matches song=${request.songId} query=${round + 1} " +
                                "phase=rest: ${restRanked.size} top=$restTop",
                        )
                        attemptCandidates(restRanked)
                    }
                    if (phaseResult != null) return@withTimeoutOrNull phaseResult
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

    /**
     * 任务①b：bili 候选精修——时长桶门 + 分P 页级打分（Neri 时长桶过滤/selectNeteaseAutoBiliPage）。
     * 非 bilibili 源原样返回；bili 源无分P能力（单测 fake）时只做桶门。
     * 打分复用 [scoreCandidate]，此处不另立规则。
     */
    private suspend fun refineBiliCandidates(
        provider: MusicProvider,
        items: List<MusicTrack>,
        source: MusicTrack,
    ): List<MusicTrack> {
        if (items.isEmpty()) return items
        val gated = if (provider.source == MusicSource.Bilibili) {
            val result = gateBiliByDurationBucket(items, source.durationMs)
            if (result.size != items.size) {
                eventLogger(
                    "duration bucket gate source=bilibili: dropped ${items.size - result.size}/${items.size} " +
                        "(targetBucket=${bilibiliSearchDurationBucket(source.durationMs)})",
                )
            }
            result
        } else {
            items
        }
        val capability = provider as? PageExpandableCapability ?: return gated
        // 初排不设 70 分门槛：合集视频的种子常被"总时长 -15"压到门槛下，
        // 但展开分P 后命中的页面能翻盘——门槛留给展开后的 rankCandidates。
        val prelim = gated.mapNotNull { candidate ->
            scoreCandidate(source, candidate)?.let { candidate to it }
        }.sortedByDescending { it.second }
            .take(ExpandedCandidateLimit)
            .map { it.first }
        if (prelim.isEmpty()) return gated
        // 并行展开（设计：top3 各自 async view，预算 1.5s/个取并集，最坏 1.5s 而非 4.5s）。
        val winnerById = coroutineScope {
            prelim.map { seed ->
                async { seed.id to expandToBestPage(capability, seed, source) }
            }.awaitAll().toMap()
        }
        return gated.map { winnerById[it.id] ?: it }.distinctBy { it.id }
    }

    /** 展开一个候选的分P，原 track 与各分P 取 scoreCandidate 最高者；超时/异常/无可打分维持原候选。 */
    private suspend fun expandToBestPage(
        capability: PageExpandableCapability,
        seed: MusicTrack,
        source: MusicTrack,
    ): MusicTrack {
        val outcome = runCatching {
            withTimeoutOrNull(PageExpandTimeoutMs) { capability.expandPages(seed) }
        }
        if (outcome.exceptionOrNull() is CancellationException) {
            throw outcome.exceptionOrNull()!!
        }
        val pages = outcome.getOrNull().orEmpty()
        if (pages.isEmpty()) return seed
        val best = (pages + seed)
            .distinctBy { it.id }
            .mapNotNull { candidate -> scoreCandidate(source, candidate)?.let { candidate to it } }
            .maxByOrNull { it.second }
            ?: return seed
        if (best.first.id != seed.id) {
            eventLogger(
                "page expansion source=bilibili: ${seed.id.value} -> ${best.first.id.value} score=${best.second}",
            )
        }
        return best.first
    }

    companion object {
        val EligibleSources = setOf(
            // 候选库只有网易云与 bilibili（需求：其它的平台都不放到音源候选库里）。
            // 任意主源都走同一套回落，网易云主源由 excludeSource 自我排除。
            MusicSource.Netease,
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
// 优先源（pin 的网易云）每轮最多试 3 个候选，防止它全挂时把预算烧光、
// 另一候选源一轮都轮不到（总预算 12，且整体还有 timeoutMs 墙钟限制）。
private const val LeadPhaseAttemptCap = 3
private const val SearchPageSize = 10
// 项2：bili 单独放宽 —— 候选库主力就是它（EligibleSources={Netease,Bilibili}），
// 第 1 页 10 条常被 70 分门槛整批淘汰。一页拿 25 条不增加调用次数、不增加时延，
// 只多一点带宽；B 站 search API page_size 上限 42，BilibiliProvider.searchAll
// 已按 coerceIn(1,50) 处理过同一量级。其余源仍走 SearchPageSize —— 不动共享常数，
// 避免波及网易云/酷狗/酷我/QQ 的调用量（历史教训：候选搜索 14 次全 405 限频）。
private const val BiliSearchPageSize = 25
private const val SearchTimeoutMs = 2_500L
// 任务①b：初排取 top3 分P 展开（并行 view），单候选展开预算 1.5s，超时/失败维持原候选。
private const val ExpandedCandidateLimit = 3
private const val PageExpandTimeoutMs = 1_500L

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

/**
 * 任务①b 时长桶门（Neri 服务端 duration 筛的客户端等价，兜住"去筛重搜"那批混桶结果）：
 * 候选与目标同桶才留；查不出时长的候选不拦；全部异桶时不拦——宁可交给 70 分门槛，
 * 也不能把一次搜索清空（对应 Neri 首轮搜空 → duration=0 重搜后照单全收的语义）。
 */
internal fun gateBiliByDurationBucket(items: List<MusicTrack>, targetDurationMs: Long?): List<MusicTrack> {
    val targetBucket = bilibiliSearchDurationBucket(targetDurationMs)
    if (targetBucket <= 0) return items
    val inBucket = items.filter { candidate ->
        val bucket = bilibiliSearchDurationBucket(candidate.durationMs)
        bucket == 0 || bucket == targetBucket
    }
    return inBucket.ifEmpty { items }
}

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
