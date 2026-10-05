package com.lladlam.melox.playback

import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicPage
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.model.TrackAvailability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.MusicProviderRegistry
import com.lladlam.melox.core.music.provider.MusicCapability
import com.lladlam.melox.core.music.provider.PlaybackCapability
import com.lladlam.melox.core.music.provider.SearchCapability
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossProviderPlaybackFallbackResolverTest {
    @Test
    fun resolvesOnlyStrictFullPlaybackMatch() {
        val wrong = track(MusicSource.QQMusic, artist = "Other Artist")
        val exact = track(MusicSource.Kugou)
        val resolver = resolver(
            FakeProvider(MusicSource.QQMusic, listOf(wrong), playable = true),
            FakeProvider(MusicSource.Kugou, listOf(exact), playable = true),
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Kugou, result?.source)
        assertEquals(exact.id.value, result?.resourceId)
        assertEquals("https://audio.example/${exact.id.value}", result?.url)
    }

    @Test
    fun doesNotUsePreviewOrWrongArtist() {
        val resolver = resolver(
            FakeProvider(MusicSource.QQMusic, listOf(track(MusicSource.QQMusic)), playable = false),
            FakeProvider(
                MusicSource.Kugou,
                listOf(track(MusicSource.Kugou, artist = "Other Artist")),
                playable = true,
            ),
        )

        assertNull(resolver.resolve(request()))
    }

    @Test
    fun unknownDurationUsesExactTitleAndCompleteArtistMatch() {
        // 时长未知时，完整歌手名单必须排到部分名单前面（+10 完整分）。
        // 两候选放同一源内验证：两阶段搜索下 lead 源（bili 恒第一）可播即收，
        // 跨源"更优分胜出"已不是保证，名单对比只在同源候选间仍有意义。
        val partialArtists = track(
            source = MusicSource.QQMusic,
            artist = "Primary Artist",
        )
        val exact = track(
            source = MusicSource.QQMusic,
            artists = listOf("Primary Artist", "Guest"),
        )
        val resolver = resolver(
            FakeProvider(MusicSource.QQMusic, listOf(partialArtists, exact), playable = true),
        )

        val result = resolver.resolve(
            request().copy(
                artist = "Primary Artist / Guest",
                durationMs = null,
            ),
        )

        assertEquals(MusicSource.QQMusic, result?.source)
        assertEquals(exact.id.value, result?.resourceId)
    }

    @Test
    fun disabledSettingDoesNotIssueSearch() {
        val provider = FakeProvider(MusicSource.QQMusic, listOf(track(MusicSource.QQMusic)), playable = true)
        val resolver = CrossProviderPlaybackFallbackResolver(
            enabledProvider = { false },
            registryProvider = { MusicProviderRegistry(listOf(provider)) },
        )

        assertNull(resolver.resolve(request()))
        assertEquals(0, provider.searchCount)
    }

    @Test
    fun remotePolicyExcludesDisabledProviderAndControlsOrder() {
        val qq = track(MusicSource.QQMusic)
        val kugou = track(MusicSource.Kugou)
        val resolver = CrossProviderPlaybackFallbackResolver(
            enabledProvider = { true },
            registryProvider = {
                MusicProviderRegistry(
                    listOf(
                        FakeProvider(MusicSource.QQMusic, listOf(qq), playable = true),
                        FakeProvider(MusicSource.Kugou, listOf(kugou), playable = true),
                    ),
                )
            },
            fallbackConfigProvider = {
                MeloXRemoteConfigDefaults.Config.fallback.copy(
                    order = listOf("kugou", "qq_music", "bilibili"),
                    disabledProviders = setOf("qq_music"),
                )
            },
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Kugou, result?.source)
    }

    @Test
    fun artistListPreservesPrimaryArtistOrder() {
        assertEquals(
            listOf("Primary Artist", "Guest"),
            CrossProviderPlaybackFallbackResolver.splitArtists("Primary Artist / Guest"),
        )
    }

    @Test
    fun qualityGateRejectsLowerQualityCandidateAndUsesNextSource() {
        // qq 排前（lead）且能播，但只给 Standard（低于请求的 HiResolution）→ 不许吃差音质，
        // 换下一家按请求音质回报的 kugou。（没有门槛时旧逻辑会取 qq。）
        val qq = track(MusicSource.QQMusic)
        val kugou = track(MusicSource.Kugou)
        val resolver = resolver(
            FakeProvider(
                MusicSource.QQMusic,
                listOf(qq),
                playable = true,
                reportQuality = AudioQualityTier.Standard,
            ),
            FakeProvider(MusicSource.Kugou, listOf(kugou), playable = true),
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Kugou, result?.source)
        assertEquals(kugou.id.value, result?.resourceId)
    }

    @Test
    fun bilibiliFallbackExemptsQualityGate() {
        // bilibili 固定最先兜底且豁免音质：即使它报 Standard 也直接收，不轮到 kugou。
        val bili = track(MusicSource.Bilibili)
        val kugou = track(MusicSource.Kugou)
        val resolver = resolver(
            FakeProvider(
                MusicSource.Bilibili,
                listOf(bili),
                playable = true,
                reportQuality = AudioQualityTier.Standard,
            ),
            FakeProvider(MusicSource.Kugou, listOf(kugou), playable = true),
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Bilibili, result?.source)
        assertEquals(bili.id.value, result?.resourceId)
    }

    private fun resolver(vararg providers: MusicProvider) = CrossProviderPlaybackFallbackResolver(
        enabledProvider = { true },
        registryProvider = { MusicProviderRegistry(providers.asList()) },
    )

    private fun request() = CrossProviderFallbackRequest(
        songId = 100L,
        title = "A Song",
        artist = "The Artist",
        durationMs = 200_000L,
        quality = AudioQualityTier.HiResolution,
    )

    private fun track(
        source: MusicSource,
        title: String = "A Song",
        artist: String = "The Artist",
        artists: List<String> = listOf(artist),
        durationMs: Long = 200_000L,
    ) = MusicTrack(
        id = MusicResourceId(source, "${source.storageValue}-$title-$artist"),
        title = title,
        artists = artists.map { MusicArtistRef(name = it) },
        durationMs = durationMs,
        availability = TrackAvailability.Playable,
    )

    private class FakeProvider(
        override val source: MusicSource,
        private val tracks: List<MusicTrack>,
        private val playable: Boolean,
        private val reportQuality: AudioQualityTier? = null,
    ) : MusicProvider, SearchCapability, PlaybackCapability {
        override val displayName = source.displayName
        override val capabilities = setOf(MusicCapability.Search, MusicCapability.Playback)
        var searchCount = 0
            private set

        override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
            searchCount += 1
            assertTrue(query.contains("A Song"))
            return MusicPage(tracks, page, pageSize, tracks.size.toLong())
        }

        override suspend fun resolvePlayback(
            track: MusicTrack,
            quality: AudioQualityTier,
        ): PlaybackResolution = if (playable) {
            PlaybackResolution.Playable(
                url = "https://audio.example/${track.id.value}",
                requestHeaders = mapOf("Referer" to "https://example.com/"),
                requestedQuality = quality,
                // 默认按请求音质回报，保持既有用例聚焦匹配/排序意图；
                // 音质门槛（低于请求不收）由 qualityGate*/bilibili* 两个用例显式覆盖。
                actualQuality = reportQuality ?: quality,
            )
        } else {
            PlaybackResolution.Preview("https://preview.example/${track.id.value}")
        }
    }
}
