// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import com.droid.wayfinder.data.ConnectConfig
import com.droid.wayfinder.data.FsPath
import com.droid.wayfinder.data.FtpMapping
import com.droid.wayfinder.data.MimeFilter
import com.droid.wayfinder.data.Protocol
import com.droid.wayfinder.data.RemoteUri
import com.droid.wayfinder.data.SafLabels
import com.droid.wayfinder.data.SafMount
import com.droid.wayfinder.data.ServerAddress
import com.droid.wayfinder.data.WebDavXml
import org.apache.commons.net.ftp.parser.UnixFTPEntryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.IOException

class PackageCTest {

    // ---------------------------------------------------------------- address parsing

    @Test fun parsesSmbUrl() {
        val a = RemoteUri.parse("smb://nas/media/Movies")!!
        assertEquals(Protocol.SMB, a.protocol)
        assertEquals("nas", a.host)
        assertEquals(445, a.port)
        assertEquals("media/Movies", a.path)
        assertEquals("smb://nas/media/Movies", a.rootPath)
        assertEquals("nas", a.credKey)
    }

    @Test fun parsesUncPath() {
        val a = RemoteUri.parse("\\\\192.168.1.20\\photos\\2024", Protocol.FTP)!!
        assertEquals(Protocol.SMB, a.protocol)
        assertEquals("192.168.1.20", a.host)
        assertEquals("photos/2024", a.path)
    }

    @Test fun parsesWebDavAndHttpSchemes() {
        val a = RemoteUri.parse("davs://cloud.example.com/remote.php/dav/files/me")!!
        assertEquals(Protocol.DAVS, a.protocol)
        assertEquals(443, a.port)
        assertEquals("davs://cloud.example.com/remote.php/dav/files/me", a.rootPath)
        assertEquals("davs://cloud.example.com", a.credKey)
        val h = RemoteUri.parse("http://nas:8080/webdav")!!
        assertEquals(Protocol.DAV, h.protocol)
        assertEquals(8080, h.port)
        assertEquals("dav://nas:8080/webdav", h.rootPath)
        assertEquals("dav://nas:8080", h.credKey)
        assertEquals(Protocol.DAVS, RemoteUri.parse("https://x/y")!!.protocol)
    }

    @Test fun parsesFtpWithUserPortAndPassword() {
        val a = RemoteUri.parse("ftp://alice@files.local:2121/pub/iso")!!
        assertEquals(Protocol.FTP, a.protocol)
        assertEquals("alice", a.user)
        assertNull(a.password)
        assertEquals("files.local", a.host)
        assertEquals(2121, a.port)
        assertEquals("pub/iso", a.path)
        assertEquals("ftp://files.local:2121/pub/iso", a.rootPath)
        val b = RemoteUri.parse("ftps://bob:p%40ss%20w@host")!!
        assertEquals(Protocol.FTPS, b.protocol)
        assertEquals("bob", b.user)
        assertEquals("p@ss w", b.password)
        assertEquals(21, b.port)
        assertEquals("ftps://host", b.rootPath)
    }

    @Test fun bareHostUsesDefaultProtocolAndRejectsJunk() {
        val a = RemoteUri.parse("nas/share", Protocol.DAV)!!
        assertEquals(Protocol.DAV, a.protocol)
        assertEquals("share", a.path)
        assertNull(RemoteUri.parse(""))
        assertNull(RemoteUri.parse("gopher://x"))
        assertNull(RemoteUri.parse("smb://"))
    }

    @Test fun decodesPercentEscapesInPath() {
        assertEquals("My Files/a+b", RemoteUri.parse("dav://h/My%20Files/a+b")!!.path)
    }

    @Test fun ipv6Authority() {
        val (h, p) = RemoteUri.splitAuthority("[fe80::1]:2121", 21)
        assertEquals("[fe80::1]", h)
        assertEquals(2121, p)
        assertEquals("fe80::1" to 21, RemoteUri.splitAuthority("fe80::1", 21)) // bare v6 without brackets: no port split
    }

    @Test fun remotePathHelpers() {
        assertEquals("ftp", RemoteUri.schemeOf("ftp://h/x"))
        assertNull(RemoteUri.schemeOf("/storage/emulated/0"))
        assertNull(RemoteUri.schemeOf("trash://"))
        assertEquals("dav://h:81", RemoteUri.credKeyOf("dav://h:81/a/b"))
        assertEquals("nas", RemoteUri.credKeyOf("smb://nas/share/x"))
        assertEquals("a/b", RemoteUri.relOf("davs://h/a/b"))
        assertEquals("", RemoteUri.relOf("davs://h"))
        assertEquals("saf://ab12", RemoteUri.rootOf("saf://ab12/x/y"))
    }

    @Test fun connectConfigBuildsAddressAndGuestCreds() {
        val c = ConnectConfig(Protocol.SMB, " nas ", 445, "\\media\\tv\\", user = "me", password = "pw", domain = "WG")
        assertEquals("smb://nas/media/tv", c.address.rootPath)
        assertEquals("WG", c.cred.domain)
        val g = c.copy(guest = true)
        assertEquals("", g.cred.user)
        val f = ConnectConfig(Protocol.FTP, "h", 21, user = "u", domain = "ignored")
        assertEquals("", f.cred.domain)
        assertEquals("ftp://h", ServerAddress(Protocol.FTP, "h", 0).rootPath)
    }

    // ---------------------------------------------------------------- FsPath with new schemes

    @Test fun fsPathHandlesDavFtpSaf() {
        assertTrue(FsPath.isRemote("davs://h/a"))
        assertTrue(FsPath.isRemote("saf://id/a"))
        assertFalse(FsPath.isRemote("/sdcard"))
        assertFalse(FsPath.isRemote("recents://"))
        assertEquals("ftp://h/a/b", FsPath.normalize("ftp://h//a/b/"))
        assertEquals("ftp://h/a", FsPath.parent("ftp://h/a/b"))
        assertEquals(FsPath.SMB_ROOT, FsPath.parent("davs://h"))
        assertEquals(FsPath.SMB_ROOT, FsPath.parent("saf://abc"))
        assertEquals("b", FsPath.name("dav://h/a/b"))
        assertTrue(FsPath.isWithin("dav://h/base", "dav://h/base/x"))
        assertFalse(FsPath.isWithin("dav://h/base", "dav://h/basement"))
    }

    @Test fun breadcrumbsUseMountLabels() {
        val crumbs = FsPath.breadcrumbs("davs://cloud/remote.php/dav/Photos", mapOf("davs://cloud/remote.php/dav" to "My Cloud"))
        assertEquals(listOf("Network", "My Cloud", "Photos"), crumbs.map { it.first })
        assertEquals("davs://cloud/remote.php/dav/Photos", crumbs.last().second)
        val ftp = FsPath.breadcrumbs("ftp://host:2121/pub", emptyMap())
        assertEquals(listOf("Network", "host:2121", "pub"), ftp.map { it.first })
        val saf = FsPath.breadcrumbs("saf://x1/Docs", mapOf("saf://x1" to "Google Drive"))
        assertEquals(listOf("Network", "Google Drive", "Docs"), saf.map { it.first })
        // SMB without a saved label keeps the host/share crumbs.
        assertEquals(listOf("Network", "nas", "media"), FsPath.breadcrumbs("smb://nas/media", emptyMap()).map { it.first })
    }

    // ---------------------------------------------------------------- WebDAV multistatus

    private val multistatus = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:response>
    <d:href>/remote.php/dav/files/me/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Tue, 01 Oct 2024 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/me/My%20Photos/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
    <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>https://cloud.example.com/remote.php/dav/files/me/report%2Bfinal.pdf</d:href>
    <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12345</d:getcontentlength><d:getlastmodified>Wed, 02 Oct 2024 12:30:00 GMT</d:getlastmodified><d:displayname>report+final.pdf</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
</d:multistatus>"""

    @Test fun parsesMultistatus() {
        val r = WebDavXml.parseMultistatus(multistatus, KXmlParser())
        assertEquals(3, r.size)
        assertEquals("/remote.php/dav/files/me", r[0].path)
        assertTrue(r[0].isDir)
        assertEquals("My Photos", r[1].name)
        assertTrue(r[1].isDir)
        assertEquals(0L, r[1].size)
        val f = r[2]
        assertEquals("/remote.php/dav/files/me/report+final.pdf", f.path)
        assertFalse(f.isDir)
        assertEquals(12345L, f.size)
        assertEquals(1727872200000L, f.modified)
        assertEquals("report+final.pdf", f.displayName)
    }

    @Test fun multistatusWithDefaultNamespaceAndUppercasePrefix() {
        val xml = """<multistatus xmlns="DAV:"><response><href>/a/b.txt</href><propstat><prop><getcontentlength>7</getcontentlength><resourcetype/></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""
        val r = WebDavXml.parseMultistatus(xml, KXmlParser())
        assertEquals(1, r.size)
        assertEquals("b.txt", r[0].name)
        assertEquals(7L, r[0].size)
    }

    @Test(expected = IOException::class)
    fun multistatusRejectsDtd() {
        WebDavXml.parseMultistatus("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"/>""", KXmlParser())
    }

    @Test fun hrefDecoding() {
        assertEquals("/", WebDavXml.hrefToPath("/"))
        assertEquals("/a b/c", WebDavXml.hrefToPath("http://h:80/a%20b/c/"))
        assertEquals("/x+y", WebDavXml.hrefToPath("/x+y"))
    }

    // ---------------------------------------------------------------- FTP listing mapping

    @Test fun mapsUnixFtpListing() {
        val p = UnixFTPEntryParser()
        val dir = p.parseFTPEntry("drwxr-xr-x    2 ftp      ftp          4096 Jan 01  2020 docs")
        val file = p.parseFTPEntry("-rw-r--r--    1 ftp      ftp        123456 Mar 15  2021 movie night.mkv")
        val e1 = FtpMapping.entry("ftp://h/pub/", dir)!!
        assertEquals("ftp://h/pub/docs", e1.path)
        assertTrue(e1.isDir)
        assertEquals(0L, e1.size)
        val e2 = FtpMapping.entry("ftp://h/pub", file)!!
        assertEquals("movie night.mkv", e2.name)
        assertEquals(123456L, e2.size)
        assertFalse(e2.isDir)
        assertNull(FtpMapping.entry("ftp://h", p.parseFTPEntry("drwxr-xr-x 2 u g 4096 Jan 01 2020 .")))
        assertEquals("/pub/x", FtpMapping.serverPath("ftps://h:990/pub/x"))
        assertEquals("/", FtpMapping.serverPath("ftp://h"))
    }

    // ---------------------------------------------------------------- picker & SAF helpers

    @Test fun mimeFilter() {
        assertTrue(MimeFilter.matches("image/png", emptyList()))
        assertTrue(MimeFilter.matches(null, listOf("*/*")))
        assertTrue(MimeFilter.matches("image/png", listOf("image/*")))
        assertFalse(MimeFilter.matches("video/mp4", listOf("image/*")))
        assertTrue(MimeFilter.matches("application/pdf", listOf("text/plain", "application/pdf")))
        assertFalse(MimeFilter.matches(null, listOf("image/*")))
    }

    @Test fun safLabelsAndSerialization() {
        assertEquals("Google Drive", SafLabels.providerLabel("com.google.android.apps.docs.storage", "acc=1;doc=root"))
        assertEquals("Internal storage", SafLabels.providerLabel("com.android.externalstorage.documents", "primary:Download"))
        assertEquals("USB / SD card", SafLabels.providerLabel("com.android.externalstorage.documents", "1234-ABCD:"))
        assertEquals("Downloads", SafLabels.providerLabel("com.android.providers.downloads.documents", "downloads"))
        val list = listOf(SafMount("a1", "Google Drive · Work", "content://x/tree/1"), SafMount("b2", "USB", "content://y/tree/2"))
        assertEquals(list, SafMount.parse(SafMount.serialize(list)))
        assertEquals("saf://a1", list[0].path)
        assertTrue(SafMount.parse(null).isEmpty())
    }
}
