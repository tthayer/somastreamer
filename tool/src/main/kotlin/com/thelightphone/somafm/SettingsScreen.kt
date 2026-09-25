package com.thelightphone.somafm

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsScreenViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Boolean>() {

    private val _quality = MutableStateFlow(StreamQuality.Default)
    val quality: StateFlow<StreamQuality> = _quality.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Boolean>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) { _quality.value = loadQuality(dataStore) }
    }

    fun cycleQuality() {
        val next = _quality.value.next()
        _quality.value = next
        viewModelScope.launch(Dispatchers.IO) { saveQuality(dataStore, next) }
    }
}

/** Tool settings. Returns `true` when the user asked to reload the station list. */
class SettingsScreen(sealedActivity: SealedLightActivity) : LightScreen<Boolean, SettingsScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsScreenViewModel>
        get() = SettingsScreenViewModel::class.java

    override fun createViewModel() = SettingsScreenViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val quality by viewModel.quality.collectAsState()

        SomaScaffold(title = "Settings", onBack = { goBack(false) }) {
            LightScrollView(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                SomaRow(
                    title = "Stream quality",
                    detail = "${quality.label} · tap to change",
                    onClick = viewModel::cycleQuality,
                )
                SectionRule()
                SomaRow(
                    title = "Reload stations",
                    detail = "Fetch the latest SomaFM station list",
                    onClick = { goBack(true) },
                )
            }
        }
    }
}
