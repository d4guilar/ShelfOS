// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import java.text.NumberFormat
import java.util.Locale

/** Locale-aware rendering of a 0..100 reading-progress value (symbol placement/spacing varies by locale). */
fun formatPercent(value: Int): String = NumberFormat.getPercentInstance(Locale.getDefault()).format(value / 100.0)
