package com.android.insta.core.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

class DataStoreSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val json: Json,
) : SessionStore {

    override val session: Flow<Session?> = dataStore.data.map { it.toSession() }.distinctUntilChanged()

    override suspend fun current(): Session? = session.first()

    override suspend fun save(session: Session) {
        dataStore.edit {
            it[ACCESS] = cipher.encrypt(session.accessToken)
            it[REFRESH] = cipher.encrypt(session.refreshToken)
            it[USER] = json.encodeToString(SessionUser.serializer(), session.user)
        }
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        dataStore.edit {
            if (it[USER] == null) return@edit // Logged out while the refresh was in flight.
            it[ACCESS] = cipher.encrypt(accessToken)
            it[REFRESH] = cipher.encrypt(refreshToken)
        }
    }

    override suspend fun updateUser(user: SessionUser) {
        dataStore.edit {
            if (it[USER] == null) return@edit
            it[USER] = json.encodeToString(SessionUser.serializer(), user)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toSession(): Session? {
        val access = this[ACCESS]?.let(cipher::decrypt) ?: return null
        val refresh = this[REFRESH]?.let(cipher::decrypt) ?: return null
        val user = this[USER]?.let { runCatching { json.decodeFromString(SessionUser.serializer(), it) }.getOrNull() }
            ?: return null
        return Session(access, refresh, user)
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val USER = stringPreferencesKey("user")
    }
}
