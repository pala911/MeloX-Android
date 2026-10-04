package com.lladlam.melox.core.provider.kugou

import com.lladlam.melox.core.music.model.MusicAlbumRef
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicPlaylistDetail
import com.lladlam.melox.core.music.model.MusicPlaylistSummary
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fields that every real Kugou list-file song carries and that the playlist header
 * record never carries. Used to tell the header apart from a track when a self-created
 * list's header lacks the collection identity keys.
 */
private val SONG_MARKERS = listOf(
    "timelen",
    "time_length",
    "Duration",
    "duration",
    "singerinfo",
    "albuminfo",
    "bitrate",
    "privilege",
    "mixsongid",
    "audio_id",
)

class KugouPlaylistClient(
    private val sessionProvider: () -> KugouSession,
    httpClient: OkHttpClient = com.lladlam.melox.core.network.MeloXHttpClient.shared,
) {
    private val requests = KugouRequestClient(sessionProvider, httpClient)

    suspend fun detail(
        playlist: MusicPlaylistSummary,
        page: Int = 1,
        pageSize: Int = 100,
    ): MusicPlaylistDetail {
        require(playlist.id.source == MusicSource.Kugou)
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 200)
        val session = sessionProvider()

        val detail = runCatching {
            requests.post(
                path = "/v3/get_list_info",
                body = JSONObject()
                    .put(
                        "data",
                        JSONArray().put(
                            JSONObject().put("global_collection_id", playlist.id.value),
                        ),
                    )
                    .put("userid", session.userId)
                    .put("token", session.token),
                headers = mapOf("x-router" to "pubsongs.kugou.com"),
            )
        }.getOrNull()

        // The endpoint silently caps a request at 150 items while still reporting that
        // the first page is complete. Use a conservative wire page size and exhaust pages
        // from the first request so large playlists are not truncated at that boundary.
        val wirePageSize = safeSize.coerceAtMost(50)
        val allTracks = linkedMapOf<String, MusicTrack>()
        var reportedTotal = -1L
        val pagesToLoad = if (safePage == 1) 100 else 1
        for (offset in 0 until pagesToLoad) {
            val pageNumber = safePage + offset
            val songsResponse = requests.get(
                path = "/pubsongs/v2/get_other_list_file_nofilt",
                params = mapOf(
                    "area_code" to "1",
                    "begin_idx" to ((pageNumber - 1) * wirePageSize).toString(),
                    "plat" to "1",
                    "type" to "1",
                    "mode" to "1",
                    "personal_switch" to "1",
                    "extend_fields" to "abtags,hot_cmt,popularization",
                    "pagesize" to wirePageSize.toString(),
                    "global_collection_id" to playlist.id.value,
                ),
            )
            val pageTracks = flattenObjects(songsResponse)
                .mapNotNull { item -> parseTrack(item, playlist.title) }
                .distinctBy { it.id.value }
            reportedTotal = maxOf(reportedTotal, findFirstLong(songsResponse, "total", "total_count", "count", "filesize"))
            val before = allTracks.size
            pageTracks.forEach { allTracks.putIfAbsent(it.id.value, it) }
            if (pageTracks.isEmpty() || allTracks.size == before || safePage != 1) break
        }
        val tracks = allTracks.values.toList()
        val summary = detail
            ?.let(::flattenObjects)
            ?.mapNotNull(::parseSummary)
            ?.firstOrNull()
            ?: playlist
        val total = maxOf(reportedTotal, tracks.size.toLong()).takeIf { it >= 0 }
        return MusicPlaylistDetail(
            summary = summary,
            tracks = tracks,
            total = total,
        )
    }

    private fun parseSummary(item: JSONObject): MusicPlaylistSummary? {
        if (firstString(item, "hash", "Hash", "FileHash").isNotBlank()) return null
        val id = firstString(item, "global_collection_id", "global_specialid", "specialid", "special_id")
            .ifBlank {
                firstLong(item, "global_collection_id", "global_specialid", "specialid", "special_id")
                    .takeIf { it > 0 }?.toString().orEmpty()
            }
        if (id.isBlank()) return null
        val title = firstString(item, "specialname", "special_name", "listname", "name", "title")
        if (title.isBlank()) return null
        return MusicPlaylistSummary(
            id = MusicResourceId(MusicSource.Kugou, id),
            title = title,
            artworkUrl = kugouArtworkUrl(item),
            creatorName = firstString(item, "nickname", "username", "author_name").takeIf(String::isNotBlank),
            description = firstString(item, "intro", "description", "desc").takeIf(String::isNotBlank),
            trackCount = firstLong(item, "song_count", "songcount", "song_num", "count").takeIf { it >= 0 }?.toInt(),
            playCount = firstLong(item, "play_count", "playcount", "heat").takeIf { it >= 0 },
        )
    }

    private fun parseTrack(item: JSONObject, playlistTitle: String): MusicTrack? {
        // flattenObjects() walks every object in the response, including the playlist
        // header. That header carries the playlist's own title (and, on a self-created
        // list, a cover-file hash), so reject it by its collection identity rather than
        // by refusing the generic "name" title: the list-file song objects title
        // themselves with plain "name", so dropping that fallback emptied the list.
        if (item.has("global_collection_id") ||
            item.has("parent_global_collection_id") ||
            item.has("list_create_listid") ||
            item.has("list_create_userid") ||
            item.has("specialid")
        ) {
            return null
        }
        val hash = kugouFirstString(item, "FileHash", "Hash", "hash", "filehash").uppercase()
        if (hash.isBlank()) return null
        // A self-created list ships a header record that carries none of the collection
        // identity keys above: it is the list's own title/cover entry, holding a cover-file
        // hash and no playback metadata. Every real song object carries duration, artist,
        // bitrate or billing fields, so require at least one before treating it as a track.
        if (SONG_MARKERS.none(item::has)) return null
        val rawTitle = kugouFirstString(
            item,
            "SongName", "songname", "AudioName", "audio_name", "FileName", "filename", "name",
        )
        if (rawTitle.isBlank()) return null
        val (title, singer) = recoverKugouTrackText(
            rawTitle,
            kugouSingerName(item, "SingerName", "singername", "author_name", "AuthorName"),
        )
        if (title.isBlank()) return null
        // Narrow last resort: a header is titled exactly like its playlist and has no
        // artist. A genuine song that merely shares the playlist's name still has one,
        // so this only drops the header, never a real track.
        if (playlistTitle.isNotBlank() && title == playlistTitle &&
            (singer.isBlank() || singer == "未知歌手")
        ) {
            return null
        }
        val albumName = kugouFirstString(item, "AlbumName", "album_name", "albumname")
            .ifBlank { item.optJSONObject("albuminfo")?.optString("name").orEmpty() }
        val albumId = kugouFirstString(item, "AlbumID", "album_id", "albumid").takeIf(String::isNotBlank)
        val albumAudioId = kugouFirstLong(item, "album_audio_id", "MixSongID", "mixsongid", "AlbumAudioID", "Audioid", "audio_id")
            .takeIf { it > 0 }
        val artwork = kugouArtworkUrl(item)
        val artists = singer
            .split(Regex("\\s*(?:、|/|&|,|;|；)\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf("未知歌手") }
            .map { MusicArtistRef(name = it) }
        return MusicTrack(
            id = MusicResourceId(MusicSource.Kugou, hash),
            title = title,
            artists = artists,
            album = albumName.takeIf(String::isNotBlank)?.let {
                MusicAlbumRef(
                    id = albumId?.let { value -> MusicResourceId(MusicSource.Kugou, value) },
                    name = it,
                    artworkUrl = artwork,
                )
            },
            artworkUrl = artwork,
            durationMs = kugouFirstLong(item, "timelen").takeIf { it > 0 }
                ?: kugouFirstLong(item, "Duration", "duration", "time_length")
                    .takeIf { it > 0 }
                    ?.let { if (it > 100_000L) it else it * 1_000L },
            providerMetadata = ProviderTrackMetadata.Kugou(
                hash = hash,
                albumAudioId = albumAudioId,
                albumId = albumId,
            ),
        )
    }

    private fun flattenObjects(root: Any?): List<JSONObject> = buildList {
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    add(value)
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(root)
    }

    private fun findFirstLong(root: Any?, vararg keys: String): Long {
        flattenObjects(root).forEach { value ->
            val found = firstLong(value, *keys)
            if (found >= 0) return found
        }
        return -1L
    }

    private fun firstString(value: JSONObject, vararg keys: String): String =
        keys.asSequence().map(value::optString).firstOrNull(String::isNotBlank).orEmpty()

    private fun firstLong(value: JSONObject, vararg keys: String): Long =
        keys.asSequence().mapNotNull { key ->
            when (val raw = value.opt(key)) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
        }.firstOrNull() ?: -1L

}
