// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.debug

import com.d4guilar.shelfos.AppContainer
import com.d4guilar.shelfos.feature.library.CoverLabEntry

/** Release builds have no Cover Lab (D1-C-DEV is development-only): no entry, no UI, no provider-cover request. */
@Suppress("UNUSED_PARAMETER")
fun coverLabEntry(container: AppContainer): CoverLabEntry? = null
