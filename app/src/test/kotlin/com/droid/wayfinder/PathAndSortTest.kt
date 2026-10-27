// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.formatSize
import com.droid.wayfinder.data.naturalCompare
import com.droid.wayfinder.data.sortEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FsPathTest {
    @Test fun parentOfLocal() {
        assertEquals("/a", FsPath.parent("/a/b"))
        assertEquals("/", FsPath.parent("/a"))
        assertNull(FsPath.parent("/"))
    }

    @Test fun parentOfSmb() {
        assertEquals("smb://nas/media", FsPath.parent("smb://nas/media/x"))
        assertEquals("smb://nas", FsPath.parent("smb://nas/media"))
        assertEquals("smb://", FsPath.parent("smb://nas"))
        assertNull(FsPath.parent("smb://"))
    }

    @Test fun parseSmbParts() {
        val p = FsPath.parseSmb("smb://nas/media/Movies/2020")
        assertEquals("nas", p.host); assertEquals("media", p.share); assertEquals("Movies/2020", p.rel)
        assertNull(FsPath.parseSmb("smb://nas").share)
    }

    @Test fun serverInputFormats() {
        assertEquals("smb://nas/media", FsPath.parseServerInput("smb://nas/media/")!!.path)
        assertEquals("smb://192.168.1.20/photos", FsPath.parseServerInput("\\\\192.168.1.20\\photos")!!.path)
        val u = FsPath.parseServerInput("smb://bob@nas/docs")!!
        assertEquals("bob", u.user); assertEquals("nas", u.host); assertEquals("smb://nas/docs", u.path)
        assertNull(FsPath.parseServerInput("  "))
    }

    @Test fun withinChecks() {
        assertTrue(FsPath.isWithin("/a/b", "/a/b/c"))
        assertTrue(FsPath.isWithin("/a/b", "/a/b"))
        assertTrue(!FsPath.isWithin("/a/b", "/a/bc"))
    }

    @Test fun breadcrumbsUseRootLabel() {
        val roots = mapOf("/storage/emulated/0" to "Internal storage")
        val c = FsPath.breadcrumbs("/storage/emulated/0/Documents/Work", roots)
        assertEquals(listOf("Internal storage", "Documents", "Work"), c.map { it.first })
        assertEquals("/storage/emulated/0/Documents", c[1].second)
        val smb = FsPath.breadcrumbs("smb://nas/media/x", roots)
        assertEquals(listOf("Network", "nas", "media", "x"), smb.map { it.first })
    }

    @Test fun childJoin() {
        assertEquals("/a/b", FsPath.child("/a", "b"))
        assertEquals("/b", FsPath.child("/", "b"))
    }
}

class SortingTest {
    private fun f(name: String, dir: Boolean = false, size: Long = 0, mod: Long = 0) =
        FileEntry("/x/$name", name, dir, size, mod)

    @Test fun naturalOrder() {
        assertTrue(naturalCompare("file2", "file10") < 0)
        assertTrue(naturalCompare("File", "file") == 0)
        assertTrue(naturalCompare("a", "B") < 0)
    }

    @Test fun foldersFirstThenName() {
        val out = sortEntries(listOf(f("b.txt"), f("a", dir = true), f("a.txt")), SortKey.Name, true).map { it.name }
        assertEquals(listOf("a", "a.txt", "b.txt"), out)
    }

    @Test fun descendingKeepsFoldersFirst() {
        val out = sortEntries(listOf(f("a.txt", size = 5), f("z", dir = true), f("b.txt", size = 9)), SortKey.Size, false).map { it.name }
        assertEquals(listOf("z", "b.txt", "a.txt"), out)
    }

    @Test fun sizeFormatting() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1 KB", formatSize(1024))
        assertEquals("1.5 MB", formatSize(1_572_864))
    }
}
