package com.thelightphone.somafm

/** A SomaFM station, as listed by `channels.json`. */
data class Channel(
    val id: String,
    val title: String,
    val description: String,
    val dj: String,
    val genre: String,
    val listeners: Int,
    val lastPlaying: String,
    val playlists: List<Playlist>,
)

/** One `.pls` playlist for a channel; each resolves to a few mirrored stream URLs. */
data class Playlist(
    val url: String,
    val format: String,
    val quality: String,
)

/** A track from `songs/{id}.json`; [playedAt] is epoch seconds. */
data class Song(
    val title: String,
    val artist: String,
    val album: String,
    val playedAt: Long?,
)

/**
 * Stream bitrate choice. Values map onto the `quality` / `format` pairs SomaFM
 * publishes per channel; [label] is what the settings row shows.
 */
enum class StreamQuality(val label: String, val quality: String, val preferredFormats: List<String>) {
    Low("Low (32k)", "low", listOf("aacp", "aac", "mp3")),
    Standard("Standard (64k)", "high", listOf("aacp", "aac", "mp3")),
    High("High (128k)", "highest", listOf("aac", "mp3", "aacp")),
    ;

    fun next(): StreamQuality = entries[(ordinal + 1) % entries.size]

    companion object {
        val Default = Standard

        fun fromName(name: String?): StreamQuality = entries.firstOrNull { it.name == name } ?: Default
    }
}

sealed class LoadState<out T> {
    data object Loading : LoadState<Nothing>()
    data class Ready<T>(val value: T) : LoadState<T>()
    data class Failed(val message: String) : LoadState<Nothing>()
}

internal fun <T> Result<T>.toLoadState(fallback: String): LoadState<T> = fold(
    onSuccess = { LoadState.Ready(it) },
    onFailure = { LoadState.Failed(it.message ?: fallback) },
)
