package app.playsift.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Offers to import a saved history file once, on the first run with an empty store, until the user acts on it. */
interface HistoryImportOffer {
    /** [storeWasEmpty] only counts the first time; after that the stored answer decides. Returns whether to show the offer. */
    suspend fun shouldOffer(storeWasEmpty: Boolean): Boolean

    suspend fun markDone()
}

class DataStoreHistoryImportOffer(private val dataStore: DataStore<Preferences>) : HistoryImportOffer {
    override suspend fun shouldOffer(storeWasEmpty: Boolean): Boolean {
        val stored = dataStore.data.first()[OFFER_KEY]
        if (stored == null && storeWasEmpty) {
            dataStore.edit { preferences -> preferences[OFFER_KEY] = PENDING }
            return true
        }
        return stored == PENDING
    }

    override suspend fun markDone() {
        dataStore.edit { preferences -> preferences[OFFER_KEY] = DONE }
    }

    private companion object {
        val OFFER_KEY = stringPreferencesKey("history_import_offer")
        const val PENDING = "pending"
        const val DONE = "done"
    }
}
