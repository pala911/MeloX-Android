package com.lladlam.melox.playback

import com.lladlam.melox.core.music.model.MusicSource

/**
 * One playable answer plus the quality it actually sounds at. `null` quality
 * means unknown and counts as "meets the requested bar" - the same convention
 * the cross-provider gate uses.
 *
 * Shared by both resolver layers so the NetEase chain and the provider chain
 * pick candidates with exactly the same quality-first rules. [E] is the layer's
 * own quality type (MusicQuality for the NetEase resolver, AudioQualityTier for
 * provider tracks) - comparison is by ordinal, which matches tier order in both
 * enums - and [R] is that layer's resolved-request type.
 */
internal data class QualityCandidate<E, R>(
    val request: R,
    val actualQuality: E?,
    /** Set only for cross-provider picks; their records apply once they win. */
    val fallbackSource: MusicSource? = null,
)

internal fun <E : Enum<E>, R> QualityCandidate<E, R>.meetsRequested(requested: E): Boolean =
    actualQuality?.let { it.ordinal >= requested.ordinal } ?: true

/**
 * Quality-first pick between the third-party sources (LX then CHKSZ) and the
 * cross-provider pool (bilibili). A candidate meeting the user's quality wins -
 * third-party preferred - otherwise the higher actual quality is compared, ties
 * going to the third-party side. Null only when neither side produced anything.
 */
internal fun <E : Enum<E>, R> selectCandidate(
    requested: E,
    thirdParty: QualityCandidate<E, R>?,
    fallback: QualityCandidate<E, R>?,
): QualityCandidate<E, R>? {
    if (thirdParty == null) return fallback
    if (fallback == null) return thirdParty
    if (thirdParty.meetsRequested(requested)) return thirdParty
    if (fallback.meetsRequested(requested)) return fallback
    val triTier = thirdParty.actualQuality ?: requested
    val fbTier = fallback.actualQuality ?: requested
    return if (triTier.ordinal >= fbTier.ordinal) thirdParty else fallback
}
