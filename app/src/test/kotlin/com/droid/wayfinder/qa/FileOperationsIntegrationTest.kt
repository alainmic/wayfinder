// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import com.droid.wayfinder.data.ColorTag
import com.droid.wayfinder.data.Conflict
import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.data.ModifiedRange
import com.droid.wayfinder.data.Progress
import com.droid.wayfinder.data.RecentFiles
import com.droid.wayfinder.data.RenameItem
import com.droid.wayfinder.data.RenameRule
import com.droid.wayfinder.data.SearchCategory
import com.droid.wayfinder.data.SearchFilter
import com.droid.wayfinder.data.SearchScope
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.TrashManager
import com.droid.wayfinder.data.planBatchRename
import com.droid.wayfinder.data.sortEntries
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Integration tests of FileRepository / FileOperations / TrashManager / ZipFileSystem on real temp folders. */
class FileOperationsIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var trash: TrashManager
    private lateinit var repo: FileRepository
    private lateinit var ops: FileOperations
    private val progress = mutableListOf<Progress>()

    @Before fun setUp() {
        root = tmp.newFolder("root")
        trash = TrashManager(File(root, ".Trash"))
        repo = FileRepository(LocalFileSystem(), LocalFileSystem(), trash)
        ops = FileOperations(repo)
    }

    private fun f(rel: String, text: String = rel) = File(root, rel).apply { parentFile?.mkdirs(); writeText(text) }
    private fun d(rel: String) = File(root, rel).apply { mkdirs() }
    private fun p(rel: String) = File(root, rel).path
    private fun transfer(src: List<String>, dest: String, move: Boolean, c: Conflict = Conflict.KeepBoth) =
        runBlocking { ops.transfer(src, dest, move, c) { progress += it } }

    // ------------------------------------------------------------- copy / move / conflicts

    @Test fun copyFileAndFolderTreeKeepsSourcesAndReportsProgress() {
        f("src/a.txt", "AAA"); f("src/sub/b.txt", "BB"); d("dst")
        val res = transfer(listOf(p("src")), p("dst"), move = false)
        assertEquals(listOf(p("dst/src")), res.map { it.target })
        assertEquals("AAA", File(root, "dst/src/a.txt").readText())
        assertEquals("BB", File(root, "dst/src/sub/b.txt").readText())
        assertTrue(File(root, "src/a.txt").exists())
        val last = progress.last()
        assertEquals("Copying", last.title)
        assertEquals(5L, last.totalBytes); assertEquals(5L, last.doneBytes)
        assertEquals(2, last.totalItems); assertEquals(2, last.doneItems)
        assertEquals(1f, last.fraction!!, 0.0001f)
    }

    @Test fun moveWithinVolumeRenamesAndRemovesSource() {
        f("a.txt", "x"); d("dst")
        val res = transfer(listOf(p("a.txt")), p("dst"), move = true)
        assertEquals(p("dst/a.txt"), res.single().target)
        assertFalse(File(root, "a.txt").exists())
        assertEquals("x", File(root, "dst/a.txt").readText())
        assertEquals("Moving", progress.first().title)
    }

    @Test fun conflictReplaceOverwritesDestination() {
        f("a.txt", "new"); f("dst/a.txt", "old")
        assertEquals(listOf("a.txt"), ops.conflicts(listOf(p("a.txt")), p("dst")))
        transfer(listOf(p("a.txt")), p("dst"), move = false, c = Conflict.Replace)
        assertEquals("new", File(root, "dst/a.txt").readText())
        assertEquals(1, File(root, "dst").list()!!.size)
    }

    @Test fun conflictReplaceFolderWithFolderReplacesWholeTree() {
        f("src/x/keep.txt", "1"); f("dst/x/stale.txt", "old")
        transfer(listOf(p("src/x")), p("dst"), move = true, c = Conflict.Replace)
        assertTrue(File(root, "dst/x/keep.txt").exists())
        assertFalse("old tree must be replaced, not merged", File(root, "dst/x/stale.txt").exists())
        assertFalse(File(root, "src/x").exists())
    }

    @Test fun conflictKeepBothCreatesNumberedCopy() {
        f("a.txt", "new"); f("dst/a.txt", "old"); f("dst/a (1).txt", "older")
        val res = transfer(listOf(p("a.txt")), p("dst"), move = false, c = Conflict.KeepBoth)
        assertEquals(p("dst/a (2).txt"), res.single().target)
        assertEquals("old", File(root, "dst/a.txt").readText())
        assertEquals("new", File(root, "dst/a (2).txt").readText())
    }

    @Test fun conflictSkipLeavesDestinationUntouchedAndReportsNothing() {
        f("a.txt", "new"); f("b.txt", "b"); f("dst/a.txt", "old")
        val res = transfer(listOf(p("a.txt"), p("b.txt")), p("dst"), move = true, c = Conflict.Skip)
        assertEquals(listOf(p("dst/b.txt")), res.map { it.target })
        assertEquals("old", File(root, "dst/a.txt").readText())
        assertTrue("skipped source must stay", File(root, "a.txt").exists())
    }

    @Test fun pasteCopyIntoSameFolderMakesNumberedCopyButMoveIsNoOp() {
        f("a.txt", "x")
        val copy = transfer(listOf(p("a.txt")), root.path, move = false, c = Conflict.Replace)
        assertEquals(p("a (1).txt"), copy.single().target)
        assertEquals("x", File(root, "a.txt").readText())
        val move = transfer(listOf(p("a.txt")), root.path, move = true, c = Conflict.Replace)
        assertTrue(move.isEmpty())
        assertTrue(File(root, "a.txt").exists())
    }

    @Test fun copyOrMoveFolderIntoItselfIsRejected() {
        d("dir/sub")
        val e = assertThrows(IOException::class.java) { transfer(listOf(p("dir")), p("dir/sub"), move = true) }
        assertTrue(e.message!!.contains("into itself"))
        assertTrue(File(root, "dir/sub").isDirectory)
        // A sibling whose name merely starts with the same prefix is fine.
        d("dir2")
        transfer(listOf(p("dir2")), p("dir/sub"), move = false)
        assertTrue(File(root, "dir/sub/dir2").isDirectory)
    }

    @Test fun uniqueNameHandlesExtensionsDotfilesAndFolders() {
        f("notes.txt"); f(".bashrc"); d("Folder"); f("archive.tar.gz")
        assertEquals("notes (1).txt", ops.uniqueName(root.path, "notes.txt"))
        assertEquals(".bashrc (1)", ops.uniqueName(root.path, ".bashrc"))
        assertEquals("Folder (1)", ops.uniqueName(root.path, "Folder"))
        assertEquals("archive.tar (1).gz", ops.uniqueName(root.path, "archive.tar.gz"))
        assertEquals("fresh.txt", ops.uniqueName(root.path, "fresh.txt"))
    }

    @Test fun deletePermanentlyRemovesFilesAndTrees() = runBlocking {
        f("a.txt"); f("dir/x/y.txt")
        ops.deletePermanently(listOf(p("a.txt"), p("dir"))) { progress += it }
        assertFalse(File(root, "a.txt").exists()); assertFalse(File(root, "dir").exists())
        assertEquals(2, progress.last().doneItems)
    }

    @Test fun measureCountsBytesAndFiles() = runBlocking {
        f("m/a", "12345"); f("m/b/c", "123")
        assertEquals(8L to 2, ops.measure(listOf(p("m"))))
    }

    @Test fun unsupportedSchemeIsAClearError() {
        // dav:// is a known remote scheme, but this repository has no WebDAV backend registered.
        val dav = assertThrows(IOException::class.java) { repo.fs("dav://host/x") }
        assertTrue(dav.message!!.contains("Unsupported location: dav://"))
        // Unknown schemes are not remote: they resolve to the local file system and fail as missing folders.
        assertThrows(IOException::class.java) { repo.list("gopher://x/y") }
    }

    // ------------------------------------------------------------- duplicate

    @Test fun duplicateUsesCopySuffixesAndCopiesFolders() = runBlocking {
        f("photo.jpg", "img"); f("dir/inner.txt", "i")
        val first = ops.duplicate(listOf(p("photo.jpg"), p("dir"))) {}
        assertEquals(listOf(p("photo copy.jpg"), p("dir copy")), first)
        val second = ops.duplicate(listOf(p("photo.jpg"))) {}
        assertEquals(listOf(p("photo copy 2.jpg")), second)
        assertEquals("i", File(root, "dir copy/inner.txt").readText())
        assertEquals("img", File(root, "photo copy 2.jpg").readText())
    }

    @Test fun duplicateOfMissingItemFails() {
        assertThrows(IOException::class.java) { runBlocking { ops.duplicate(listOf(p("ghost.txt"))) {} } }
    }

    // ------------------------------------------------------------- trash

    @Test fun trashMoveListRestoreAndEmpty() {
        f("docs/a.txt", "A"); d("docs/folder")
        val t = trash.moveToTrash(listOf(p("docs/a.txt"), p("docs/folder")))
        assertEquals(2, t.size)
        assertFalse(File(root, "docs/a.txt").exists())
        val listed = trash.list()
        assertEquals(setOf("a.txt", "folder"), listed.map { it.name }.toSet())
        assertTrue(listed.all { it.subtitle == p("docs") })
        assertTrue(trash.isInTrash(listed.first().path))
        assertTrue(trash.isInTrash(FsPath.TRASH_ROOT))
        assertEquals(p("docs/a.txt"), trash.originalOf(t[0].trashed))

        val restored = trash.restore(t[0].trashed)
        assertEquals(p("docs/a.txt"), restored)
        assertEquals("A", File(restored).readText())
        assertEquals(1, trash.list().size)

        trash.empty()
        assertTrue(trash.list().isEmpty())
    }

    @Test fun trashRestoreRecreatesMissingParentAndAvoidsOverwriting() {
        f("gone/a.txt", "trashed")
        val t = trash.moveToTrash(listOf(p("gone/a.txt"))).single()
        File(root, "gone").deleteRecursively()
        assertEquals(p("gone/a.txt"), trash.restore(t.trashed))
        val t2 = trash.moveToTrash(listOf(p("gone/a.txt"))).single()
        f("gone/a.txt", "newer")
        val r2 = trash.restore(t2.trashed)
        assertEquals(p("gone/a (restored 1).txt"), r2)
        assertEquals("newer", File(root, "gone/a.txt").readText())
        assertEquals("trashed", File(r2).readText())
    }

    /** Regression: two quick deletes (same millisecond, same index) must not share a trash slot. */
    @Test fun rapidSuccessiveTrashCallsKeepEveryItemAndOrigin() {
        val names = (1..20).map { "f$it.txt" }
        names.forEach { f("q/$it", it) }
        val trashed = names.map { trash.moveToTrash(listOf(p("q/$it"))).single() }
        assertEquals(20, trash.list().size)
        trashed.forEach { assertEquals(it.original, trash.originalOf(it.trashed)) }
        trashed.forEach { trash.restore(it.trashed) }
        names.forEach { assertEquals(it, File(root, "q/$it").readText()) }
    }

    @Test fun trashDeletePermanentlyRemovesSlot() {
        f("a.txt")
        val t = trash.moveToTrash(listOf(p("a.txt"))).single()
        trash.deletePermanently(t.trashed)
        assertTrue(trash.list().isEmpty())
        assertNull(trash.originalOf(t.trashed))
    }

    @Test fun repositoryListsTrashRoot() {
        f("a.txt")
        trash.moveToTrash(listOf(p("a.txt")))
        assertEquals(listOf("a.txt"), repo.list(FsPath.TRASH_ROOT).map { it.name })
    }

    // ------------------------------------------------------------- compress / extract / zip browse

    @Test fun compressExtractRoundTripPreservesTreeAndContent() = runBlocking {
        f("proj/readme.md", "# hi"); f("proj/src/main.kt", "fun main() {}"); d("proj/empty"); f("solo.txt", "solo")
        val zip = ops.compress(listOf(p("proj"), p("solo.txt")), p("out.zip")) {}
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("proj/", "proj/readme.md", "proj/src/main.kt", "proj/empty/", "solo.txt")))
        }
        d("x")
        val out = ops.extract(zip, p("x"), onProgress = {})
        assertEquals(p("x/out"), out)
        assertEquals("# hi", File(out, "proj/readme.md").readText())
        assertEquals("fun main() {}", File(out, "proj/src/main.kt").readText())
        assertTrue(File(out, "proj/empty").isDirectory)
        assertEquals("solo", File(out, "solo.txt").readText())
        // Extracting again never merges into the previous folder.
        assertEquals(p("x/out (1)"), ops.extract(zip, p("x"), onProgress = {}))
    }

    @Test fun failedCompressLeavesNoPartialArchive() {
        val zip = p("bad.zip")
        assertThrows(Exception::class.java) {
            runBlocking { ops.compress(listOf(p("missing-dir/file")), zip) { throw IllegalStateException("boom") } }
        }
        assertFalse(File(zip).exists())
    }

    private fun makeZip(name: String, vararg entries: Pair<String, String?>): File {
        val z = File(root, name)
        ZipOutputStream(z.outputStream()).use { out ->
            entries.forEach { (n, body) ->
                out.putNextEntry(ZipEntry(n)); body?.let { out.write(it.toByteArray()) }; out.closeEntry()
            }
        }
        return z
    }

    @Test fun zipBrowseSynthesisesFoldersAndIsReadOnly() {
        val z = makeZip("pack.zip", "deep/er/file.txt" to "D", "top.txt" to "T")
        val zr = FsPath.zipRoot(z.path)
        assertEquals(setOf("deep", "top.txt"), repo.list(zr).map { it.name }.toSet())
        val deep = repo.list(FsPath.child(zr, "deep")).single()
        assertTrue(deep.isDir); assertEquals("er", deep.name)
        val fs = repo.fs(zr)
        assertEquals("T", fs.openInput(FsPath.child(zr, "top.txt")).use { it.readBytes().decodeToString() })
        assertThrows(IOException::class.java) { fs.mkdir(FsPath.child(zr, "new")) }
        assertThrows(IOException::class.java) { fs.delete(FsPath.child(zr, "top.txt")) }
        assertThrows(IOException::class.java) { fs.openOutput(FsPath.child(zr, "w.txt")) }
        assertTrue(FsPath.isReadOnly(zr))
    }

    @Test fun copyFolderOutOfZipToDisk() {
        val z = makeZip("pack.zip", "deep/er/file.txt" to "D", "deep/b.txt" to "B")
        d("out")
        val res = transfer(listOf(FsPath.child(FsPath.zipRoot(z.path), "deep")), p("out"), move = false)
        assertEquals(p("out/deep"), res.single().target)
        assertEquals("D", File(root, "out/deep/er/file.txt").readText())
        assertEquals("B", File(root, "out/deep/b.txt").readText())
        assertTrue("archive untouched", z.exists())
    }

    @Test fun zipIndexRefreshesAfterArchiveChanges() {
        val z = makeZip("pack.zip", "a.txt" to "1")
        val zr = FsPath.zipRoot(z.path)
        assertEquals(listOf("a.txt"), repo.list(zr).map { it.name })
        Thread.sleep(1100) // mtime granularity
        makeZip("pack.zip", "a.txt" to "1", "b.txt" to "22")
        assertEquals(setOf("a.txt", "b.txt"), repo.list(zr).map { it.name }.toSet())
    }

    // ------------------------------------------------------------- batch rename plan (applied in BrowserViewModelQaTest)

    @Test fun batchRenameSwapIsNotAConflict() {
        val plan = planBatchRename(listOf(RenameItem("a.txt", false), RenameItem("b.txt", false)), RenameRule.FindReplace("a", "b"), listOf("a.txt", "b.txt"))
        // a.txt → b.txt collides with b.txt → b.txt (unchanged) inside the batch: flagged as duplicate.
        assertEquals(2, plan.count { it.conflict == "Duplicate name" })
        val numbered = planBatchRename(listOf(RenameItem("2.txt", false), RenameItem("1.txt", false)), RenameRule.Numbered("", 1), listOf("1.txt", "2.txt"))
        assertTrue("1↔2 swap must be allowed", numbered.all { it.conflict == null })
        assertEquals(listOf("1.txt", "2.txt"), numbered.map { it.new })
    }

    // ------------------------------------------------------------- recents & search filters & marks

    @Test fun recentsScanHonoursLimitWindowHiddenAndAndroidFolder() {
        val now = System.currentTimeMillis()
        fun file(rel: String, ageDays: Long) = f(rel).apply { setLastModified(now - ageDays * 86_400_000L) }
        file("new.txt", 0); file("week.txt", 6); file("old.txt", 60)
        file(".hidden/secret.txt", 0); file("Android/data/app.txt", 0); file("deep/a/b/c/leaf.txt", 1)
        val r = RecentFiles.scan(listOf(root.path), now - 30L * 86_400_000L, limit = 10)
        assertEquals(listOf("new.txt", "leaf.txt", "week.txt"), r.map { it.name })
        assertEquals(2, RecentFiles.scan(listOf(root.path), 0, limit = 2).size)
        assertTrue(RecentFiles.scan(listOf(root.path), 0, isActive = { false }).isEmpty())
    }

    @Test fun searchFilterCombinesCategoryAndDate() {
        val now = System.currentTimeMillis()
        val img = FileEntry("/x/cat.png", "cat.png", false, modified = now)
        val oldImg = FileEntry("/x/old.jpg", "old.jpg", false, modified = now - 40L * 86_400_000L)
        val dir = FileEntry("/x/cats", "cats", true, modified = now)
        val f = SearchFilter(SearchScope.Everywhere, SearchCategory.Images, ModifiedRange.Month)
        assertTrue(f.matches(img, now)); assertFalse(f.matches(oldImg, now)); assertFalse(f.matches(dir, now))
        assertTrue(SearchFilter().matches(dir, now))
        assertFalse(f.isDefault); assertTrue(SearchFilter().isDefault)
    }

    @Test fun marksRekeyOnlyExactPathAndDescendants() {
        val m = Marks().toggleStar(listOf("/a/b", "/a/bc", "/a/b/x")).toggleTag(listOf("/a/b/x"), ColorTag.Urgent)
        val r = m.renamed("/a/b", "/z/b2")
        assertEquals(setOf("/z/b2", "/a/bc", "/z/b2/x"), r.starred)
        assertEquals(setOf(ColorTag.Urgent), r.tagsOf("/z/b2/x"))
        assertTrue(r.tagsOf("/a/b/x").isEmpty())
        assertEquals(m, m.renamed("/nope", "/x"))
    }

    @Test fun sortFoldersFirstAndBySize() {
        val es = listOf(
            FileEntry("/d/b.txt", "b.txt", false, size = 5), FileEntry("/d/Z", "Z", true),
            FileEntry("/d/a10.txt", "a10.txt", false, size = 50), FileEntry("/d/a2.txt", "a2.txt", false, size = 1),
        )
        assertEquals(listOf("Z", "a2.txt", "a10.txt", "b.txt"), sortEntries(es, SortKey.Name, true).map { it.name })
        val bySizeDesc = sortEntries(es, SortKey.Size, false).map { it.name }
        assertEquals("Z", bySizeDesc.first())
        assertEquals(listOf("a10.txt", "b.txt", "a2.txt"), bySizeDesc.drop(1))
        assertNotEquals(sortEntries(es, SortKey.Name, true), sortEntries(es, SortKey.Name, false))
    }

    @Test fun listingTenThousandFilesIsFast() {
        val big = d("big")
        repeat(10_000) { File(big, "file_$it.dat").createNewFile() }
        val t0 = System.nanoTime()
        val list = repo.list(big.path)
        val sorted = sortEntries(list, SortKey.Name, true)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(10_000, sorted.size)
        assertEquals("file_0.dat", sorted.first().name)
        assertEquals("file_9999.dat", sorted.last().name)
        assertTrue("list+sort of 10k took $ms ms", ms < 5_000)
    }
}
