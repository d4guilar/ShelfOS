// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.preferences

import android.content.Context
import androidx.core.content.edit
import com.d4guilar.shelfos.data.metadata.ProviderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two booleans this setting needs, behind an interface so JVM tests need no Android. */
interface BooleanStore {
    fun get(key: String, default: Boolean): Boolean
    fun put(key: String, value: Boolean)
}

class SharedPreferencesBooleanStore(context: Context, name: String = "online_metadata") : BooleanStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun put(key: String, value: Boolean) { prefs.edit { putBoolean(key, value) } }
}

/**
 * Online metadata consent (Phase 4.5C.2A, plan D1-M). Every switch is OFF on a fresh install; this is the [ProviderSettings] the
 * registry reads at the moment of each call, so a stale screen can never authorize a request. Persisted in a private preference
 * file (no Room change); not backed up (`allowBackup=false`).
 */
class OnlineMetadataSettings(private val store: BooleanStore) : ProviderSettings {
    private val online = MutableStateFlow(store.get(ONLINE, false))
    private val providers = MutableStateFlow(readProviders())

    /** Observable copies for the UI and for cancelling in-flight work. */
    val onlineFlow: StateFlow<Boolean> = online.asStateFlow()
    val providersFlow: StateFlow<Set<String>> = providers.asStateFlow()

    override val onlineEnabled get() = online.value
    override fun providerEnabled(id: String) = id in providers.value
    override fun hasToken(id: String) = false
    override fun disabledByPolicy(id: String) = false

    fun setOnlineEnabled(enabled: Boolean) { store.put(ONLINE, enabled); online.value = enabled }

    fun setProviderEnabled(id: String, enabled: Boolean) {
        store.put(providerKey(id), enabled)
        providers.value = if (enabled) providers.value + id else providers.value - id
    }

    /** What "Enable & Search" does: the user consented to this provider, so both switches go on together. */
    fun enableProvider(id: String) { setOnlineEnabled(true); setProviderEnabled(id, true) }

    private fun readProviders(): Set<String> = KNOWN.filter { store.get(providerKey(it), false) }.toSet()
    private fun providerKey(id: String) = "provider_$id"

    companion object {
        const val ONLINE = "online_enabled"
        /** Providers that can ever be switched on; an unknown id read from storage is ignored. */
        val KNOWN = listOf("openlibrary")
    }
}
