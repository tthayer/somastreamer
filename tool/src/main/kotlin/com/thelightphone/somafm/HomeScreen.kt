package com.thelightphone.somafm

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val channels: LoadState<List<Channel>> = LoadState.Loading,
    val favorites: Set<String> = emptySet(),
    val quality: StreamQuality = StreamQuality.Default,
)

class HomeScreenViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Favorites and quality can change on the station screen; the channel
        // list only loads once (the refresh button reloads it).
        loadSettings()
        if (_uiState.value.channels !is LoadState.Ready) refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(channels = LoadState.Loading)
        viewModelScope.launch(Dispatchers.IO) {
            val channels = withApi { it.listChannels() }.toLoadState("Could not load SomaFM stations.")
            _uiState.value = _uiState.value.copy(channels = channels)
        }
    }

    fun cycleQuality() {
        val next = _uiState.value.quality.next()
        _uiState.value = _uiState.value.copy(quality = next)
        viewModelScope.launch(Dispatchers.IO) { saveQuality(dataStore, next) }
    }

    private fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                favorites = loadFavorites(dataStore),
                quality = loadQuality(dataStore),
            )
        }
    }
}

@InitialScreen
class HomeScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel() = HomeScreenViewModel(lightContext.dataStore)

    override fun willShow() {
        super.willShow()
        // Connects to (or reconnects with) the detached playback service. Idempotent,
        // so re-showing this screen doesn't disturb a stream already playing.
        RadioPlayer.attach(DefaultLightAudio(sealedActivity), lightContext.dataStore)
    }

    override fun onScreenDestroy() {
        // Leaving the initial screen closes the tool. Detached playback keeps going;
        // this only lets go of our handle on it.
        RadioPlayer.release()
        super.onScreenDestroy()
    }

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val radio by RadioPlayer.state.collectAsState()

        SomaScaffold(title = "SomaFM", onBack = null, rightButton = refreshButton { viewModel.refresh() }) {
            val channels = (uiState.channels as? LoadState.Ready)?.value.orEmpty()
            val station = radio.station
            LightScrollView(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                if (station != null) {
                    val tunedChannel = channels.firstOrNull { it.id == station.id }
                    SomaRow(
                        title = "Now Playing",
                        detail = listOf(station.title, radio.status.label()).filter { it.isNotBlank() }.joinToString(" · "),
                        onClick = tunedChannel?.let { channel -> { openStation(channel, uiState.quality) } },
                    )
                }

                when (val state = uiState.channels) {
                    is LoadState.Loading -> SomaRow(title = "Loading stations…")
                    is LoadState.Failed -> SomaRow(title = state.message, detail = "Tap to retry", onClick = viewModel::refresh)
                    is LoadState.Ready -> {
                        val (favorites, others) = sortChannels(state.value, uiState.favorites)
                        if (favorites.isNotEmpty()) {
                            SectionHeader("Favorites")
                            favorites.forEach { ChannelRow(it, uiState.quality) }
                        }
                        SectionHeader(if (favorites.isEmpty()) "Stations" else "All stations")
                        others.forEach { ChannelRow(it, uiState.quality) }
                    }
                }

                SectionHeader("Settings")
                SomaRow(
                    title = "Stream quality",
                    detail = "${uiState.quality.label} · tap to change",
                    onClick = viewModel::cycleQuality,
                )
            }
        }
    }

    @Composable
    private fun ChannelRow(channel: Channel, quality: StreamQuality) {
        SomaRow(
            title = channel.title,
            detail = channel.summaryLine().ifBlank { null },
            onClick = { openStation(channel, quality) },
        )
    }

    private fun openStation(channel: Channel, quality: StreamQuality) {
        navigateTo(screenFactory = { StationScreen(it, channel, quality) })
    }
}
