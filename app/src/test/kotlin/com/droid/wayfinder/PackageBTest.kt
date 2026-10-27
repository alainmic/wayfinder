// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.data.ModifiedRange
import com.droid.wayfinder.data.RecentFiles
import com.droid.wayfinder.data.SearchCategory
import com.droid.wayfinder.data.SearchFilter
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ZipFileSystem
import com.droid.wayfinder.data.sortEntries
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipPathTest {
    @Test fun splitsArchiveAndInnerPath() {
        assertEquals("/d/a.zip" to "", FsPath.zipSplit("/d/a.zip!"))
        assertEquals("/d/a.zip" to "", FsPath.zipSplit("/d/a.zip!/"))
        assertEquals("/d/A.ZIP" to "x/y.txt", FsPath.zipSplit("/d/A.ZIP!/x//y.txt/"))
        assertNull(FsPath.zipSplit("/d/a.zip"))
        assertNull(FsPath.zipSplit("/d/a.zip!x"))
        assertNull(FsPath.zipSplit("smb://h/s/a.zip!/x"))
        assertTrue(FsPath.isZip("/d/a.zip!/x") && FsPath.isReadOnly("/d/a.zip!/x") && !FsPath.isLocalFile("/d/a.zip!/x"))
    }

    @Test fun navigationWorksInsideArchives() {
        assertEquals("/d/a.zip!", FsPath.zipRoot("/d/a.zip/"))
        assertEquals("/d/a.zip!", FsPath.parent("/d/a.zip!/x"))
        assertEquals("/d", FsPath.parent("/d/a.zip!"))
        assertEquals("a.zip", FsPath.name("/d/a.zip!"))
        assertEquals("x", FsPath.name("/d/a.zip!/x"))
        assertEquals("/d/a.zip!/x", FsPath.child("/d/a.zip!", "x"))
        val crumbs = FsPath.breadcrumbs("/s/d/a.zip!/x", mapOf("/s" to "Internal"))
        assertEquals(listOf("Internal", "d", "a.zip", "x"), crumbs.map { it.first })
        assertEquals("/s/d/a.zip!", crumbs[2].second)
    }

    @Test fun virtualCollections() {
        assertTrue(FsPath.isVirtual(FsPath.RECENTS_ROOT) && FsPath.isCollection(FsPath.STARRED_ROOT))
        assertEquals(FsPath.RECENTS_ROOT, FsPath.normalize(FsPath.RECENTS_ROOT))
        assertNull(FsPath.parent(FsPath.STARRED_ROOT))
        val tagPath = FsPath.tagPath(ColorTag.Review)
        assertEquals(ColorTag.Review, FsPath.tagOf(tagPath))
        assertEquals("Review", FsPath.name(tagPath))
        assertEquals(listOf("Starred" to FsPath.STARRED_ROOT), FsPath.breadcrumbs(FsPath.STARRED_ROOT, emptyMap()))
    }
}

class FileInfoTest {
    @Test fun posixModeStrings() {
        assertEquals("drwxr-xr-x", FileInfo.posixString(0x4000 or "755".toInt(8)))
        assertEquals("-rw-r-----", FileInfo.posixString(0x8000 or "640".toInt(8)))
        assertEquals("lrwxrwxrwx", FileInfo.posixString(0xA000 or "777".toInt(8)))
        assertEquals("-rwsr-xr-t", FileInfo.posixString(0x8000 or "5755".toInt(8)))
        assertEquals("0755", FileInfo.octal(0x4000 or "755".toInt(8)))
    }

    @Test fun checksumsOfKnownInput() = runBlocking {
        val c = FileInfo.checksums("abc".byteInputStream())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", c.sha256)
        assertEquals("352441c2", c.crc32)
    }

    @Test fun zipEntryNamesAreSanitised() {
        assertEquals("a/b.txt", FileInfo.safeZipEntryPath("/a//./b.txt"))
        assertEquals("a/b", FileInfo.safeZipEntryPath("a\\b"))
        assertNull(FileInfo.safeZipEntryPath("../evil"))
        assertNull(FileInfo.safeZipEntryPath("a/../../evil"))
        assertNull(FileInfo.safeZipEntryPath("..\\evil"))
        assertNull(FileInfo.safeZipEntryPath("/"))
    }
}

class MarksTest {
    @Test fun starToggleAllOrNothing() {
        val m = Marks().toggleStar(listOf("/a")).toggleStar(listOf("/a", "/b"))
        assertEquals(setOf("/a", "/b"), m.starred)
        assertTrue(m.toggleStar(listOf("/a", "/b")).starred.isEmpty())
    }

    @Test fun tagsToggleAndQuery() {
        var m = Marks().toggleTag(listOf("/a", "/b"), ColorTag.Urgent).toggleTag(listOf("/b"), ColorTag.Spec)
        assertEquals(listOf("/a", "/b"), m.pathsWith(ColorTag.Urgent))
        assertEquals(setOf(ColorTag.Urgent, ColorTag.Spec), m.tagsOf("/b"))
        m = m.toggleTag(listOf("/a", "/b"), ColorTag.Urgent)
        assertTrue(m.tagsOf("/a").isEmpty() && "/a" !in m.tags)
        assertEquals(ColorTag.Spec.ordinal, m.rank("/b"))
        assertEquals(Int.MAX_VALUE, m.rank("/a"))
    }

    @Test fun renameRekeysItemAndDescendants() {
        val m = Marks(setOf("/d/f", "/d/f/x", "/d/fx"), mapOf("/d/f/y" to setOf(ColorTag.WIP), "/other" to setOf(ColorTag.Build)))
        val r = m.renamed("/d/f", "/e/g")
        assertEquals(setOf("/e/g", "/e/g/x", "/d/fx"), r.starred)
        assertEquals(setOf(ColorTag.WIP), r.tagsOf("/e/g/y"))
        assertEquals(setOf(ColorTag.Build), r.tagsOf("/other"))
        assertTrue(m.renamed("/nothing", "/z") === m)
    }

    @Test fun serializationRoundTrip() {
        val m = Marks(setOf("/a b", "/c"), mapOf("/a b" to setOf(ColorTag.Approved, ColorTag.Urgent), "/d" to setOf(ColorTag.Archive)))
        assertEquals(m, Marks.parse(m.serialize()))
        assertEquals(Marks(), Marks.parse(null))
        assertEquals(Marks(setOf("/x")), Marks.parse("S\t/x\nT\tNoSuchTag\t/y\ngarbage"))
    }
}

class SearchFilterTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private fun f(name: String, modified: Long = now, dir: Boolean = false) = FileEntry("/x/$name", name, dir, modified = modified)

    @Test fun categories() {
        val img = SearchFilter(category = SearchCategory.Images)
        assertTrue(img.matches(f("a.JPG"), now, utc))
        assertFalse(img.matches(f("a.mp4"), now, utc))
        assertFalse(img.matches(f("pics", dir = true), now, utc))
        assertTrue(SearchFilter().matches(f("pics", dir = true), now, utc))
        assertTrue(SearchFilter(category = SearchCategory.Docs).matches(f("r.pdf"), now, utc))
        assertTrue(SearchFilter(category = SearchCategory.Apks).matches(f("app.apk"), now, utc))
        assertTrue(SearchFilter(category = SearchCategory.Code).matches(f("Main.kt"), now, utc))
    }

    @Test fun modifiedRanges() {
        val day = 24L * 3600 * 1000
        assertEquals(1_699_920_000_000L, ModifiedRange.Today.since(now, utc)) // 2023-11-14T00:00Z
        assertEquals(1_672_531_200_000L, ModifiedRange.Year.since(now, utc)) // 2023-01-01T00:00Z
        val week = SearchFilter(modified = ModifiedRange.Week)
        assertTrue(week.matches(f("a", now - 6 * day), now, utc))
        assertFalse(week.matches(f("a", now - 8 * day), now, utc))
        assertFalse(SearchFilter(modified = ModifiedRange.Today).matches(f("a", now - day), now, utc))
        assertTrue(SearchFilter(modified = ModifiedRange.Month).matches(f("a", now - 29 * day), now, utc))
        assertNull(ModifiedRange.Any.since(now, utc))
    }
}

class ZipBrowseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun makeZip(vararg entries: Pair<String, String?>): File {
        val f = File(tmp.root, "t.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                if (body != null) z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    @Test fun listsStatsAndReadsEntries() {
        val zip = makeZip("docs/" to null, "docs/a.txt" to "hello", "img/deep/p.bin" to "xyz", "../evil.txt" to "no", "top.md" to "#")
        val fs = ZipFileSystem()
        val root = FsPath.zipRoot(zip.path)
        val top = fs.list(root).map { it.name }.sorted()
        assertEquals(listOf("docs", "img", "top.md"), top) // unsafe entry hidden, implicit "img" folder synthesised
        val docs = fs.list("$root/docs")
        assertEquals(listOf("a.txt"), docs.map { it.name })
        assertEquals(5L, docs[0].size)
        assertEquals("$root/docs/a.txt", docs[0].path)
        assertTrue(fs.stat("$root/img")!!.isDir)
        assertTrue(fs.stat(root)!!.isDir)
        assertNull(fs.stat("$root/missing"))
        assertEquals("hello", fs.openInput("$root/docs/a.txt").use { it.readBytes().decodeToString() })
        assertTrue(runCatching { fs.delete("$root/top.md") }.isFailure)
        assertTrue(runCatching { fs.mkdir("$root/new") }.isFailure)
    }

    @Test fun copyOutOfArchive() = runBlocking {
        val zip = makeZip("docs/a.txt" to "hello", "docs/sub/b.txt" to "bee")
        val local = LocalFileSystem()
        val ops = FileOperations(FileRepository(local, local, null))
        val out = tmp.newFolder("out")
        val root = FsPath.zipRoot(zip.path)
        ops.transfer(listOf("$root/docs"), out.path, move = false, conflict = Conflict.KeepBoth) {}
        assertEquals("hello", File(out, "docs/a.txt").readText())
        assertEquals("bee", File(out, "docs/sub/b.txt").readText())
        assertTrue(zip.exists())
    }

    @Test fun extractRejectsZipSlip() = runBlocking {
        val zip = makeZip("ok.txt" to "fine", "../../evil.txt" to "bad")
        val local = LocalFileSystem()
        val ops = FileOperations(FileRepository(local, local, null))
        val dest = tmp.newFolder("dest")
        val r = runCatching { ops.extract(zip.path, dest.path, onProgress = {}) }
        assertTrue(r.isFailure)
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
        assertFalse(File(dest, "t").exists()) // partial output removed
    }
}

class RecentsAndSortTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun recentScanKeepsNewestAndSkipsHidden() {
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("root")
        File(root, "a/b").mkdirs(); File(root, ".hidden").mkdirs(); File(root, "Android/data").mkdirs()
        fun file(rel: String, age: Long) = File(root, rel).apply { writeText(rel); setLastModified(now - age) }
        file("new.txt", 1_000); file("a/b/newer.txt", 10); file("a/old.txt", 90L * 24 * 3600 * 1000)
        file(".hidden/secret.txt", 5); file("Android/data/x.txt", 5); file("a/mid.txt", 50_000)
        val res = RecentFiles.scan(listOf(root.path), since = now - 30L * 24 * 3600 * 1000, limit = 2)
        assertEquals(listOf("newer.txt", "new.txt"), res.map { it.name })
        assertEquals(3, RecentFiles.scan(listOf(root.path), since = now - 30L * 24 * 3600 * 1000).size)
    }

    @Test fun sortByPermissions() {
        val a = FileEntry("/a", "a", false, mode = 0x8000 or "644".toInt(8))
        val b = FileEntry("/b", "b", false, mode = 0x8000 or "600".toInt(8))
        assertEquals(listOf("b", "a"), sortEntries(listOf(a, b), SortKey.Permissions, true).map { it.name })
    }
}
