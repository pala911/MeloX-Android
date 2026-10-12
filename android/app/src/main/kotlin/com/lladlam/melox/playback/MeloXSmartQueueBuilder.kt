package com.lladlam.melox.playback

import androidx.media3.common.MediaItem

/** Holds playlist candidates outside Media3 so the visible queue grows one match at a time. */
object MeloXSmartQueueBuilder {
    data class AnalysisSummary(val bpm: Double, val confidence: Double)
    enum class MatchReason { CompatibleTempo, HighestConfidence }
    data class Match(
        val item: MediaItem,
        val analysis: AnalysisSummary,
        val tempoDistance: Double,
        val reason: MatchReason,
    )

    private val lock = Any()
    private var candidates = mutableListOf<MediaItem>()
    private val consumedIds = linkedSetOf<String>()
    private val analyses = mutableMapOf<String, AnalysisSummary>()
    private val failedIds = mutableSetOf<String>()
    /** 允许「下载 + 算 BPM」的候选 id；空集 = 不限额（旧行为，全量分析）。 */
    private val analysisTargets = mutableSetOf<String>()
    /** `begin()` 的 startIndex 之后的条目 id：真正会被播放的候选（历史条目永远不会轮到）。 */
    private val futureIds = mutableSetOf<String>()
    private var generation = 0L

    fun begin(items: List<MediaItem>, selectedIndex: Int): MediaItem {
        val selected = items[selectedIndex.coerceIn(items.indices)]
        synchronized(lock) {
            generation += 1L
            consumedIds.clear()
            consumedIds += selected.mediaId
            candidates = items.filterIndexed { index, _ -> index != selectedIndex }.toMutableList()
            futureIds.clear()
            items.drop(selectedIndex + 1).forEach { futureIds += it.mediaId }
            analyses.clear()
            failedIds.clear()
            analysisTargets.clear()
        }
        return selected
    }

    /** 最新入链的条目 id（消费顺序，`consumedIds` 是 LinkedHashSet）。 */
    fun tailMediaId(): String? = synchronized(lock) { consumedIds.lastOrNull() }

    fun snapshot(): CandidateSnapshot = synchronized(lock) {
        val remaining = candidates.filterNot { it.mediaId in consumedIds }
        // 只把「后面还没播到的」候选交给分析：startIndex 之前的历史条目永远不会被播放，
        // 下载它们纯粹是带宽/IO 浪费（begin() 里 candidates 本来就把它们算进候选池）。
        CandidateSnapshot(generation, remaining, remaining.filter { it.mediaId in futureIds })
    }

    fun setAnalysisTargets(expectedGeneration: Long, mediaIds: Collection<String>) = synchronized(lock) {
        if (generation == expectedGeneration) {
            analysisTargets.clear()
            analysisTargets += mediaIds
        }
    }

    fun consume(expectedGeneration: Long, mediaId: String): MediaItem? = synchronized(lock) {
        if (generation != expectedGeneration || mediaId in consumedIds) return@synchronized null
        val index = candidates.indexOfFirst { it.mediaId == mediaId }
        if (index < 0) return@synchronized null
        consumedIds += mediaId
        candidates.removeAt(index)
    }

    fun recordAnalysis(expectedGeneration: Long, mediaId: String, analysis: MeloXAutoMixTrackAnalysis) =
        synchronized(lock) {
            if (generation == expectedGeneration) {
                analyses[mediaId] = AnalysisSummary(analysis.bpm, analysis.confidence)
            }
        }

    fun bestAnalyzedMatch(
        expectedGeneration: Long,
        sourceId: String,
    ): Match? = synchronized(lock) {
        if (generation != expectedGeneration) return@synchronized null
        val source = analyses[sourceId]
        if (source == null && sourceId !in failedIds) return@synchronized null
        val remaining = candidates.filterNot { it.mediaId in consumedIds || it.mediaId in failedIds }
        // 兜底判定要看「本该被分析的候选」而非全部候选：开了限额后 remaining 尾部还躺着
        // 未纳入分析的条目，若拿 remaining 比大小，HighestConfidence 兜底就永远不会触发。
        val eligible = if (analysisTargets.isEmpty()) remaining
            else remaining.filter { it.mediaId in analysisTargets }
        val analyzed = remaining.mapNotNull { item ->
            analyses[item.mediaId]?.let { candidate ->
                Match(
                    item = item,
                    analysis = candidate,
                    tempoDistance = source?.let { compatibleTempoDistance(it.bpm, candidate.bpm) } ?: Double.POSITIVE_INFINITY,
                    reason = MatchReason.CompatibleTempo,
                )
            }
        }
        analyzed
            .asSequence()
            .filter { it.tempoDistance <= COMPATIBLE_TEMPO_DISTANCE_BPM }
            .minByOrNull { match ->
                match.tempoDistance + (1.0 - match.analysis.confidence) * 4.0
            }
            ?: if (analyzed.size == eligible.size && eligible.isNotEmpty()) {
                analyzed.maxByOrNull { it.analysis.confidence }?.copy(
                    reason = MatchReason.HighestConfidence,
                )
            } else null
    }

    /** DJ tempo matching treats half-time and double-time estimates as equivalent. */
    internal fun compatibleTempoDistance(sourceBpm: Double, candidateBpm: Double): Double =
        listOf(candidateBpm * .5, candidateBpm, candidateBpm * 2.0)
            .minOf { adjusted -> kotlin.math.abs(sourceBpm - adjusted) }

    private const val COMPATIBLE_TEMPO_DISTANCE_BPM = 8.0

    fun firstRemaining(expectedGeneration: Long): MediaItem? = synchronized(lock) {
        if (generation != expectedGeneration) null
        else candidates.firstOrNull { it.mediaId !in consumedIds && it.mediaId !in failedIds }
    }

    fun recordFailure(expectedGeneration: Long, mediaId: String) = synchronized(lock) {
        if (generation == expectedGeneration) failedIds.add(mediaId)
    }

    fun emergencyCandidate(expectedGeneration: Long): MediaItem? = synchronized(lock) {
        if (generation != expectedGeneration) return@synchronized null
        val remaining = candidates.filterNot { it.mediaId in consumedIds || it.mediaId in failedIds }
        remaining.filter { it.mediaId in analyses }.maxByOrNull { analyses.getValue(it.mediaId).confidence }
            ?: remaining.firstOrNull()
    }

    fun reset() = synchronized(lock) {
        generation += 1L
        candidates.clear()
        consumedIds.clear()
        analyses.clear()
        failedIds.clear()
        futureIds.clear()
        analysisTargets.clear()
    }
}

data class CandidateSnapshot(
    val generation: Long,
    /** 全部未消费的候选（含历史条目），保留旧语义。 */
    val items: List<MediaItem>,
    /** startIndex 之后、真正会轮到播放的候选——限额只作用于它。 */
    val futureItems: List<MediaItem>,
)
