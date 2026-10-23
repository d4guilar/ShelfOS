// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

/**
 * Where the artwork of a USER-slot cover came from (Phase 4.5C.3A). The USER slot says only that the person chose this cover for display;
 * the origin says who supplied the pixels. A cover with no recorded origin (every row written before schema v9) is of unknown origin and is
 * never assumed to be a person's own image: Composite Cover eligibility (OMNIBUS_COVERS) must treat it as such.
 */
enum class CoverOriginKind {
    /** An image the person picked from device storage. */
    USER_SUPPLIED,

    /** A provider's artwork the person explicitly previewed and confirmed; [CoverOrigin.providerId] and the references say which. */
    PROVIDER_SELECTED,
}

data class CoverOrigin(
    val kind: CoverOriginKind, val providerId: String? = null, val editionRef: String? = null,
    val refKind: String? = null, val refValue: String? = null, val selectedAtMs: Long? = null,
) {
    init {
        if (kind == CoverOriginKind.PROVIDER_SELECTED) require(!providerId.isNullOrBlank() && !editionRef.isNullOrBlank() && !refKind.isNullOrBlank() && !refValue.isNullOrBlank()) {
            "a provider-selected cover must name its provider, edition and artwork"
        }
    }

    companion object {
        val DEVICE = CoverOrigin(CoverOriginKind.USER_SUPPLIED)
    }
}
