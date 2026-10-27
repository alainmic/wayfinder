// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.TrashManager
import com.droid.wayfinder.ui.TabState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TabStateTest {
    private fun entries(vararg n: String) = n.map { FileEntry("/d/$it", it, false) }
    private fun tab() = TabState(1, "/d", entries = entries("a", "b", "c", "d", "e"))

    @Test fun historyBackForward() {
        val t = tab().navigatedTo("/e").navigatedTo("/f")
        assertEquals(listOf("/d", "/e"), t.back)
        val b = t.goBack()
        assertEquals("/e", b.path); assertEquals(listOf("/f"), b.forward)
        val f = b.goForward()
        assertEquals("/f", f.path); assertTrue(f.forward.isEmpty())
        assertTrue(f.navigatedTo("/g").forward.isEmpty())
    }

    @Test fun ctrlAndShiftSelection() {
        var t = tab().clicked("/d/b", ctrl = false, shift = false, showHidden = false)
        assertEquals(setOf("/d/b"), t.selection)
        t = t.clicked("/d/d", ctrl = false, shift = true, showHidden = false)
        assertEquals(setOf("/d/b", "/d/c", "/d/d"), t.selection)
        t = t.clicked("/d/c", ctrl = true, shift = false, showHidden = false)
        assertEquals(setOf("/d/b", "/d/d"), t.selection)
    }

    @Test fun keyboardFocusMovesAndExtends() {
        var t = tab().moveFocus(1, false, false, false)
        assertEquals("/d/a", t.focused)
        t = t.moveFocus(2, true, false, false)
        assertEquals(setOf("/d/a", "/d/b", "/d/c"), t.selection)
        assertEquals("/d/e", t.moveFocus(100, false, false, false).focused)
    }

    @Test fun selectAllAndInvert() {
        val t = tab().clicked("/d/a", false, false, false)
        assertEquals(4, t.invertSelection(false).selection.size)
        assertEquals(5, t.selectAll(false).selection.size)
    }

    @Test fun hiddenFilesAreFiltered() {
        val t = TabState(1, "/d", entries = listOf(FileEntry("/d/.x", ".x", false), FileEntry("/d/y", "y", false)))
        assertEquals(1, t.visible(false).size)
        assertEquals(2, t.visible(true).size)
    }
}

class FileOperationsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ops(): FileOperations {
        val local = LocalFileSystem()
        return FileOperations(FileRepository(local, local, null))
    }

    @Test fun copyKeepsBothOnConflictAndMoveRemovesSource() = runBlocking {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        File(src, "a.txt").writeText("hello"); File(dst, "a.txt").writeText("old")
        val o = ops()
        val r = o.transfer(listOf(File(src, "a.txt").path), dst.path, move = false, conflict = Conflict.KeepBoth) {}
        assertEquals(File(dst, "a (1).txt").path, r[0].target)
        assertEquals("hello", File(dst, "a (1).txt").readText())
        assertEquals("old", File(dst, "a.txt").readText())
        o.transfer(listOf(File(src, "a.txt").path), dst.path, move = true, conflict = Conflict.Replace) {}
        assertFalse(File(src, "a.txt").exists())
        assertEquals("hello", File(dst, "a.txt").readText())
    }

    @Test fun copyFolderRecursivelyAndRefuseIntoItself() = runBlocking {
        val root = tmp.newFolder("root")
        File(root, "d/sub").mkdirs(); File(root, "d/sub/f.bin").writeBytes(ByteArray(1000) { it.toByte() })
        val out = tmp.newFolder("out")
        ops().transfer(listOf(File(root, "d").path), out.path, false, Conflict.KeepBoth) {}
        assertEquals(1000, File(out, "d/sub/f.bin").length())
        val failed = runCatching { ops().transfer(listOf(File(root, "d").path), File(root, "d/sub").path, false, Conflict.KeepBoth) {} }
        assertTrue(failed.isFailure)
    }

    @Test fun zipRoundTrip() = runBlocking {
        val dir = tmp.newFolder("z")
        File(dir, "in/inner").mkdirs(); File(dir, "in/inner/x.txt").writeText("zip me")
        val o = ops()
        val zip = o.compress(listOf(File(dir, "in").path), File(dir, "in.zip").path) {}
        val out = o.extract(zip, dir.path, onProgress = {})
        assertEquals("zip me", File(out, "in/inner/x.txt").readText())
    }

    @Test fun trashAndRestore() {
        val trash = TrashManager(tmp.newFolder("trash"))
        val f = tmp.newFile("doc.txt").apply { writeText("keep") }
        val moved = trash.moveToTrash(listOf(f.path))
        assertFalse(f.exists())
        assertEquals(1, trash.list().size)
        assertEquals(f.path, trash.originalOf(moved[0].trashed))
        trash.restore(moved[0].trashed)
        assertEquals("keep", f.readText())
        assertTrue(trash.list().isEmpty())
    }
}
