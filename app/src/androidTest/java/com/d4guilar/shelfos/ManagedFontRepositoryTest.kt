// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.FontImportException
import com.d4guilar.shelfos.core.reader.ManagedFontRepository
import com.d4guilar.shelfos.core.reader.ManagedFontSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ManagedFontRepositoryTest {
    @Test fun importRestartReplaceFailureAndRemovalOwnOnlyManagedCopies() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "managed-font-repository-test").apply { deleteRecursively() }
        val repository = ManagedFontRepository(context, root)
        val family = FileInputStream("/system/fonts/DancingScript-Regular.ttf").use {
            repository.import(it, "Dancing_Script.ttf")
        }
        assertEquals(ManagedFontSource.USER, family.source)
        assertEquals("Dancing Script", family.displayName)
        assertEquals(1, repository.epubResources().size)
        val managed = repository.epubResources().single().file
        assertTrue(managed.isFile)

        val restarted = ManagedFontRepository(context, root)
        assertEquals(family.id, restarted.families.value.last().id)
        val beforeReplacement = managed.readBytes()
        // Codex QA cleanup: distinct, genuinely different valid font content — not the same file re-imported —
        // so this actually proves replacement happened, rather than trivially matching byte-identical content
        // regardless of whether replace() did anything at all.
        val replacementBytes = File("/system/fonts/DroidSans.ttf").readBytes()
        assertFalse("Test fixture sanity: the two source fonts must differ", beforeReplacement.contentEquals(replacementBytes))
        val replacement = FileInputStream("/system/fonts/DroidSans.ttf").use {
            restarted.replace(family.id, it, "Replacement.ttf")
        }
        assertEquals(family.id, replacement.id)
        val afterReplacement = restarted.epubResources().single().file.readBytes()
        assertArrayEquals(replacementBytes, afterReplacement)
        assertFalse("Replacement should have actually changed the stored bytes", beforeReplacement.contentEquals(afterReplacement))
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith('.') })

        assertThrows(FontImportException::class.java) {
            runBlocking { restarted.import(ByteArrayInputStream("not a font".toByteArray()), "Broken.ttf") }
        }
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith('.') })
        assertEquals(1, restarted.epubResources().size)

        assertTrue(restarted.remove(family.id))
        assertFalse(managed.exists())
        assertTrue(restarted.epubResources().isEmpty())
        root.deleteRecursively()
    }
}
