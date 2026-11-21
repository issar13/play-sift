package app.playsift.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Period
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class CutoffOption(val period: Period, val label: String) {
    SixMonths(Period.ofMonths(6), "6 months"),
    OneYear(Period.ofYears(1), "1 year"),
    TwoYears(Period.ofYears(2), "2 years"),
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class CutoffSettings(private val dataStore: DataStore<Preferences>) {
    val cutoff: Flow<CutoffOption> = dataStore.data.map { preferences ->
        CutoffOption.entries.firstOrNull { it.name == preferences[CUTOFF_KEY] } ?: CutoffOption.OneYear
    }

    suspend fun setCutoff(option: CutoffOption) {
        dataStore.edit { preferences -> preferences[CUTOFF_KEY] = option.name }
    }

    private companion object {
        val CUTOFF_KEY = stringPreferencesKey("cutoff")
    }
}
