package io.github.tthayer.somastreamer

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
    val channels: List<Channel> = SOMA_STATIONS,
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
        // Favorites and quality can change on the station and settings screens.
        loadSettings()
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

        SomaScaffold(title = "SomaStreamer", onBack = null, rightButton = settingsButton(::openSettings)) {
            val channels = uiState.channels
            val station = radio.station
            LightScrollView(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                if (station != null) {
                    val tunedChannel = channels.firstOrNull { it.id == station.id }
                    SomaRow(
                        title = "Now Playing",
                        detail = listOf(station.title, radio.status.label()).filter { it.isNotBlank() }.joinToString(" · "),
                        onClick = tunedChannel?.let { channel -> { openStation(channel, uiState.quality) } },
                    )
                    SectionRule()
                }

                val (favorites, others) = sortChannels(channels, uiState.favorites)
                if (favorites.isNotEmpty()) {
                    SectionHeader("Favorites")
                    favorites.forEach { ChannelRow(it, uiState.quality) }
                    SectionRule()
                }
                SectionHeader(if (favorites.isEmpty()) "Stations" else "All stations")
                others.forEach { ChannelRow(it, uiState.quality) }
            }
        }
    }

    @Composable
    private fun ChannelRow(channel: Channel, quality: StreamQuality) {
        SomaRow(
            title = channel.title,
            detail = channel.genre.ifBlank { null },
            onClick = { openStation(channel, quality) },
        )
    }

    private fun openSettings() {
        navigateTo(screenFactory = ::SettingsScreen)
    }

    private fun openStation(channel: Channel, quality: StreamQuality) {
        navigateTo(screenFactory = { StationScreen(it, channel, quality) })
    }
}
