// SPDX-License-Identifier: MPL-2.0
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.d4guilar.shelfos.core.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * Parse-only check that [json] is a well-formed serialized Readium `Locator` (READIUM_LOCATOR_1), using the same
 * `Locator.fromJSON` the reader uses to restore positions. Readium types stay inside `core.reader` (ADR-0003); the
 * domain receives only this boolean through `DefaultAnnotationLocatorValidator`. It never normalizes or rewrites the
 * string and is separate from resume restoration.
 */
fun isReadiumLocatorJson(json: String): Boolean =
    runCatching { Locator.fromJSON(JSONObject(json)) != null }.getOrDefault(false)
