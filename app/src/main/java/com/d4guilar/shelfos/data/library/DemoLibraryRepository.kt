// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

enum class MediaCategory(val label: String) { BOOK("Books"), COMIC("Comics"), MANGA("Manga"), DOCUMENT("Documents") }
enum class LibraryFilter(val label: String, val category: MediaCategory?) {
    FAVORITES("★ Favorites", null), BOOKS("Books", MediaCategory.BOOK), COMICS("Comics", MediaCategory.COMIC),
    MANGA("Manga", MediaCategory.MANGA), DOCUMENTS("Documents", MediaCategory.DOCUMENT)
}

/** Fictional, immutable presentation fixtures. These are not imported LibraryItems. */
data class DemoPublication(
    val id: String, val title: String, val creator: String, val category: MediaCategory,
    val progress: Int, val coverColor: Long, val coverMotif: Int,
    val synopsis: String, val initiallyFavorite: Boolean = true,
)

interface LibraryRepository { val publications: List<DemoPublication> }

class DemoLibraryRepository : LibraryRepository {
    override val publications = listOf(
        DemoPublication("quiet", "The Quiet Between", "Elise Moreau", MediaCategory.BOOK, 68, 0xFFAD6758, 0,
            "A fictional journey through the spaces between familiar places. An original ShelfOS sample publication."),
        DemoPublication("orbital", "Orbital Days", "Marcus Tan", MediaCategory.COMIC, 42, 0xFF304856, 1,
            "A small observatory follows the changing sky. An original sample for the Comics shelf."),
        DemoPublication("forest", "The Hollow Forest", "Sora Takeda", MediaCategory.MANGA, 15, 0xFF426455, 2,
            "A young cartographer records the paths of an imagined forest. An original sample for the Manga shelf."),
        DemoPublication("tides", "Evening Tides", "J. R. Kade", MediaCategory.COMIC, 100, 0xFF72516D, 1,
            "An illustrated voyage along an imaginary coast. Demo content, with no publication file attached."),
        DemoPublication("machine", "A Gentle Machine", "Lena Park", MediaCategory.BOOK, 28, 0xFF807461, 0,
            "An imagined collection of essays on making things with care."),
        DemoPublication("orchard", "The Last Orchard", "Daniel Cho", MediaCategory.BOOK, 12, 0xFF854A42, 2,
            "A fictional orchard, a changing season, and the stories kept in a notebook."),
        DemoPublication("sand", "Sand & Circuitry", "M. Ishikawa", MediaCategory.MANGA, 76, 0xFF9A6B38, 0,
            "An original sample of sequential storytelling set in an imagined landscape."),
        DemoPublication("horizon", "Velvet Horizon", "Talia Reyes", MediaCategory.BOOK, 3, 0xFF59677B, 1,
            "A fictional field journal of distant horizons and quiet mornings."),
        DemoPublication("field", "Field Notes", "ShelfOS Studio", MediaCategory.DOCUMENT, 0, 0xFF63746A, 2,
            "An original sample document illustrating the Documents shelf. There is no PDF attached."),
        DemoPublication("river", "The Paper River", "L. K. Moreno", MediaCategory.DOCUMENT, 100, 0xFF867D60, 0,
            "An imaginary study of paper, maps, and the places they describe."),
    )
}

fun filterPublications(items: List<DemoPublication>, filter: LibraryFilter, favorites: Set<String>, query: String = "") =
    items.filter { item ->
        (if (filter == LibraryFilter.FAVORITES) item.id in favorites else item.category == filter.category) &&
            (query.isBlank() || item.title.contains(query.trim(), ignoreCase = true) ||
                item.creator.contains(query.trim(), ignoreCase = true))
    }
