package io.github.tthayer.somastreamer

/** A SomaFM station, from the bundled [SOMA_STATIONS] list. */
data class Channel(
    val id: String,
    val title: String,
    val description: String,
    val genre: String,
    val playlists: List<Playlist>,
)

/** One `.pls` playlist for a channel; each resolves to a few mirrored stream URLs. */
data class Playlist(
    val url: String,
    val format: String,
    val quality: String,
)

/** A track heard on a station's stream; [playedAt] is when it was first seen, in epoch seconds. */
data class Song(
    val title: String,
    val artist: String,
    val playedAt: Long?,
)

/**
 * Builds a [Channel] from one row of the generated station list. [kbps] are the
 * AAC bitrates SomaFM publishes as `https://somafm.com/{id}{kbps}.pls`; 130 is
 * SomaFM's name for its 128k AAC stream.
 */
internal fun station(id: String, title: String, genre: String, description: String, vararg kbps: Int) = Channel(
    id = id,
    title = title,
    description = description,
    genre = genre.split('/').filter { it.isNotBlank() }.joinToString(", "),
    playlists = kbps.toList().mapNotNull { rate ->
        val quality = when (rate) {
            130 -> "highest"
            64 -> "high"
            32 -> "low"
            else -> return@mapNotNull null
        }
        Playlist(url = "https://somafm.com/$id$rate.pls", format = "aac", quality = quality)
    },
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
