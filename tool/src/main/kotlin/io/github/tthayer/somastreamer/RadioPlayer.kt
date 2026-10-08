package io.github.tthayer.somastreamer

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screens render: the tuned station and where playback stands. */
data class RadioState(
    val station: TunedStation? = null,
    val status: RadioStatus = RadioStatus.Idle,
) {
    val isPlaying: Boolean get() = status == RadioStatus.Playing
}

sealed interface RadioStatus {
    data object Idle : RadioStatus
    /** Resolving the playlist or buffering the stream. */
    data object Connecting : RadioStatus
    data object Playing : RadioStatus
    data object Paused : RadioStatus
    data class Failed(val message: String) : RadioStatus
}

/**
 * Process-level radio engine over a detached [LightAudioPlayer], so the stream
 * keeps playing after the tool closes. Screens observe [state] and call the
 * controls directly.
 *
 * A station is one live stream: the tool resolves the channel's `.pls` into its
 * mirror URLs and queues exactly one of them. If that mirror fails with a source
 * (network) error, the next mirror is tried before surfacing the failure.
 *
 * Not thread-safe by design: every entry point runs on the Compose main thread,
 * as does the state mirror.
 */
object RadioPlayer {

    private const val TAG = "RadioPlayer"
    private const val ARTIST = "SomaFM"

    /** What the user asked for, independent of what the player reports. */
    private data class Intent(
        val station: TunedStation? = null,
        val wantsPlay: Boolean = false,
        val failure: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val intent = MutableStateFlow(Intent())
    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private var player: LightAudioPlayer? = null
    private var dataStore: DataStore<Preferences>? = null
    private var mirror: Job? = null
    private var tuneJob: Job? = null

    /** Mirror URLs for the tuned station, and which one is queued. */
    private var streams: List<String> = emptyList()
    private var streamIndex = 0

    /**
     * Connects to the detached playback service. Idempotent: screens can call it
     * from `willShow` without disturbing a stream in progress. If the service is
     * still playing from an earlier session, its station is relabelled from the
     * last one this tool tuned.
     */
    fun attach(audio: LightAudio, store: DataStore<Preferences>) {
        if (player != null) return
        dataStore = store
        val engine = try {
            audio.newPlayer(LightAudioUsage.Music, LightAudioPlayback.Detached)
        } catch (error: Exception) {
            Log.e(TAG, "could not create the detached player", error)
            _state.value = RadioState(status = RadioStatus.Failed("Audio is unavailable."))
            return
        }
        player = engine

        mirror = scope.launch {
            if (!engine.awaitReady()) return@launch
            if (engine.currentMediaItemIndex.value != NO_MEDIA_ITEM && intent.value.station == null) {
                val tuned = withContext(Dispatchers.IO) { loadTuned(store) }
                intent.value = Intent(station = tuned, wantsPlay = engine.isPlaying.value)
            }
            launch { engine.error.collect { it?.let(::onPlaybackError) } }
            combine(intent, engine.isPlaying, engine.error) { want, isPlaying, error ->
                RadioState(station = want.station, status = statusOf(want, isPlaying, error))
            }.collect { _state.value = it }
        }
    }

    /** Tunes to [channel] at [quality] and starts playing. */
    fun play(channel: Channel, quality: StreamQuality) {
        val engine = player ?: return
        val station = TunedStation(id = channel.id, title = channel.title)
        tuneJob?.cancel()
        // Recently played means "since you tuned in", so a fresh tune starts a fresh list.
        if (intent.value.station?.id != channel.id) SongLog.clear(channel.id)
        intent.value = Intent(station = station, wantsPlay = true)
        tuneJob = scope.launch {
            val playlist = channel.playlistFor(quality)
            if (playlist == null) {
                fail("That station has no streams right now.")
                return@launch
            }
            val urls = withContext(Dispatchers.IO) { withApi { it.resolveStreams(playlist) } }
                .getOrElse {
                    fail(it.message ?: "Could not reach SomaFM.")
                    return@launch
                }
            streams = urls
            streamIndex = 0
            queueCurrentStream(engine, station)
            dataStore?.let { store -> withContext(Dispatchers.IO) { saveTuned(store, station) } }
        }
    }

    fun togglePlayPause() {
        val engine = player ?: return
        val want = intent.value
        val station = want.station ?: return
        if (engine.isPlaying.value || (want.wantsPlay && want.failure == null)) {
            tuneJob?.cancel()
            engine.pause()
            intent.update { it.copy(wantsPlay = false) }
            return
        }
        intent.value = want.copy(wantsPlay = true, failure = null)
        if (streams.isNotEmpty()) {
            // Live radio: re-queue rather than resume, so playback rejoins the
            // live edge instead of replaying a stale buffer.
            queueCurrentStream(engine, station)
        } else {
            // A stream inherited from an earlier session: we never resolved its
            // mirrors, so just resume what the service has queued.
            engine.play()
        }
    }

    /** Stops playback and clears the queue, which ends the detached session. */
    fun stop() {
        val engine = player ?: return
        tuneJob?.cancel()
        engine.stop()
        engine.setMediaQueue(emptyList())
        streams = emptyList()
        streamIndex = 0
        intent.value = Intent()
        dataStore?.let { store -> scope.launch(Dispatchers.IO) { saveTuned(store, null) } }
    }

    /**
     * Disconnects this process from the playback service. Playback itself keeps
     * going; [stop] first to end it.
     */
    fun release() {
        tuneJob?.cancel()
        mirror?.cancel()
        mirror = null
        player?.release()
        player = null
        streams = emptyList()
        streamIndex = 0
        intent.value = Intent()
        _state.value = RadioState()
    }

    private fun queueCurrentStream(engine: LightAudioPlayer, station: TunedStation) {
        val url = streams.getOrNull(streamIndex) ?: return
        Log.d(TAG, "tuning ${station.id} via $url")
        engine.setMediaQueue(
            listOf(
                LightAudioItem(
                    source = LightAudioSource.UrlSource(url),
                    metadata = LightMediaMetadata(title = station.title, artist = ARTIST),
                ),
            ),
        )
        engine.play()
    }

    private fun onPlaybackError(error: LightAudioError) {
        val engine = player ?: return
        val want = intent.value
        val station = want.station ?: return
        Log.w(TAG, "playback error on mirror $streamIndex: ${error.kind} ${error.diagnostic}")
        if (want.wantsPlay && error.kind == LightAudioErrorKind.Source && streamIndex + 1 < streams.size) {
            streamIndex++
            queueCurrentStream(engine, station)
            return
        }
        fail(errorMessage(error))
    }

    private fun fail(message: String) {
        intent.update { it.copy(wantsPlay = false, failure = message) }
    }

    private fun statusOf(want: Intent, isPlaying: Boolean, error: LightAudioError?): RadioStatus = when {
        want.failure != null -> RadioStatus.Failed(want.failure)
        isPlaying -> RadioStatus.Playing
        want.wantsPlay && error == null -> RadioStatus.Connecting
        want.station != null -> RadioStatus.Paused
        else -> RadioStatus.Idle
    }

    private fun errorMessage(error: LightAudioError): String = when (error.kind) {
        LightAudioErrorKind.Source -> "Lost the stream. Check your connection and try again."
        LightAudioErrorKind.Unsupported -> "This stream format isn't supported. Try another quality."
        LightAudioErrorKind.Output -> "Couldn't open the speaker."
        LightAudioErrorKind.Unknown -> "Playback failed."
    }
}
