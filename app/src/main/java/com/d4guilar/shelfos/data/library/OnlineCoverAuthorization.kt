// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.library.MediaCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The production cover grant (Phase 4.5C.3A, owner decision D1-C-PROD). It is NOT a [com.d4guilar.shelfos.domain.metadata.ScopedGrant] and
 * Open Library's [com.d4guilar.shelfos.domain.metadata.TermsStatus] stays CONDITIONAL: the metadata grant (D1-M) still cannot authorize artwork.
 * It is an internal owner authorization for exactly one thing: a person manually previewing and choosing an Open Library cover for a Book. It is not
 * an Open Library approval and not a copyright license; D31 (attribution, pacing, takedown/compliance monitoring) continues to apply.
 */
object ProductionCoverGrant {
    const val DECISION = "D1-C-PROD"
    const val PROVIDER_ID = OpenLibrary.ID
    val CATEGORY = MediaCategory.BOOK

    /** Whether the grant covers this provider and category at all (before any consent is looked at). */
    fun covers(providerId: String, category: MediaCategory) = providerId == PROVIDER_ID && category == CATEGORY
}

/** The separate, default-OFF consent to Open Library cover downloads. Its own preference file; never implied by the metadata consent. */
class OnlineCoverConsent(private val store: BooleanStore) {
    private val flag = MutableStateFlow(store.get(KEY, false))
    val enabled: StateFlow<Boolean> = flag.asStateFlow()
    fun set(enabled: Boolean) { store.put(KEY, enabled); flag.value = enabled }

    companion object {
        const val KEY = "open_library_covers_enabled"
        const val PREFERENCES = "online_covers"
    }
}

/** Why a cover request can or cannot start; each missing consent is reported on its own so the person can grant exactly what is needed. */
enum class OnlineCoverAccess { AVAILABLE, METADATA_OFF, COVERS_OFF, BOTH_OFF }

/**
 * The single gate every production cover request passes, re-read at the moment of each stage (discovery, preview, download and the final
 * commit) so a stale screen can never authorize anything. It needs D1-C-PROD's scope AND the person's cover consent AND the existing Open
 * Library metadata switches; none of the three implies another.
 */
class OnlineCoverAuthorization(private val metadata: OnlineMetadataSettings, val consent: OnlineCoverConsent) {
    fun access(): OnlineCoverAccess {
        val metadataOn = metadata.onlineEnabled && metadata.providerEnabled(OpenLibrary.ID)
        val coversOn = consent.enabled.value
        return when { metadataOn && coversOn -> OnlineCoverAccess.AVAILABLE; metadataOn -> OnlineCoverAccess.COVERS_OFF; coversOn -> OnlineCoverAccess.METADATA_OFF; else -> OnlineCoverAccess.BOTH_OFF }
    }

    fun permits(providerId: String, category: MediaCategory): Boolean = ProductionCoverGrant.covers(providerId, category) && access() == OnlineCoverAccess.AVAILABLE

    /** Emits whenever [access] may have changed, so in-flight work can be cancelled the moment consent is withdrawn. */
    val accessFlow: Flow<OnlineCoverAccess> = combine(metadata.onlineFlow, metadata.providersFlow, consent.enabled) { _, _, _ -> access() }.distinctUntilChanged()
}
