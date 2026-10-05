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
import com.lladlam.melox.core.music.provider.PageExpandableCapability
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
        // 候选库只剩网易云/bilibili：网易云（pin 先行）搜到的错歌手候选被硬门丢掉，
        // 正确候选由 bilibili 兜底接住。
        val wrong = track(MusicSource.Netease, artist = "Other Artist")
        val exact = track(MusicSource.Bilibili)
        val resolver = resolver(
            FakeProvider(MusicSource.Netease, listOf(wrong), playable = true),
            FakeProvider(MusicSource.Bilibili, listOf(exact), playable = true),
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Bilibili, result?.source)
        assertEquals(exact.id.value, result?.resourceId)
        assertEquals("https://audio.example/${exact.id.value}", result?.url)
    }

    @Test
    fun doesNotUsePreviewOrWrongArtist() {
        // 错歌手在网易云侧被硬门丢弃；bilibili 侧只有试听片段——两边都接不住，必须 null。
        val resolver = resolver(
            FakeProvider(
                MusicSource.Netease,
                listOf(track(MusicSource.Netease, artist = "Other Artist")),
                playable = true,
            ),
            FakeProvider(MusicSource.Bilibili, listOf(track(MusicSource.Bilibili)), playable = false),
        )

        assertNull(resolver.resolve(request()))
    }

    @Test
    fun unknownDurationUsesExactTitleAndCompleteArtistMatch() {
        // 时长未知时，完整歌手名单必须排到部分名单前面（+10 完整分）。
        // 两候选放同一源内验证：两阶段搜索下 lead 源（pin 网易云恒第一）可播即收，
        // 跨源"更优分胜出"已不是保证，名单对比只在同源候选间仍有意义。
        val partialArtists = track(
            source = MusicSource.Netease,
            artist = "Primary Artist",
        )
        val exact = track(
            source = MusicSource.Netease,
            artists = listOf("Primary Artist", "Guest"),
        )
        val resolver = resolver(
            FakeProvider(MusicSource.Netease, listOf(partialArtists, exact), playable = true),
        )

        val result = resolver.resolve(
            request().copy(
                artist = "Primary Artist / Guest",
                durationMs = null,
            ),
        )

        assertEquals(MusicSource.Netease, result?.source)
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
        // 远程 disabledProviders 把网易云关掉后，候选库里只剩 bilibili。
        val netease = track(MusicSource.Netease)
        val bili = track(MusicSource.Bilibili)
        val resolver = CrossProviderPlaybackFallbackResolver(
            enabledProvider = { true },
            registryProvider = {
                MusicProviderRegistry(
                    listOf(
                        FakeProvider(MusicSource.Netease, listOf(netease), playable = true),
                        FakeProvider(MusicSource.Bilibili, listOf(bili), playable = true),
                    ),
                )
            },
            fallbackConfigProvider = {
                MeloXRemoteConfigDefaults.Config.fallback.copy(
                    order = listOf("bilibili", "netease"),
                    disabledProviders = setOf("netease"),
                )
            },
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Bilibili, result?.source)
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
        // 网易云排前（lead）且能播，但只给 Standard（低于请求的 HiResolution）→ 不许吃差音质，
        // 换下一家按请求音质回报的 bilibili。（没有门槛时旧逻辑会取网易云。）
        val netease = track(MusicSource.Netease)
        val bili = track(MusicSource.Bilibili)
        val resolver = resolver(
            FakeProvider(
                MusicSource.Netease,
                listOf(netease),
                playable = true,
                reportQuality = AudioQualityTier.Standard,
            ),
            FakeProvider(MusicSource.Bilibili, listOf(bili), playable = true),
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Bilibili, result?.source)
        assertEquals(bili.id.value, result?.resourceId)
    }

    @Test
    fun bilibiliFallbackExemptsQualityGate() {
        // bilibili 豁免音质门槛：即使它报 Standard（低于请求的 HiResolution）也直接收。
        val bili = track(MusicSource.Bilibili)
        val resolver = resolver(
            FakeProvider(
                MusicSource.Bilibili,
                listOf(bili),
                playable = true,
                reportQuality = AudioQualityTier.Standard,
            ),
        )

        val result = resolver.resolve(request())

        assertEquals(MusicSource.Bilibili, result?.source)
        assertEquals(bili.id.value, result?.resourceId)
    }

    @Test
    fun bilibiliDurationBucketDropsOutOfBucketCandidate() {
        // 任务①b 时长桶门：目标 200s=桶1。同桶候选 A 只有 72 分，异桶候选 B（11min=桶2）
        // 有 75 分——没有桶门时 B 会胜出，门必须先把它丢掉 A 才是最终命中。
        val inBucket = track(
            source = MusicSource.Bilibili,
            title = "Song A cover",
            artists = listOf("The Artist Band"),
            durationMs = 240_000L,
        )
        val outOfBucket = track(
            source = MusicSource.Bilibili,
            title = "A Song Live",
            durationMs = 660_000L,
        )
        val resolver = resolver(
            FakeProvider(MusicSource.Bilibili, listOf(outOfBucket, inBucket), playable = true),
        )

        val result = resolver.resolve(request())

        assertEquals(inBucket.id.value, result?.resourceId)
    }

    @Test
    fun bilibiliDurationBucketKeepsAllWhenNoneInBucket() {
        // 全部异桶时不拦（Neri 首轮搜空 → 去筛重搜照单全收的语义）：
        // 只有 45min 桶3 候选也必须能命中，交 70 分门槛定夺。
        val onlyOutOfBucket = track(
            source = MusicSource.Bilibili,
            title = "A Song Live",
            durationMs = 2_700_000L,
        )
        val resolver = resolver(
            FakeProvider(MusicSource.Bilibili, listOf(onlyOutOfBucket), playable = true),
        )

        val result = resolver.resolve(request())

        assertEquals(onlyOutOfBucket.id.value, result?.resourceId)
    }

    @Test
    fun bilibiliPageExpansionPicksBestMatchingPart() {
        // 任务①b 页级打分（Neri selectNeteaseAutoBiliPage）：种子是 40min 合集总时长
        // （时长 -15 压到 40 分过不了 70 门槛）；expandPages 返回 [种子, 分P] 时必须按
        // argmax 选中 200s 精确命中的分P（85 分）并以分P 的 id 解析；取首个会得到种子而失败。
        val seed = track(
            source = MusicSource.Bilibili,
            artists = listOf("SomeUP"),
            durationMs = 2_400_000L,
        )
        val part = track(
            source = MusicSource.Bilibili,
            title = "A Song - 副歌",
            artists = listOf("SomeUP"),
            durationMs = 200_000L,
        )
        val resolver = resolver(
            FakeBiliPageProvider(listOf(seed), pages = listOf(seed, part)),
        )

        val result = resolver.resolve(request())

        assertEquals(part.id.value, result?.resourceId)
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

    private open class FakeProvider(
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

    /** 任务①b：带分P 展开能力的 bili fake（桶门看 source，展开看能力接口）。 */
    private class FakeBiliPageProvider(
        tracks: List<MusicTrack>,
        private val pages: List<MusicTrack>,
    ) : FakeProvider(MusicSource.Bilibili, tracks, playable = true), PageExpandableCapability {
        override suspend fun expandPages(track: MusicTrack): List<MusicTrack> = pages
    }
}
