package io.github.tthayer.somastreamer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// SomaFM tracks run a few minutes. Each check opens the stream and reads ~45 KB
// up to its first metadata block, so 30s keeps "now playing" current at a
// small cost. Polling only runs while the screen is showing.
private const val SONG_POLL_MS = 30_000L
private const val RECENT_SONGS = 8

data class StationUiState(
    /** The track on air: Ready(null) between tracks. */
    val current: LoadState<Song?> = LoadState.Loading,
    val favorite: Boolean = false,
    val nowSeconds: Long = System.currentTimeMillis() / 1000,
)

class StationScreenViewModel(
    private val dataStore: DataStore<Preferences>,
    private val channel: Channel,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(StationUiState())
    val uiState: StateFlow<StationUiState> = _uiState.asStateFlow()

    private var poll: Job? = null

    /** Mirror URLs of the station's lowest-bitrate stream, used only to read titles. */
    private var titleStreams: List<String>? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(favorite = channel.id in loadFavorites(dataStore))
        }
        poll?.cancel()
        poll = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                loadSongs()
                delay(SONG_POLL_MS)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        poll?.cancel()
        poll = null
        super.onScreenHide(screen)
    }

    fun toggleFavorite() {
        val favorite = !_uiState.value.favorite
        _uiState.value = _uiState.value.copy(favorite = favorite)
        viewModelScope.launch(Dispatchers.IO) { setFavorite(dataStore, channel.id, favorite) }
    }

    private suspend fun loadSongs() {
        val result = withApi { api -> readTitle(api) }
        val nowSeconds = System.currentTimeMillis() / 1000
        result.getOrNull()?.let { SongLog.record(channel.id, it, nowSeconds) }
        _uiState.value = _uiState.value.copy(
            // Keep showing the last good title if a later poll fails.
            current = if (result.isFailure && _uiState.value.current is LoadState.Ready) {
                _uiState.value.current
            } else {
                result.toLoadState("Could not read what's playing.")
            },
            nowSeconds = nowSeconds,
        )
    }

    /** Reads the title from the first mirror that answers. */
    private suspend fun readTitle(api: SomaApi): Result<Song?> {
        val streams = titleStreams ?: run {
            val playlist = channel.playlistFor(StreamQuality.Low)
                ?: return Result.failure(SomaApiException("That station has no streams right now."))
            api.resolveStreams(playlist).getOrElse { return Result.failure(it) }.also { titleStreams = it }
        }
        var failure: Throwable = SomaApiException("That station has no streams right now.")
        for (url in streams) {
            api.streamTitle(url).onSuccess { return Result.success(it) }.onFailure { failure = it }
        }
        return Result.failure(failure)
    }
}

class StationScreen(
    sealedActivity: SealedLightActivity,
    private val channel: Channel,
    private val quality: StreamQuality,
) : LightScreen<Unit, StationScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<StationScreenViewModel>
        get() = StationScreenViewModel::class.java

    override fun createViewModel() = StationScreenViewModel(lightContext.dataStore, channel)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val radio by RadioPlayer.state.collectAsState()
        val log by SongLog.songs.collectAsState()
        val tunedHere = radio.station?.id == channel.id

        SomaScaffold(
            title = channel.title,
            onBack = { goBack(Unit) },
            rightButton = LightBarButton.LightIcon(
                icon = if (uiState.favorite) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                onClick = viewModel::toggleFavorite,
            ),
        ) {
            LightScrollView(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                if (channel.description.isNotBlank()) MessageLine(channel.description)
                channel.genre.takeIf { it.isNotBlank() }?.let { MessageLine(it) }

                NowPlaying(uiState.current)

                Controls(radio = radio, tunedHere = tunedHere)

                // The log's newest entry is the track on air unless the station
                // has since gone to a break; either way it's shown above.
                val onAir = (uiState.current as? LoadState.Ready)?.value
                val recent = log[channel.id].orEmpty().let { songs ->
                    val latest = songs.firstOrNull()
                    if (onAir != null && latest?.title == onAir.title && latest.artist == onAir.artist) songs.drop(1) else songs
                }
                if (recent.isNotEmpty()) {
                    SectionHeader("Recently played")
                    recent.take(RECENT_SONGS).forEach { song ->
                        SomaRow(
                            title = song.displayLine(),
                            marker = formatAgo(song.playedAt, uiState.nowSeconds),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MessageLine(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun NowPlaying(onAir: LoadState<Song?>) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val current = (onAir as? LoadState.Ready)?.value
            when {
                current != null -> {
                    LightText(
                        text = current.title.ifBlank { current.artist },
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current.title.isNotBlank() && current.artist.isNotBlank()) {
                        LightText(
                            text = current.artist,
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            lighten = true,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
                onAir is LoadState.Failed -> MessageText(onAir.message)
                onAir is LoadState.Loading -> MessageText("Loading…")
                else -> MessageText("Between tracks")
            }
        }
    }

    @Composable
    private fun Controls(radio: RadioState, tunedHere: Boolean) {
        val playing = tunedHere && (radio.isPlaying || radio.status == RadioStatus.Connecting)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightIcon(
                icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                size = 4f,
                modifier = Modifier.lightClickable {
                    when {
                        !tunedHere || radio.status is RadioStatus.Failed -> RadioPlayer.play(channel, quality)
                        else -> RadioPlayer.togglePlayPause()
                    }
                },
            )
            if (tunedHere) {
                LightIcon(
                    icon = LightIcons.STOP,
                    size = 3f,
                    modifier = Modifier.lightClickable { RadioPlayer.stop() },
                )
            }
        }
        if (tunedHere) {
            radio.status.label().takeIf { it.isNotBlank() }?.let { status ->
                LightText(
                    text = status,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = radio.status !is RadioStatus.Failed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
