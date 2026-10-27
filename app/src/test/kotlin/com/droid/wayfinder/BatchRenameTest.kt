// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.RenameItem
import com.droid.wayfinder.data.RenameRule
import com.droid.wayfinder.data.duplicateName
import com.droid.wayfinder.data.fuzzyScore
import com.droid.wayfinder.data.planBatchRename
import com.droid.wayfinder.data.splitExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRenameTest {
    private fun files(vararg n: String) = n.map { RenameItem(it, false) }

    @Test fun extensionSplitting() {
        assertEquals("photo" to ".jpg", splitExtension("photo.jpg", false))
        assertEquals("a.tar" to ".gz", splitExtension("a.tar.gz", false))
        assertEquals(".bashrc" to "", splitExtension(".bashrc", false))
        assertEquals("my.folder" to "", splitExtension("my.folder", true))
    }

    @Test fun findReplace() {
        val p = planBatchRename(files("IMG_001.jpg", "IMG_002.jpg", "notes.txt"), RenameRule.FindReplace("IMG_", "Trip "), emptyList())
        assertEquals(listOf("Trip 001.jpg", "Trip 002.jpg", "notes.txt"), p.map { it.new })
        assertEquals(listOf(true, true, false), p.map { it.changed })
        assertTrue(p.all { it.conflict == null })
    }

    @Test fun findReplaceCaseInsensitive() {
        val p = planBatchRename(files("Report.DOC"), RenameRule.FindReplace(".doc", ".docx", matchCase = false), emptyList())
        assertEquals("Report.docx", p[0].new)
        val q = planBatchRename(files("Report.DOC"), RenameRule.FindReplace(".doc", ".docx", matchCase = true), emptyList())
        assertEquals("Report.DOC", q[0].new)
    }

    @Test fun prefixSuffixGoesBeforeExtension() {
        val items = listOf(RenameItem("a.txt", false), RenameItem("dir.v2", true), RenameItem(".env", false))
        val p = planBatchRename(items, RenameRule.PrefixSuffix("old_", "_bak"), emptyList())
        assertEquals(listOf("old_a_bak.txt", "old_dir.v2_bak", "old_.env_bak"), p.map { it.new })
    }

    @Test fun numberedKeepsExtensionAndPads() {
        val names = (1..12).map { "x$it.png" }.toTypedArray()
        val p = planBatchRename(files(*names), RenameRule.Numbered("Holiday", 1), emptyList())
        assertEquals("Holiday 01.png", p[0].new)
        assertEquals("Holiday 12.png", p[11].new)
        val q = planBatchRename(files("a.txt", "b"), RenameRule.Numbered("", 7), emptyList())
        assertEquals(listOf("7.txt", "8"), q.map { it.new })
    }

    @Test fun conflictsWithExistingAndDuplicates() {
        // "b.txt" exists in the folder and is not being renamed → conflict.
        val p = planBatchRename(files("a.txt"), RenameRule.FindReplace("a", "b"), listOf("a.txt", "B.TXT"))
        assertNotNull(p[0].conflict)
        // Two items mapping to the same name → both flagged.
        val q = planBatchRename(files("x1.txt", "x2.txt"), RenameRule.FindReplace("x1", "x2"), listOf("x1.txt", "x2.txt"))
        assertNotNull(q[0].conflict); assertNotNull(q[1].conflict)
        // Swapping names within the batch is allowed (old names are freed).
        val r = planBatchRename(files("1.txt", "2.txt"), RenameRule.Numbered("", 2), listOf("1.txt", "2.txt"))
        assertEquals(listOf("2.txt", "3.txt"), r.map { it.new })
        assertTrue(r.all { it.conflict == null })
        // Invalid characters / empty names.
        assertNotNull(planBatchRename(files("a"), RenameRule.FindReplace("a", "x/y"), emptyList())[0].conflict)
        assertNotNull(planBatchRename(files("a"), RenameRule.FindReplace("a", ""), emptyList())[0].conflict)
    }

    @Test fun duplicateNames() {
        val existing = mutableSetOf("report.pdf")
        assertEquals("report copy.pdf", duplicateName("report.pdf", false) { it in existing })
        existing += "report copy.pdf"
        assertEquals("report copy 2.pdf", duplicateName("report.pdf", false) { it in existing })
        existing += "report copy 2.pdf"
        assertEquals("report copy 3.pdf", duplicateName("report.pdf", false) { it in existing })
        assertEquals("My.Folder copy", duplicateName("My.Folder", true) { false })
    }

    @Test fun fuzzyMatching() {
        assertNotNull(fuzzyScore("nf", "New folder"))
        assertNull(fuzzyScore("xyz", "New folder"))
        assertEquals(0, fuzzyScore("", "anything"))
        // Prefix/contiguous matches outrank scattered ones.
        assertTrue(fuzzyScore("ren", "Rename")!! > fuzzyScore("ren", "Reopen closed tab")!!)
        assertTrue(fuzzyScore("dup", "Duplicate")!! > fuzzyScore("dup", "Dual pane")!!)
    }
}
