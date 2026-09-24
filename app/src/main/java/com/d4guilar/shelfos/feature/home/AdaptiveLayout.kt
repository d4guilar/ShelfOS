// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.home

enum class NavigationLayout { BOTTOM, RAIL }
data class AdaptiveLayout(val navigation: NavigationLayout, val showDetails: Boolean)

fun adaptiveLayout(widthDp: Float, heightDp: Float): AdaptiveLayout {
    val rail = widthDp >= 600f || (heightDp < 480f && widthDp >= 480f)
    return AdaptiveLayout(
        navigation = if (rail) NavigationLayout.RAIL else NavigationLayout.BOTTOM,
        showDetails = widthDp >= 1000f && heightDp >= 480f,
    )
}
