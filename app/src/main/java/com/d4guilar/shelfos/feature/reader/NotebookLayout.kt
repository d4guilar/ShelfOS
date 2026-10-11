// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

enum class NotebookPresentation { DIALOG, SIDE_BY_SIDE }

data class NotebookLayout(val presentation: NotebookPresentation, val paneWidthDp: Float)

/** Reader-host adaptive policy. Width and height are the actual available constraints, never device dimensions. */
fun notebookPresentation(widthDp: Float, heightDp: Float): NotebookLayout =
    if (widthDp >= 840f && heightDp >= 480f) {
        NotebookLayout(NotebookPresentation.SIDE_BY_SIDE, (widthDp * 0.36f).coerceIn(320f, 400f))
    } else NotebookLayout(NotebookPresentation.DIALOG, 0f)
