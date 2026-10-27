// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import com.droid.wayfinder.data.FileInfo
import com.droid.wayfinder.data.FileOperations
import com.droid.wayfinder.data.FileRepository
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.LocalFileSystem
import com.droid.wayfinder.data.TrashManager
import com.droid.wayfinder.data.WebDavXml
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Regression tests for the local file-system findings of the security review: symbolic links are never
 * followed by delete / copy / measure / compress / trash, the Trash only ever deletes inside itself and
 * distrusts its `.origin` records, and WebDAV responses can't smuggle DTDs or traversal names.
 */
class FileSystemSecurityTest {
    private lateinit var sandbox: File
    private lateinit var victim: File
    private lateinit var work: File
    private val local = LocalFileSystem()
    private val ops = FileOperations(FileRepository(local, local, null))

    @Before fun setUp() {
        sandbox = Files.createTempDirectory("wf-sec").toFile().canonicalFile
        victim = File(sandbox, "victim").apply { mkdirs(); File(this, "precious.txt").writeText("do not delete") }
        work = File(sandbox, "work").apply { mkdirs() }
    }

    @After fun tearDown() { FileInfo.deleteTree(sandbox) }

    private fun link(at: File, to: File) = Files.createSymbolicLink(at.toPath(), to.toPath()).toFile()

    private fun assertVictimIntact() {
        assertEquals("do not delete", File(victim, "precious.txt").readText())
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun deleteDoesNotFollowLinkedFolders() {
        val dir = File(work, "dir").apply { mkdirs(); File(this, "own.txt").writeText("x") }
        link(File(dir, "link"), victim)
        local.delete(dir.path)
        assertFalse(dir.exists())
        assertVictimIntact()
    }

    @Test
    fun deletingALinkRemovesOnlyTheLink() {
        val l = link(File(work, "l"), victim)
        local.delete(l.path)
        assertFalse(FileInfo.isSymlink(l.path))
        assertVictimIntact()
        // Dangling links are visible and deletable.
        val dangling = link(File(work, "dangling"), File(sandbox, "missing"))
        assertTrue(local.exists(dangling.path))
        assertTrue(local.stat(dangling.path)!!.isLink)
        local.delete(dangling.path)
        assertFalse(local.exists(dangling.path))
    }

    @Test
    fun deleteTreeHandlesLinkLoops() {
        val a = File(work, "a").apply { mkdirs() }
        link(File(a, "loop"), a)
        link(File(a, "up"), work)
        assertTrue(FileInfo.deleteTree(a))
        assertTrue(work.isDirectory)
        assertVictimIntact()
    }

    // ------------------------------------------------------------------ copy / measure / compress

    @Test
    fun copyAndMeasureTerminateOnLinkLoops() = runBlocking {
        val src = File(work, "src").apply { mkdirs(); File(this, "f.txt").writeText("data") }
        link(File(src, "self"), src)
        link(File(src, "victim"), victim)
        val (bytes, _) = ops.measure(listOf(src.path))
        assertEquals("linked folders are not measured", 4L, bytes)
        val out = File(sandbox, "out").apply { mkdirs() }
        ops.transfer(listOf(src.path), out.path, move = false, conflict = com.droid.wayfinder.data.Conflict.KeepBoth) {}
        assertEquals("data", File(out, "src/f.txt").readText())
        assertFalse("nested linked folder not copied", File(out, "src/victim/precious.txt").exists())
        // Moving (= copy + delete across file systems here is a rename, so force the copy path via duplicate).
        ops.duplicate(listOf(src.path)) {}
        assertVictimIntact()
    }

    @Test
    fun compressSkipsItsOwnOutputAndNestedLinks() = runBlocking {
        val dir = File(work, "dir").apply { mkdirs(); File(this, "a.txt").writeText("a") }
        link(File(dir, "victim"), victim)
        val zip = File(dir, "dir.zip")
        ops.compress(listOf(dir.path), zip.path) {}
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.contains("dir/a.txt"))
        assertFalse("archive must not contain itself: $names", names.any { it.endsWith("dir.zip") })
        assertFalse("linked folder followed: $names", names.any { it.contains("precious") })
    }

    @Test
    fun uniqueNameIsBounded() {
        // A file system claiming every name exists (hostile server) must not hang the app.
        val always = object : com.droid.wayfinder.data.FileSystem by local {
            override fun exists(path: String) = true
        }
        val o = FileOperations(FileRepository(always, always, null))
        try { o.uniqueName("/x", "a.txt"); fail("must give up") } catch (_: IOException) {}
    }

    // ------------------------------------------------------------------ trash

    private fun trash() = TrashManager(File(sandbox, ".Trash"))

    @Test
    fun trashRoundTripDoesNotFollowLinks() {
        val dir = File(work, "dir").apply { mkdirs(); File(this, "a.txt").writeText("a") }
        link(File(dir, "victim"), victim)
        val t = trash()
        val trashed = t.moveToTrash(listOf(dir.path)).single()
        assertFalse(dir.exists())
        assertVictimIntact()
        assertEquals(dir.path, t.originalOf(trashed.trashed))
        val restored = t.restore(trashed.trashed)
        assertEquals("a", File(restored, "a.txt").readText())
        t.moveToTrash(listOf(restored))
        t.empty()
        assertVictimIntact()
    }

    @Test
    fun trashSlotsAreUnique() {
        val t = trash()
        val files = (1..20).map { File(work, "same.txt").let { _ -> File(work, "d$it").apply { mkdirs() }.let { d -> File(d, "same.txt").apply { writeText("$it") } } } }
        val out = t.moveToTrash(files.map { it.path })
        assertEquals(20, out.map { it.trashed }.distinct().size)
        assertEquals(20, t.list().size)
    }

    @Test
    fun deletePermanentlyIsConfinedToTheBin() {
        val t = trash()
        val f = File(work, "f.txt").apply { writeText("x") }
        val trashed = t.moveToTrash(listOf(f.path)).single()
        for (p in listOf(victim.path, File(victim, "precious.txt").path, "${trashed.trashed}/../../../victim",
            File(sandbox, ".Trash").path, sandbox.path, "/")) {
            try { t.deletePermanently(p); fail("deleted $p") } catch (_: IOException) {}
        }
        assertVictimIntact()
        t.deletePermanently(trashed.trashed)
        assertEquals(0, t.list().size)
    }

    @Test
    fun tamperedOriginIsIgnored() {
        val t = trash()
        val f = File(work, "f.txt").apply { writeText("x") }
        val trashed = t.moveToTrash(listOf(f.path)).single()
        val origin = File(File(trashed.trashed).parentFile, ".origin")
        for (bad in listOf("relative/path", "/a/../../etc/x", "/x\u0000y", "/a\n/b", "${File(sandbox, ".Trash").path}/x", "/a.zip!/x", "")) {
            origin.writeText(bad)
            assertNull("accepted '$bad'", t.originalOf(trashed.trashed))
            try { t.restore(trashed.trashed); fail("restored to '$bad'") } catch (_: IOException) {}
        }
        assertEquals(null, TrashManager.validOrigin("/storage/../data/data/x", "/t"))
        assertEquals("/storage/emulated/0/a", TrashManager.validOrigin("//storage//emulated/0/a/", "/t"))
    }

    // ------------------------------------------------------------------ WebDAV XML

    @Test
    fun webDavRejectsDtdsAndBombs() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///data/data/com.droid.wayfinder/shared_prefs/smb_servers.xml">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&x;</d:href></d:response></d:multistatus>"""
        val laughs = "<?xml version=\"1.0\"?><!DOCTYPE l [<!ENTITY a \"aaaaaaaaaa\">" +
            (1..9).joinToString("") { "<!ENTITY ${'a' + it} \"" + "&${'a' + it - 1};".repeat(10) + "\">" } +
            "]><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&j;</d:href></d:response></d:multistatus>"
        for (doc in listOf(xxe, laughs, xxe.replace("DOCTYPE", "doctype"))) {
            try { WebDavXml.parseMultistatus(doc, KXmlParser()); fail("DTD accepted") } catch (_: IOException) {}
        }
        try { WebDavXml.parseMultistatus("<a>" + " ".repeat((WebDavXml.MAX_RESPONSE_BYTES + 1).toInt()), KXmlParser()); fail("size") } catch (_: IOException) {}
        try { WebDavXml.parseMultistatus("<d:multistatus xmlns:d=\"DAV:\"><d:response>", KXmlParser()) } catch (_: IOException) {}
    }

    @Test
    fun webDavTraversalHrefsYieldUnsafeNames() {
        val doc = """<d:multistatus xmlns:d="DAV:">""" +
            listOf("/dav/a/..", "/dav/a/%2e%2e", "/dav/a/%2E%2E/", "/dav/..%2F..%2Fetc", "/dav/x%00y", "/dav/ok.txt", "/dav/%2F").joinToString("") {
                "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:getcontentlength>-5</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            } + "</d:multistatus>"
        val res = WebDavXml.parseMultistatus(doc, KXmlParser())
        val safe = res.filter { FsPath.isSafeName(it.name) && !it.name.contains('\\') }.map { it.name }
        assertTrue("only harmless names survive: $safe", safe.all { it == "ok.txt" || it == "a" || it == "etc" || it == "dav" })
        assertTrue(res.all { it.size >= 0 })
    }

    @Test
    fun mutatedWebDavDocumentsOnlyFailWithIOException() {
        val base = """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/dav/a%20b.txt</d:href><d:propstat><d:prop><d:getcontentlength>12</d:getcontentlength><d:getlastmodified>Mon, 12 Jan 2026 10:00:00 GMT</d:getlastmodified><d:displayname>a b</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:quota/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response></d:multistatus>"""
        FuzzKit.run(
            "WebDavXml.parseMultistatus", 20_000, { r -> if (r.nextInt(4) == 0) FuzzKit.mutate(r, FuzzKit.mutate(r, base)) else FuzzKit.mutate(r, base) },
            listOf(base) + FuzzKit.corpus, allowed = { it is IOException },
        ) { doc ->
            val res = WebDavXml.parseMultistatus(doc, KXmlParser())
            assertTrue(res.size <= WebDavXml.MAX_RESOURCES)
            res.forEach { assertTrue(it.size >= 0); assertTrue(it.path.startsWith("/")); WebDavXml.hrefToPath(it.path) }
        }
    }
}
