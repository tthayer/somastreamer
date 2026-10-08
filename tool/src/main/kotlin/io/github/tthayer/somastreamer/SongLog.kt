package io.github.tthayer.somastreamer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks heard on each station, newest first, as read from the stream's own
 * metadata. SomaFM's play-history feed isn't open to third parties, so this is
 * the only history there is: what the tool has seen since the station was tuned.
 * In memory only, for the life of the process.
 */
internal object SongLog {

    private const val MAX_SONGS = 20

    private val _songs = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val songs: StateFlow<Map<String, List<Song>>> = _songs.asStateFlow()

    /** Records [song] as playing on [stationId] at [nowSeconds], unless it's already the latest entry. */
    fun record(stationId: String, song: Song, nowSeconds: Long) {
        _songs.update { all ->
            val log = all[stationId].orEmpty()
            val latest = log.firstOrNull()
            if (latest != null && latest.title == song.title && latest.artist == song.artist) {
                all
            } else {
                all + (stationId to (listOf(song.copy(playedAt = nowSeconds)) + log).take(MAX_SONGS))
            }
        }
    }

    /** Forgets [stationId]'s history, so it starts again from the moment it's tuned. */
    fun clear(stationId: String) {
        _songs.update { it - stationId }
    }
}
