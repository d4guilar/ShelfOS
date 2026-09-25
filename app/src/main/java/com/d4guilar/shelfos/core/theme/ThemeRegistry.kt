// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.theme

enum class ThemeId(val storageKey: String) {
    CLASSIC("classic"), DARK("dark"), RETRO_LIGHT("retro_light"),
    RETRO_DARK("retro_dark"), PAPER("paper")
}

data class ThemeRegistration(val id: ThemeId, val label: String, val available: Boolean)

object ThemeRegistry {
    val entries = listOf(
        ThemeRegistration(ThemeId.CLASSIC, "ShelfOS Classic", true),
        ThemeRegistration(ThemeId.DARK, "ShelfOS Dark", true),
        ThemeRegistration(ThemeId.RETRO_LIGHT, "Retro Apple UI", false),
        ThemeRegistration(ThemeId.RETRO_DARK, "Retro Apple UI Dark", false),
        ThemeRegistration(ThemeId.PAPER, "Paper / Vintage Library", false),
    )

    fun resolve(storageKey: String?): ThemeId = entries
        .firstOrNull { it.available && it.id.storageKey == storageKey }?.id ?: ThemeId.CLASSIC

    fun isAvailable(id: ThemeId) = entries.any { it.id == id && it.available }
}
