package com.lladlam.melox.core.provider.bilibili

import com.lladlam.melox.core.music.model.*
import com.lladlam.melox.core.music.provider.*
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Neri `durationFilterForBiliSearch` 蓝本：目标时长 → B站搜索时长桶
 * （0=不筛，1=<10min，2=10-30min，3=30-60min，4=>=60min）。
 * 搜索侧 duration 参数与跨源兜底的客户端桶门共用这一份实现，保证两侧口径一致。
 */
internal fun bilibiliSearchDurationBucket(durationMs: Long?): Int = when {
    durationMs == null || durationMs <= 0L -> 0
    durationMs < 10 * 60_000L -> 1
    durationMs < 30 * 60_000L -> 2
    durationMs < 60 * 60_000L -> 3
    else -> 4
}

class BilibiliProvider(
    private val sessionProvider: () -> BilibiliSession,
    private val httpClient: OkHttpClient = com.lladlam.melox.core.network.MeloXHttpClient.shared,
    private val associationProvider: (String, Long) -> BilibiliPlaybackAssociation? = { _, _ -> null },
    private val apiCache: BilibiliApiCache? = null,
    private val sessionRevisionProvider: () -> Long = { 0L },
) : MusicProvider, SearchCapability, PlaybackCapability, UserLibraryCapability, PlaylistCapability, PageExpandableCapability {
    override val source = MusicSource.Bilibili
    override val displayName = source.displayName
    override val capabilities = setOf(MusicCapability.Search, MusicCapability.Playback, MusicCapability.Library, MusicCapability.Playlists)

    override suspend fun accountSummary(): MusicAccountSummary? {
        val session = sessionProvider()
        if (!session.isLoggedIn) return null
        val data = runCatching {
            cachedGet("account", "/x/web-interface/nav", emptyMap(), BilibiliApiCache.Account).data()
        }.getOrNull()
        val mid = data?.optLong("mid")?.takeIf { it > 0 }?.toString()
            ?: session.userId
        return MusicAccountSummary(
            source,
            mid,
            data?.optString("uname").orEmpty().ifBlank { "Bilibili 用户 $mid" },
            secure(data?.optString("face").orEmpty()),
        )
    }

    override suspend fun userPlaylists(page: Int, pageSize: Int): MusicPage<MusicPlaylistSummary> {
        val session = sessionProvider()
        if (!session.isLoggedIn) return MusicPage(emptyList(), page, pageSize, 0)
        val list = cachedGet(
            "folders", "/x/v3/fav/folder/created/list-all",
            mapOf("up_mid" to session.userId, "type" to "2"), BilibiliApiCache.Folders,
        )
            .data().optJSONArray("list") ?: JSONArray()
        val all = list.objects().mapNotNull { item ->
            val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            MusicPlaylistSummary(
                MusicResourceId(source, id.toString()), item.optString("title"), secure(item.optString("cover")),
                trackCount = item.optInt("media_count"),
            )
        }
        val start = ((page.coerceAtLeast(1) - 1) * pageSize).coerceAtMost(all.size)
        val selected = all.drop(start).take(pageSize).map { playlist ->
            runCatching { playlistSummaryWithCover(playlist) }.getOrDefault(playlist)
        }
        return MusicPage(selected, page, pageSize, all.size.toLong())
    }

    override suspend fun playlistDetail(playlist: MusicPlaylistSummary, page: Int, pageSize: Int): MusicPlaylistDetail {
        val params = mapOf(
            "media_id" to playlist.id.value, "pn" to page.toString(), "ps" to pageSize.coerceIn(1, 20).toString(),
            "order" to "mtime", "tid" to "0", "type" to "0", "platform" to "web",
        )
        val data = cachedGet("folder-detail", "/x/v3/fav/resource/list", params, BilibiliApiCache.FolderDetail).data()
        val medias = data.optJSONArray("medias") ?: JSONArray()
        val tracks = medias.objects().flatMap { media ->
            val bvid = media.optString("bvid")
            if (bvid.isBlank()) emptyList() else videoTracks(bvid, media)
        }
        val info = data.optJSONObject("info")
        val summary = playlist.copy(
            title = info?.optString("title").orEmpty().ifBlank { playlist.title },
            artworkUrl = secure(info?.optString("cover").orEmpty())
                ?: secure(medias.optJSONObject(0)?.optString("cover").orEmpty())
                ?: playlist.artworkUrl,
            creatorName = info?.optJSONObject("upper")?.optString("name").orEmpty()
                .ifBlank { playlist.creatorName.orEmpty() }
                .ifBlank { null },
            description = info?.optString("intro").orEmpty()
                .ifBlank { playlist.description.orEmpty() }
                .ifBlank { null },
            trackCount = info?.optInt("media_count")?.takeIf { it >= 0 } ?: playlist.trackCount,
        )
        return MusicPlaylistDetail(summary, tracks, info?.optLong("media_count"))
    }

    private fun playlistSummaryWithCover(playlist: MusicPlaylistSummary): MusicPlaylistSummary {
        if (!playlist.artworkUrl.isNullOrBlank()) return playlist
        val params = mapOf(
            "media_id" to playlist.id.value, "pn" to "1", "ps" to "20",
            "order" to "mtime", "tid" to "0", "type" to "0", "platform" to "web",
        )
        val data = cachedGet("folder-detail", "/x/v3/fav/resource/list", params, BilibiliApiCache.FolderDetail).data()
        val cover = secure(data.optJSONObject("info")?.optString("cover").orEmpty())
            ?: secure(data.optJSONArray("medias")?.optJSONObject(0)?.optString("cover").orEmpty())
        return playlist.copy(artworkUrl = cover)
    }

    override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> =
        searchSongsInternal(query, page, pageSize, durationBucket = 0)

    /**
     * Neri 蓝本（NeteaseAutoSourceSwitch.fetchBiliAutoSourceCandidates）：按目标时长先带
     * `duration` 桶筛（B站搜索 API：0=全部 1=<10min 2=10-30min 3=30-60min 4=>60min），
     * 首轮搜空再把筛去掉重搜一次（等价 duration=0；重搜不传该参数，缓存键与旧调用一致）。
     */
    suspend fun searchSongs(query: String, page: Int, pageSize: Int, durationMs: Long?): MusicPage<MusicTrack> {
        val bucket = bilibiliSearchDurationBucket(durationMs)
        if (bucket <= 0) return searchSongsInternal(query, page, pageSize, durationBucket = 0)
        val filtered = searchSongsInternal(query, page, pageSize, durationBucket = bucket)
        if (filtered.items.isNotEmpty()) return filtered
        return searchSongsInternal(query, page, pageSize, durationBucket = 0)
    }

    private suspend fun searchSongsInternal(
        query: String,
        page: Int,
        pageSize: Int,
        durationBucket: Int,
    ): MusicPage<MusicTrack> {
        if (query.isBlank()) return MusicPage(emptyList(), page, pageSize, 0)
        val params = mapOf(
            "keyword" to BilibiliApiCache.normalizeSearchQuery(query), "search_type" to "video",
            "page" to page.toString(), "page_size" to pageSize.toString(),
        ) + (if (durationBucket > 0) mapOf("duration" to durationBucket.toString()) else emptyMap())
        val data = cachedWbiGet("search", "/x/web-interface/wbi/search/type", params, BilibiliApiCache.Search).data()
        val results = data.optJSONArray("result") ?: JSONArray()
        val tracks = results.objects().mapNotNull { item ->
            val bvid = item.optString("bvid").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val itemCid = item.optLong("cid").takeIf { it > 0 }
            // page=1 硬编码修正：条目缺 cid 才回退 view()——此时按 cid 反查分P 页码/part/时长
            // （搜索条目的 duration 是合集总时长，part 时长才是本P 的，供打分用）。
            val viewJson = if (itemCid == null) runCatching { view(bvid) }.getOrNull() else null
            val cid = itemCid ?: viewJson?.optLong("cid")?.takeIf { it > 0 } ?: return@mapNotNull null
            val pageMeta = viewJson?.optJSONArray("pages")?.objects()
                ?.firstOrNull { it.optLong("cid") == cid }
            mapTrack(
                item, bvid, cid, item.optLong("aid"),
                pageMeta?.optInt("page")?.takeIf { it > 0 } ?: 1,
                part = pageMeta?.optString("part")?.takeIf(String::isNotBlank),
                durationMs = pageMeta?.optLong("duration")?.takeIf { it > 0 }?.times(1000),
                owner = viewJson?.optJSONObject("owner")?.optString("name")?.takeIf(String::isNotBlank),
            )
        }
        return MusicPage(tracks, page, pageSize, data.optLong("numResults").takeIf { it > 0 })
    }

    suspend fun searchReplacementCandidates(query: String, pageSize: Int = 20): List<MusicTrack> =
        searchSongs(query, page = 1, pageSize = pageSize.coerceIn(1, 50)).items

    override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution {
        val metadata = track.providerMetadata as? ProviderTrackMetadata.Bilibili
            ?: parseIdentity(track.id.value)?.let { ProviderTrackMetadata.Bilibili(it.first, it.second) }
            ?: return PlaybackResolution.Unavailable("Bilibili 资源身份缺少 bvid+cid")
        val association = associationProvider(metadata.bvid, metadata.cid)
        val physicalBvid = association?.replacementBvid ?: metadata.bvid
        val physicalCid = association?.replacementCid ?: metadata.cid
        val params = mapOf(
            "bvid" to physicalBvid, "cid" to physicalCid.toString(), "qn" to "127", "fnval" to "4048",
            "fnver" to "0", "fourk" to "1",
        )
        var response = wbiGet("/x/player/wbi/playurl", params, retry403 = false)
        if (response.optInt("code") == -403) {
            apiCache?.invalidateMixin(sessionScope())
            response = wbiGet("/x/player/wbi/playurl", params, retry403 = false)
        }
        val data = response.data()
        val candidates = buildList {
            addAudio(data.optJSONObject("dash")?.optJSONArray("audio"))
            addAudio(data.optJSONObject("dash")?.optJSONObject("flac")?.opt("audio"))
            addAudio(data.optJSONObject("dash")?.optJSONObject("dolby")?.optJSONArray("audio"))
        }.distinctBy { it.id to it.url }
            .filter { it.id in SafeAndroidAudioIds }
        val selected = quality.bilibiliAudioPreference()
            .firstNotNullOfOrNull { preferredId -> candidates.firstOrNull { it.id == preferredId } }
            ?: candidates.firstOrNull()
            ?: return PlaybackResolution.Unavailable("Bilibili 没有返回 DASH 音频")
        val cookie = sessionProvider().cookie
        android.util.Log.i(
            "YSYYBilibiliPlayback",
            "selected bvid=$physicalBvid cid=$physicalCid requested=$quality " +
                "audioId=${selected.id} mime=${selected.mime} bandwidth=${selected.bandwidth} " +
                "anonymous=${cookie.isBlank()}",
        )
        // 公开视频的 playurl 与 CDN 拉流都不需要登录（PC 探针实测：无 cookie 时 playurl code=0
        // 且返回 30232/30280/30216，Range 请求 206 + 合法 ftyp）。所以只在有 Cookie 时才带上，
        // cookie 为空不再 return LoginRequired —— 那道门槛等于把 bilibili 这条兜底路完全掐死。
        val requestHeaders = mutableMapOf(
            "Referer" to "https://www.bilibili.com/video/$physicalBvid",
            "User-Agent" to UserAgent,
        )
        if (cookie.isNotBlank()) requestHeaders["Cookie"] = cookie
        return PlaybackResolution.Playable(
            url = selected.url,
            requestHeaders = requestHeaders,
            requestedQuality = quality,
            actualQuality = selected.id.bilibiliTier(),
            bitrate = selected.bandwidth,
            format = selected.mime,
            expiresAtEpochMs = BilibiliApiCache.playbackExpiry(selected.url),
        )
    }

    private fun videoTracks(bvid: String, fallback: JSONObject): List<MusicTrack> {
        val view = view(bvid)
        val pages = view.optJSONArray("pages") ?: JSONArray()
        return pages.objects().mapIndexedNotNull { index, page ->
            val cid = page.optLong("cid").takeIf { it > 0 } ?: return@mapIndexedNotNull null
            mapTrack(
                fallback, bvid, cid, view.optLong("aid"), page.optInt("page", index + 1),
                page.optString("part").takeIf(String::isNotBlank), page.optLong("duration").takeIf { it > 0 }?.times(1000),
                view.optJSONObject("owner")?.optString("name"), view.optString("pic"),
            )
        }
    }

    /**
     * 页级打分的候选重建（Neri selectNeteaseAutoBiliPage）：按分P 把视频展开成 track 列表，
     * 标题=视频标题 - part、时长=该 P 时长、页码入 metadata；由跨源兜底对原 track 与各分P
     * 用同一套 scoreCandidate 取最高者，命中的即为「选中后时长回写」（part 时长进打分与解析）。
     */
    override suspend fun expandPages(track: MusicTrack): List<MusicTrack> {
        val metadata = track.providerMetadata as? ProviderTrackMetadata.Bilibili
            ?: parseIdentity(track.id.value)?.let { ProviderTrackMetadata.Bilibili(it.first, it.second) }
            ?: return listOf(track)
        val viewJson = runCatching { view(metadata.bvid) }.getOrNull() ?: return listOf(track)
        val pages = viewJson.optJSONArray("pages") ?: return listOf(track)
        val owner = viewJson.optJSONObject("owner")?.optString("name")?.takeIf(String::isNotBlank)
        val pic = secure(viewJson.optString("pic"))?.takeIf { it.isNotBlank() } ?: track.artworkUrl
        val baseTitle = track.title
        val artists = owner?.let { listOf(MusicArtistRef(name = it)) } ?: track.artists
        return pages.objects().mapIndexedNotNull { index, page ->
            val cid = page.optLong("cid").takeIf { it > 0 } ?: return@mapIndexedNotNull null
            val part = page.optString("part").takeIf(String::isNotBlank)
            MusicTrack(
                MusicResourceId(source, "${metadata.bvid}:$cid"),
                part?.takeIf { it != baseTitle }?.let { "$baseTitle - $it" } ?: baseTitle,
                artists,
                artworkUrl = pic,
                durationMs = page.optLong("duration").takeIf { it > 0 }?.times(1000) ?: track.durationMs,
                availability = TrackAvailability.Playable,
                providerMetadata = ProviderTrackMetadata.Bilibili(
                    metadata.bvid,
                    cid,
                    viewJson.optLong("aid").takeIf { it > 0 } ?: metadata.aid,
                    page.optInt("page", index + 1),
                ),
            )
        }.ifEmpty { listOf(track) }
    }

    private fun view(bvid: String) = cachedGet(
        "view", "/x/web-interface/view", mapOf("bvid" to bvid), BilibiliApiCache.View,
    ).data()

    private fun mapTrack(item: JSONObject, bvid: String, cid: Long, aid: Long, page: Int, part: String? = null,
                         durationMs: Long? = null, owner: String? = null, pic: String? = null): MusicTrack {
        val baseTitle = cleanTitle(item.optString("title").ifBlank { "Bilibili 视频" })
        val title = part?.takeIf { it != baseTitle }?.let { "$baseTitle - $it" } ?: baseTitle
        val artist = owner.orEmpty().ifBlank { item.optString("author").ifBlank { item.optString("upper_name") }.ifBlank { "未知 UP 主" } }
        return MusicTrack(
            MusicResourceId(source, "$bvid:$cid"), title, listOf(MusicArtistRef(name = artist)),
            artworkUrl = secure(pic.orEmpty().ifBlank { item.optString("pic").ifBlank { item.optString("cover") } }),
            durationMs = durationMs ?: parseDuration(item.optString("duration")), availability = TrackAvailability.Playable,
            providerMetadata = ProviderTrackMetadata.Bilibili(bvid, cid, aid.takeIf { it > 0 }, page),
        )
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = request(path, params)
    private fun cachedGet(operation: String, path: String, params: Map<String, String>, policy: BilibiliApiCache.Policy): JSONObject {
        val cache = apiCache ?: return get(path, params)
        return cache.getOrLoad(BilibiliApiCache.cacheKey(sessionScope(), operation, params), policy) {
            request(path, params)
        }
    }
    private fun cachedWbiGet(operation: String, path: String, params: Map<String, String>, policy: BilibiliApiCache.Policy): JSONObject {
        val cache = apiCache ?: return wbiGet(path, params)
        return cache.getOrLoad(BilibiliApiCache.cacheKey(sessionScope(), operation, params), policy) {
            wbiGet(path, params)
        }
    }
    private fun wbiGet(path: String, params: Map<String, String>, retry403: Boolean = true): JSONObject {
        val signed = BilibiliWbiSigner.sign(params, currentMixinKey(), System.currentTimeMillis() / 1000)
        val result = request(path, signed)
        if (retry403 && result.optInt("code") == -403) {
            apiCache?.invalidateMixin(sessionScope())
            return wbiGet(path, params, false)
        }
        return result
    }

    private fun currentMixinKey(): String {
        val loader = {
            cachedGet("nav", "/x/web-interface/nav", emptyMap(), BilibiliApiCache.Account)
                .data().optJSONObject("wbi_img")?.let {
                    BilibiliWbiSigner.mixinKey(it.optString("img_url"), it.optString("sub_url"))
                }?.takeIf(String::isNotBlank) ?: throw IOException("无法获取 Bilibili WBI key")
        }
        return apiCache?.mixinKey(sessionScope(), loader) ?: loader()
    }

    private fun sessionScope(): String {
        val session = sessionProvider()
        return BilibiliSessionStore.scope(session.userId, sessionRevisionProvider())
    }

    private fun request(path: String, params: Map<String, String>): JSONObject {
        val url = "https://api.bilibili.com$path".toHttpUrl().newBuilder().apply { params.toSortedMap().forEach(::addQueryParameter) }.build()
        val session = sessionProvider()
        val request = Request.Builder().url(url).header("User-Agent", UserAgent).header("Referer", "https://www.bilibili.com/").apply {
            session.cookie.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
        }.build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Bilibili HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun JSONObject.data(): JSONObject {
        val code = optInt("code")
        if (code != 0) throw IOException(optString("message").ifBlank { "Bilibili API $code" })
        return optJSONObject("data") ?: JSONObject()
    }

    private data class Audio(val id: Int, val url: String, val bandwidth: Int?, val mime: String?)
    private fun MutableList<Audio>.addAudio(value: Any?) {
        when (value) {
            is JSONArray -> addAudio(value)
            is JSONObject -> addAudio(value)
        }
    }
    private fun MutableList<Audio>.addAudio(array: JSONArray?) {
        if (array == null) return
        array.objects().forEach { addAudio(it) }
    }
    private fun MutableList<Audio>.addAudio(item: JSONObject) {
        val url = item.optString("base_url").ifBlank { item.optString("baseUrl") }.ifBlank {
            (item.optJSONArray("backup_url") ?: item.optJSONArray("backupUrl"))?.optString(0).orEmpty()
        }
        if (url.isNotBlank()) add(
            Audio(
                item.optInt("id"),
                url,
                item.optInt("bandwidth").takeIf { it > 0 },
                item.optString("mime_type").ifBlank { item.optString("mimeType") },
            ),
        )
    }

    companion object {
        const val UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
        private val SafeAndroidAudioIds = setOf(30216, 30232, 30280, 30250)
        fun cleanTitle(value: String) = value.replace(Regex("</?em[^>]*>", RegexOption.IGNORE_CASE), "").trim()
        fun parseDuration(value: String): Long? {
            val parts = value.trim().split(':').mapNotNull(String::toLongOrNull)
            if (parts.size !in 2..3) return null
            return parts.fold(0L) { total, part -> total * 60 + part } * 1000
        }
        fun parseIdentity(value: String): Pair<String, Long>? {
            val separator = value.lastIndexOf(':')
            if (separator <= 0) return null
            return value.substring(0, separator) to (value.substring(separator + 1).toLongOrNull() ?: return null)
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
private fun secure(value: String): String? = when {
    value.startsWith("//") -> "https:$value"
    value.startsWith("http://", ignoreCase = true) -> "https://${value.substring(7)}"
    else -> value
}
    .takeIf(String::isNotBlank)
private fun AudioQualityTier.bilibiliAudioPreference() = when (this) {
    AudioQualityTier.Standard -> listOf(30216)
    AudioQualityTier.High -> listOf(30232, 30216)
    AudioQualityTier.Lossless -> listOf(30280, 30232, 30216)
    // Android's platform FLAC decoder can expose only a 32 KiB input buffer.
    // Bilibili 96 kHz 30251 frames exceed it and put Media3 in a fatal state.
    AudioQualityTier.HiResolution, AudioQualityTier.Master -> listOf(30280, 30232, 30216)
    AudioQualityTier.Immersive -> listOf(30250, 30280, 30232, 30216)
}
private fun Int.bilibiliTier() = when (this) {
    30250 -> AudioQualityTier.Immersive
    30251 -> AudioQualityTier.HiResolution
    30280 -> AudioQualityTier.Lossless
    30232 -> AudioQualityTier.High
    else -> AudioQualityTier.Standard
}
