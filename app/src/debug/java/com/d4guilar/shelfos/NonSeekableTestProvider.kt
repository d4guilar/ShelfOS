// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Instrumentation-only provider whose pipe descriptors cannot seek. */
class NonSeekableTestProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> "non-seekable.pdf"
                    OpenableColumns.SIZE -> pdf.size.toLong()
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri) = "application/pdf"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "ShelfOS-test-provider") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    if (uri.lastPathSegment == "interrupted") {
                        output.write(pdf, 0, pdf.size / 2)
                        output.flush()
                        write.closeWithError("Injected interrupted copy")
                    } else output.write(pdf)
                }
            } catch (_: Exception) {
                runCatching { write.closeWithError("Injected provider failure") }
            }
        }
        return read
    }

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        val pdf: ByteArray by lazy {
            val document = PdfDocument()
            try {
                repeat(3) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText("Non-seekable page ${index + 1}", 20f, 80f,
                        Paint().apply { color = Color.BLACK; textSize = 18f })
                    document.finishPage(page)
                }
                ByteArrayOutputStream().use { output -> document.writeTo(output); output.toByteArray() }
            } finally { document.close() }
        }
    }
}
