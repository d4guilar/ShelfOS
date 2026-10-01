// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.localization

/**
 * ShelfOS's own interface language, independent of the Android system language, Reading Presentation
 * (font/size/line height/margins/palette/Publisher-ShelfOS mode) and the language of any publication's
 * content. [tag] is the stable, locale-neutral BCP-47 identifier persisted and compared against; it is never
 * a localized display label (see AGENTS.md's localization rules).
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM_DEFAULT(null), ENGLISH("en"), SPANISH("es"), PORTUGUESE_BRAZIL("pt-BR");

    companion object {
        val DEFAULT = SYSTEM_DEFAULT

        /** Unrecognized tags (for example a locale chosen outside ShelfOS's own picker) fall back to following
         * the system language rather than silently pinning to whatever that tag happened to resolve to. */
        fun fromTag(tag: String?): AppLanguage = entries.find { it.tag == tag } ?: SYSTEM_DEFAULT
    }
}
