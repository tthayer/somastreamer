package com.thelightphone.somafm

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

// SomaFM tracks run a few minutes; this keeps "now playing" current without
// hammering the feed. Polling only runs while the screen is showing.
private const val SONG_POLL_MS = 30_000L
private const val RECENT_SONGS = 8

data class StationUiState(
    val songs: LoadState<List<Song>> = LoadState.Loading,
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
        val result = withApi { it.recentSongs(channel.id) }
        _uiState.value = _uiState.value.copy(
            // Keep showing the last good list if a later poll fails.
            songs = if (result.isFailure && _uiState.value.songs is LoadState.Ready) {
                _uiState.value.songs
            } else {
                result.toLoadState("Could not load what's playing.")
            },
            nowSeconds = System.currentTimeMillis() / 1000,
        )
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
                channel.summaryLine().takeIf { it.isNotBlank() }?.let { MessageLine(it) }

                NowPlaying(uiState.songs)

                Controls(radio = radio, tunedHere = tunedHere)

                val songs = (uiState.songs as? LoadState.Ready)?.value.orEmpty()
                if (songs.size > 1) {
                    SectionHeader("Recently played")
                    songs.drop(1).take(RECENT_SONGS).forEach { song ->
                        SomaRow(
                            title = song.displayLine(),
                            detail = song.album.ifBlank { null },
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
    private fun NowPlaying(songs: LoadState<List<Song>>) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val current = (songs as? LoadState.Ready)?.value?.firstOrNull()
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
                    if (current.album.isNotBlank()) {
                        LightText(
                            text = current.album,
                            variant = LightTextVariant.Detail,
                            align = TextAlign.Center,
                            lighten = true,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                songs is LoadState.Failed -> MessageText(songs.message)
                songs is LoadState.Loading -> MessageText("Loading…")
                else -> MessageText(channel.lastPlaying)
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
