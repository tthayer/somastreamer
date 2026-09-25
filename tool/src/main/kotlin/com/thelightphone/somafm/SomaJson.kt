package com.thelightphone.somafm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val somaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // `listeners` and `date` arrive as JSON strings ("152"); lenient mode also
    // accepts them as bare numbers should the feed ever change.
    isLenient = true
}

@Serializable
internal data class ChannelsResponseDto(
    val channels: List<ChannelDto> = emptyList(),
)

@Serializable
internal data class ChannelDto(
    val id: String,
    val title: String = "",
    val description: String = "",
    val dj: String = "",
    val genre: String = "",
    val listeners: String = "",
    val lastPlaying: String = "",
    val playlists: List<PlaylistDto> = emptyList(),
)

@Serializable
internal data class PlaylistDto(
    val url: String,
    val format: String = "",
    val quality: String = "",
)

@Serializable
internal data class SongsResponseDto(
    val songs: List<SongDto> = emptyList(),
)

@Serializable
internal data class SongDto(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("date") val date: String = "",
)

internal fun parseChannels(text: String): List<Channel> =
    somaJson.decodeFromString<ChannelsResponseDto>(text).channels.map { dto ->
        Channel(
            id = dto.id,
            title = dto.title.ifBlank { dto.id },
            description = dto.description,
            dj = dto.dj,
            // Genres come pipe-separated ("ambient|electronic").
            genre = dto.genre.split('|').filter { it.isNotBlank() }.joinToString(", "),
            listeners = dto.listeners.trim().toIntOrNull() ?: 0,
            lastPlaying = dto.lastPlaying,
            playlists = dto.playlists.map { Playlist(url = it.url, format = it.format, quality = it.quality) },
        )
    }

internal fun parseSongs(text: String): List<Song> =
    somaJson.decodeFromString<SongsResponseDto>(text).songs
        .filter { it.title.isNotBlank() || it.artist.isNotBlank() }
        .map { Song(title = it.title, artist = it.artist, album = it.album, playedAt = it.date.trim().toLongOrNull()) }

private val PLS_FILE_LINE = Regex("""^File(\d+)\s*=\s*(\S+)\s*$""", RegexOption.IGNORE_CASE)

/**
 * Extracts the stream URLs from a `.pls` playlist, in `FileN` order. SomaFM's
 * playlists list the same stream on several mirrors (`ice1`, `ice2`, ...).
 */
internal fun parsePls(text: String): List<String> =
    text.lineSequence()
        .mapNotNull { PLS_FILE_LINE.matchEntire(it.trim()) }
        .map { it.groupValues[1].toInt() to it.groupValues[2] }
        .sortedBy { it.first }
        .map { it.second }
        .filter { it.startsWith("https://") || it.startsWith("http://") }
        .distinct()
        .toList()

/**
 * The best playlist for [quality]: the matching tier in the quality's preferred
 * format order, else any playlist of that tier, else the channel's first one.
 */
internal fun Channel.playlistFor(quality: StreamQuality): Playlist? {
    val tier = playlists.filter { it.quality.equals(quality.quality, ignoreCase = true) }
    return quality.preferredFormats.firstNotNullOfOrNull { format ->
        tier.firstOrNull { it.format.equals(format, ignoreCase = true) }
    } ?: tier.firstOrNull() ?: playlists.firstOrNull()
}
