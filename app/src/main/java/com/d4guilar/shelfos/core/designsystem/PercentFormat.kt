// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import java.text.NumberFormat

/** Locale-aware rendering of a 0..100 reading-progress value (symbol placement/spacing varies by locale).
 * Reads the Compose-observable LocalLocale, the same locale source formatSize uses, rather than
 * Locale.getDefault() — AppCompat happens to keep that in sync today, but it is an implementation detail this
 * composable should not depend on when an explicit, observable locale is already available. */
@Composable
fun formatPercent(value: Int): String = NumberFormat.getPercentInstance(LocalLocale.current.platformLocale).format(value / 100.0)
