// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.droid.wayfinder.ui.Mount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafAndDocumentsProviderCoverageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    class InMemorySafProvider : ContentProvider() {
        data class Node(
            val id: String,
            var parentId: String?,
            var name: String,
            val mime: String,
            val backingFile: File,
        )

        val nodes = LinkedHashMap<String, Node>()
        var throwSecurityOnQuery = false
        var throwFileNotFoundOnQuery = false

        fun seed(rootDir: File) {
            nodes.clear()
            val rootFile = File(rootDir, "root").apply { mkdirs() }
            nodes["primary:Work"] = Node("primary:Work", null, "Work", Document.MIME_TYPE_DIR, rootFile)
            val subDir = File(rootFile, "Specs").apply { mkdirs() }
            nodes["primary:Work/Specs"] = Node("primary:Work/Specs", "primary:Work", "Specs", Document.MIME_TYPE_DIR, subDir)
            val noteFile = File(rootFile, "hello.txt").apply { writeText("initial content") }
            nodes["primary:Work/hello.txt"] = Node("primary:Work/hello.txt", "primary:Work", "hello.txt", "text/plain", noteFile)
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            if (throwSecurityOnQuery) throw SecurityException("Revoked")
            if (throwFileNotFoundOnQuery) throw FileNotFoundException("Missing")

            val pathSegs = uri.pathSegments
            if (pathSegs.contains("root") && !pathSegs.contains("document")) {
                val cursor = MatrixCursor(
                    arrayOf(
                        Root.COLUMN_ROOT_ID,
                        Root.COLUMN_TITLE,
                        Root.COLUMN_DOCUMENT_ID,
                        Root.COLUMN_FLAGS,
                        Root.COLUMN_SUMMARY,
                    ),
                )
                cursor.addRow(arrayOf<Any?>("root-1", "Pixel 9", "primary:Work", 0, "Internal Storage"))
                return cursor
            }

            val cursor = MatrixCursor(
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE,
                    Document.COLUMN_LAST_MODIFIED,
                ),
            )
            val isChildren = pathSegs.lastOrNull() == "children"
            if (isChildren) {
                val parentId = DocumentsContract.getDocumentId(uri)
                for (n in nodes.values) {
                    if (n.parentId == parentId) {
                        cursor.addRow(
                            arrayOf<Any?>(
                                n.id,
                                n.name,
                                n.mime,
                                if (n.mime == Document.MIME_TYPE_DIR) 0L else n.backingFile.length(),
                                n.backingFile.lastModified().coerceAtLeast(1700000000000L),
                            ),
                        )
                    }
                }
            } else {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                val n = nodes[docId]
                if (n != null) {
                    cursor.addRow(
                        arrayOf<Any?>(
                            n.id,
                            n.name,
                            n.mime,
                            if (n.mime == Document.MIME_TYPE_DIR) 0L else n.backingFile.length(),
                            n.backingFile.lastModified().coerceAtLeast(1700000000000L),
                        ),
                    )
                }
            }
            return cursor
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            @Suppress("DEPRECATION")
            val docUri = extras?.getParcelable<Uri>("uri") ?: return null
            val docId = DocumentsContract.getDocumentId(docUri)
            val auth = docUri.authority ?: "com.example.saf"
            return when (method) {
                "android:createDocument" -> {
                    val mime = extras.getString(Document.COLUMN_MIME_TYPE).orEmpty()
                    val name = extras.getString(Document.COLUMN_DISPLAY_NAME).orEmpty()
                    val parent = nodes[docId] ?: return null
                    val newId = "$docId/$name"
                    val f = File(parent.backingFile, name)
                    if (mime == Document.MIME_TYPE_DIR) f.mkdirs() else { f.parentFile?.mkdirs(); f.createNewFile() }
                    nodes[newId] = Node(newId, docId, name, mime, f)
                    Bundle().apply {
                        val outUri = if (docUri.pathSegments.contains("tree")) {
                            DocumentsContract.buildDocumentUriUsingTree(docUri, newId)
                        } else {
                            DocumentsContract.buildDocumentUri(auth, newId)
                        }
                        putParcelable("uri", outUri)
                    }
                }
                "android:deleteDocument" -> {
                    val removed = nodes.remove(docId)
                    removed?.backingFile?.deleteRecursively()
                    Bundle().apply { putBoolean("result", removed != null) }
                }
                "android:renameDocument" -> {
                    val newName = extras.getString(Document.COLUMN_DISPLAY_NAME).orEmpty()
                    val existing = nodes.remove(docId) ?: return null
                    val parentId = existing.parentId
                    val newId = if (parentId != null) "$parentId/$newName" else newName
                    val newFile = File(existing.backingFile.parentFile, newName)
                    existing.backingFile.renameTo(newFile)
                    nodes[newId] = Node(newId, parentId, newName, existing.mime, newFile)
                    Bundle().apply {
                        val outUri = if (docUri.pathSegments.contains("tree")) {
                            DocumentsContract.buildDocumentUriUsingTree(docUri, newId)
                        } else {
                            DocumentsContract.buildDocumentUri(auth, newId)
                        }
                        putParcelable("uri", outUri)
                    }
                }
                "android:moveDocument" -> {
                    @Suppress("DEPRECATION")
                    val targetParentUri = extras.getParcelable<Uri>("android.content.extra.TARGET_URI")
                        ?: extras.getParcelable<Uri>("uriTarget")
                        ?: return null
                    val targetParentId = DocumentsContract.getDocumentId(targetParentUri)
                    val existing = nodes.remove(docId) ?: return null
                    val newId = "$targetParentId/${existing.name}"
                    val targetDir = nodes[targetParentId]?.backingFile ?: existing.backingFile.parentFile
                    val newFile = File(targetDir, existing.name)
                    existing.backingFile.renameTo(newFile)
                    nodes[newId] = Node(newId, targetParentId, existing.name, existing.mime, newFile)
                    Bundle().apply {
                        putParcelable(
                            "uri",
                            DocumentsContract.buildDocumentUriUsingTree(docUri, newId),
                        )
                    }
                }
                else -> null
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val docId = DocumentsContract.getDocumentId(uri)
            val node = nodes[docId] ?: throw FileNotFoundException(docId)
            val pfdMode = if (mode.startsWith("w")) {
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            return ParcelFileDescriptor.open(node.backingFile, pfdMode)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Test
    fun safMountSerializationAndSafLabelsCoverage() {
        val mounts = listOf(
            SafMount("m1", "My\tLabel\n1", "content://auth/tree/1"),
            SafMount("m2", "Second", "content://auth/tree/2"),
        )
        val raw = SafMount.serialize(mounts)
        val parsed = SafMount.parse(raw)
        assertEquals(2, parsed.size)
        assertEquals("My Label 1", parsed[0].label)
        assertEquals("saf://m1", parsed[0].path)

        assertEquals("Google Drive", SafLabels.providerLabel("com.google.android.apps.docs.storage", "root"))
        assertEquals("Internal storage", SafLabels.providerLabel("com.android.externalstorage.documents", "primary:Work"))
        assertEquals("Documents", SafLabels.providerLabel("com.android.externalstorage.documents", "home:Docs"))
        assertEquals("USB / SD card", SafLabels.providerLabel("com.android.externalstorage.documents", "1234-5678:"))
        assertEquals("Downloads", SafLabels.providerLabel("com.android.providers.downloads.documents", "downloads"))
        assertEquals("Media", SafLabels.providerLabel("com.android.providers.media.documents", "image_root"))
        assertEquals("Phone", SafLabels.providerLabel("com.android.mtp.documents", "1"))
        assertEquals("OneDrive", SafLabels.providerLabel("com.microsoft.skydrive.content", "1"))
        assertEquals("Dropbox", SafLabels.providerLabel("com.dropbox.product.android.dbapp.document_provider", "1"))
        assertEquals("Nextcloud", SafLabels.providerLabel("org.nextcloud.documents", "1"))
        assertEquals("SMB share", SafLabels.providerLabel("com.google.android.sambadocumentsprovider", "1"))
        assertEquals("Acme", SafLabels.providerLabel("com.acme.documents", "1"))

        assertEquals("a_b_c", safeDisplayName("a/b\u0000c"))
        assertNull(safeDisplayName(".."))
    }

    @Test
    fun safFileSystemAndDocFileSystemFullLifecycle() {
        val authority = "com.example.saf.documents"
        val provider = Robolectric.setupContentProvider(InMemorySafProvider::class.java, authority)
        provider.seed(tempFolder.newFolder("saf_backing"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Work")
        val mount = SafMount("work", "Work Folder", treeUri.toString())
        val saf = SafFileSystem(context) { listOf(mount) }

        // stat root and child
        val rootStat = saf.stat("saf://work")
        assertNotNull(rootStat)
        assertTrue(rootStat!!.isServer)
        assertEquals("Work Folder", rootStat.name)

        // list
        val kids = saf.list("saf://work")
        assertEquals(2, kids.size)
        val fileStat = saf.stat("saf://work/hello.txt")
        assertNotNull(fileStat)
        assertEquals(15L, fileStat!!.size)

        // mkdir, createFile, openOutput, openInput, rename, move, delete
        saf.mkdir("saf://work/CreatedDir")
        assertTrue(saf.stat("saf://work/CreatedDir")!!.isDir)

        saf.createFile("saf://work/CreatedDir/note.txt")
        saf.openOutput("saf://work/CreatedDir/note.txt").use {
            it.write("saf payload".toByteArray())
        }
        val readText = saf.openInput("saf://work/CreatedDir/note.txt").use { it.readBytes().decodeToString() }
        assertEquals("saf payload", readText)

        val renamed = saf.rename("saf://work/CreatedDir/note.txt", "renamed.txt")
        assertEquals("saf://work/CreatedDir/renamed.txt", renamed)

        assertTrue(saf.move("saf://work/CreatedDir/renamed.txt", "saf://work/Specs/renamed.txt"))
        saf.delete("saf://work/Specs/renamed.txt")
        assertNull(saf.stat("saf://work/Specs/renamed.txt"))

        // guard error translation
        provider.throwSecurityOnQuery = true
        saf.disconnect("work")
        try {
            saf.list("saf://work")
            org.junit.Assert.fail("Expected SecurityException to translate into IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("permission to this location was revoked"))
        }
        provider.throwSecurityOnQuery = false

        provider.throwFileNotFoundOnQuery = true
        try {
            saf.list("saf://work")
            org.junit.Assert.fail("Expected FileNotFoundException to translate into IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not found"))
        }
        provider.throwFileNotFoundOnQuery = false

        // Also exercise SystemDocumentsBridge and DocFileSystem against the same provider
        val pm = shadowOf(context.packageManager)
        val resolveInfo = ResolveInfo().apply {
            providerInfo = ProviderInfo().apply {
                this.authority = authority
                packageName = context.packageName
                name = InMemorySafProvider::class.java.name
            }
        }
        pm.addResolveInfoForIntent(Intent("android.content.action.DOCUMENTS_PROVIDER"), resolveInfo)

        val discovered = SystemDocumentsBridge.discoverRoots(context)
        assertTrue(discovered.any { it.authority == authority && it.title == "Pixel 9" })

        val docFs = DocFileSystem(context)
        val docEntries = docFs.list("doc://$authority/primary:Work")
        assertTrue(docEntries.any { it.name == "hello.txt" })

        val docStat = docFs.stat("doc://$authority/primary:Work/hello.txt")
        assertNotNull(docStat)
        assertEquals("hello.txt", docStat!!.name)

        docFs.openOutput("doc://$authority/primary:Work/hello.txt").use {
            it.write("doc updated".toByteArray())
        }
        val docRead = docFs.openInput("doc://$authority/primary:Work/hello.txt").use { it.readBytes().decodeToString() }
        assertEquals("doc updated", docRead)

        docFs.mkdir("doc://$authority/primary:Work/DocSub")
        docFs.createFile("doc://$authority/primary:Work/DocSub/item.bin")
        val docRenamed = docFs.rename("doc://$authority/primary:Work/DocSub/item.bin", "item2.bin")
        assertTrue(docRenamed.endsWith("item2.bin"))
        docFs.delete(docRenamed)
        docFs.disconnect(authority)
    }

    @Test
    fun phoneDetectorAndPhoneFileSystemLifecycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // PhoneDetector mount detection, Bluetooth phone class check, and paired phone resolution
        assertTrue(PhoneDetector.isPhoneMount("Pixel 9 Pro"))
        assertTrue(PhoneDetector.isPhoneMount("Samsung Galaxy S25"))
        assertFalse(PhoneDetector.isPhoneMount("Downloads"))
        assertTrue(PhoneDetector.isPhoneConnected(context, listOf("mtp://pixel")))

        val pairedFromMount = PhoneDetector.detectPairedPhone(
            context,
            listOf(Mount(path = "/storage/pixel9", label = "Pixel 9 Pro", subtitle = "Phone", key = "p9", saf = true)),
        )
        assertNotNull(pairedFromMount)
        assertEquals("Pixel 9 Pro", pairedFromMount!!.name)

        // Private isPhoneBluetoothDevice via reflection
        val btMethod = PhoneDetector::class.java.getDeclaredMethod("isPhoneBluetoothDevice", BluetoothClass::class.java).apply {
            isAccessible = true
        }
        assertFalse(btMethod.invoke(PhoneDetector, null) as Boolean)

        // PhoneFileSystem with rootOverride
        val phoneRoot = tempFolder.newFolder("phone_root")
        File(phoneRoot, "Download/Quick Share").mkdirs()
        val phoneFs = PhoneFileSystem(context, rootOverride = phoneRoot)

        val rootEntries = phoneFs.list(FsPath.PHONE_ROOT)
        assertEquals(phoneFs.standardFolders.size, rootEntries.size)

        val rootStat = phoneFs.stat(FsPath.PHONE_ROOT)
        assertNotNull(rootStat)
        assertTrue(rootStat!!.name in setOf("Pixel 9", "Connected Phone"))

        val dlStat = phoneFs.stat("${FsPath.PHONE_ROOT}Download")
        assertNotNull(dlStat)
        assertTrue(dlStat!!.isDir)

        phoneFs.mkdir("${FsPath.PHONE_ROOT}Download/SubFolder")
        phoneFs.createFile("${FsPath.PHONE_ROOT}Download/SubFolder/photo.jpg")
        phoneFs.openOutput("${FsPath.PHONE_ROOT}Download/SubFolder/photo.jpg").use {
            it.write("jpeg-bytes".toByteArray())
        }
        val readBytes = phoneFs.openInput("${FsPath.PHONE_ROOT}Download/SubFolder/photo.jpg").use { it.readBytes().decodeToString() }
        assertEquals("jpeg-bytes", readBytes)

        val subList = phoneFs.list("${FsPath.PHONE_ROOT}Download/SubFolder")
        assertEquals(1, subList.size)
        assertEquals("photo.jpg", subList[0].name)

        val renamed = phoneFs.rename("${FsPath.PHONE_ROOT}Download/SubFolder/photo.jpg", "vacation.jpg")
        assertEquals("${FsPath.PHONE_ROOT}Download/SubFolder/vacation.jpg", renamed)

        phoneFs.delete(renamed)
        assertNull(phoneFs.stat(renamed))

        // System top-level phone folders cannot be deleted or renamed
        try {
            phoneFs.delete("${FsPath.PHONE_ROOT}Download")
            org.junit.Assert.fail("Expected deleting top-level system folder to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Cannot delete system phone folder"))
        }

        try {
            phoneFs.rename("${FsPath.PHONE_ROOT}Download", "MyDownloads")
            org.junit.Assert.fail("Expected renaming top-level system folder to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Cannot rename system phone folder"))
        }
    }
}
