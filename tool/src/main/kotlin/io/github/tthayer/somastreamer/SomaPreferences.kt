package io.github.tthayer.somastreamer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

internal object SomaPreferences {
    val QUALITY = stringPreferencesKey("stream_quality")
    val FAVORITES = stringSetPreferencesKey("favorite_channel_ids")

    // The station the detached player was last tuned to, so a reopened tool can
    // label a stream that kept playing while it was closed.
    val TUNED_ID = stringPreferencesKey("tuned_channel_id")
    val TUNED_TITLE = stringPreferencesKey("tuned_channel_title")
}

/** The station last handed to the player: its id and title. */
data class TunedStation(val id: String, val title: String)

internal suspend fun loadQuality(dataStore: DataStore<Preferences>): StreamQuality =
    StreamQuality.fromName(dataStore.data.first()[SomaPreferences.QUALITY])

internal suspend fun saveQuality(dataStore: DataStore<Preferences>, quality: StreamQuality) {
    dataStore.edit { it[SomaPreferences.QUALITY] = quality.name }
}

internal suspend fun loadFavorites(dataStore: DataStore<Preferences>): Set<String> =
    dataStore.data.first()[SomaPreferences.FAVORITES].orEmpty()

internal suspend fun setFavorite(dataStore: DataStore<Preferences>, channelId: String, favorite: Boolean) {
    dataStore.edit { prefs ->
        val current = prefs[SomaPreferences.FAVORITES].orEmpty()
        prefs[SomaPreferences.FAVORITES] = if (favorite) current + channelId else current - channelId
    }
}

internal suspend fun loadTuned(dataStore: DataStore<Preferences>): TunedStation? {
    val prefs = dataStore.data.first()
    val id = prefs[SomaPreferences.TUNED_ID]?.takeIf { it.isNotBlank() } ?: return null
    return TunedStation(id = id, title = prefs[SomaPreferences.TUNED_TITLE] ?: id)
}

internal suspend fun saveTuned(dataStore: DataStore<Preferences>, station: TunedStation?) {
    dataStore.edit { prefs ->
        if (station == null) {
            prefs.remove(SomaPreferences.TUNED_ID)
            prefs.remove(SomaPreferences.TUNED_TITLE)
        } else {
            prefs[SomaPreferences.TUNED_ID] = station.id
            prefs[SomaPreferences.TUNED_TITLE] = station.title
        }
    }
}
