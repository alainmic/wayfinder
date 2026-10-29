// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class DiscoveredRoot(
    val authority: String,
    val rootId: String,
    val title: String,
    val documentId: String,
    val flags: Int = 0,
    val summary: String? = null,
)

object SystemDocumentsBridge {
    private const val TAG = "SystemDocumentsBridge"

    /** Discovers all accessible DocumentsProvider roots on the system. */
    fun discoverRoots(context: Context): List<DiscoveredRoot> {
        val roots = mutableListOf<DiscoveredRoot>()
        val pm = context.packageManager
        val intent = Intent("android.content.action.DOCUMENTS_PROVIDER")
        val providers = runCatching { pm.queryIntentContentProviders(intent, 0) }.getOrDefault(emptyList())

        for (ri in providers) {
            val auth = ri.providerInfo?.authority ?: continue
            val rootsUri = runCatching { DocumentsContract.buildRootsUri(auth) }.getOrNull() ?: continue
            runCatching {
                context.contentResolver.query(rootsUri, null, null, null, null)?.use { c ->
                    val rootIdCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                    val titleCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
                    val docIdCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                    val flagsCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)
                    val summaryCol = c.getColumnIndex(DocumentsContract.Root.COLUMN_SUMMARY)

                    while (c.moveToNext()) {
                        val rootId = if (rootIdCol >= 0) c.getString(rootIdCol) else ""
                        val title = if (titleCol >= 0) c.getString(titleCol) else ""
                        val docId = if (docIdCol >= 0) c.getString(docIdCol) else ""
                        val flags = if (flagsCol >= 0) c.getInt(flagsCol) else 0
                        val summary = if (summaryCol >= 0) c.getString(summaryCol) else null
                        val root = DiscoveredRoot(auth, rootId, title, docId, flags, summary)
                        roots.add(root)
                        Log.d(TAG, "Discovered root: auth=$auth, title=$title, rootId=$rootId, docId=$docId")
                    }
                }
            }.onFailure { e ->
                Log.d(TAG, "Cannot query roots for $auth: ${e.message}")
            }
        }
        return roots
    }

    /** Lists children of [documentId] under [authority] using direct DocumentsContract query. */
    fun listChildren(context: Context, authority: String, documentId: String): List<FileEntry>? {
        return runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, documentId)
            context.contentResolver.query(childrenUri, null, null, null, null)?.use { c ->
                val docIdCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val summaryCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SUMMARY)

                val list = mutableListOf<FileEntry>()
                while (c.moveToNext()) {
                    val childDocId = c.getString(docIdCol) ?: continue
                    val name = safeDisplayName(c.getString(nameCol)) ?: continue
                    val mime = c.getString(mimeCol) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (isDir) 0L else c.getLong(sizeCol).coerceAtLeast(0L)
                    val mod = c.getLong(modCol)
                    val docSummary = if (summaryCol >= 0 && !c.isNull(summaryCol)) c.getString(summaryCol)?.takeIf { it.isNotBlank() } else null
                    val defaultSubtitle = when {
                        authority.contains("mtp") || authority.contains("phone") -> "Pixel 9"
                        GooglebookEcosystem.isLinuxAuthority(authority) -> "Linux files"
                        else -> "Documents"
                    }

                    list.add(
                        FileEntry(
                            path = "doc://$authority/$childDocId",
                            name = name,
                            isDir = isDir,
                            size = size,
                            modified = if (mod > 0) mod else System.currentTimeMillis(),
                            subtitle = docSummary ?: defaultSubtitle,
                        )
                    )
                }
                list.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
            }
        }.onFailure { e ->
            Log.d(TAG, "Failed to list children for $authority / $documentId: ${e.message}")
        }.getOrNull()
    }
}

/**
 * FileSystem implementation for direct DocumentsContract access (`doc://<authority>/<documentId>`).
 */
class DocFileSystem(private val context: Context) : RemoteFileSystem {
    override fun disconnect(key: String) {}

    override fun list(path: String): List<FileEntry> {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = trimmed.substringAfter('/')
        return SystemDocumentsBridge.listChildren(context, auth, docId)
            ?: throw IOException("Cannot read folder: $path (Permission denied or device disconnected)")
    }

    override fun stat(path: String): FileEntry? {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = trimmed.substringAfter('/')
        return runCatching {
            val docUri = DocumentsContract.buildDocumentUri(auth, docId)
            context.contentResolver.query(docUri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = safeDisplayName(c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))) ?: "Item"
                    val mime = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (isDir) 0L else c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)).coerceAtLeast(0L)
                    val mod = c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    val summaryCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SUMMARY)
                    val summary = if (summaryCol >= 0 && !c.isNull(summaryCol)) c.getString(summaryCol)?.takeIf { it.isNotBlank() } else null
                    FileEntry(path, name, isDir, size, if (mod > 0) mod else System.currentTimeMillis(), subtitle = summary)
                } else null
            }
        }.getOrNull()
    }

    override fun openInput(path: String): InputStream {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = trimmed.substringAfter('/')
        val docUri = DocumentsContract.buildDocumentUri(auth, docId)
        return context.contentResolver.openInputStream(docUri)
            ?: throw IOException("Cannot open $path")
    }

    override fun openOutput(path: String): OutputStream {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = trimmed.substringAfter('/')
        val docUri = DocumentsContract.buildDocumentUri(auth, docId)
        return context.contentResolver.openOutputStream(docUri)
            ?: throw IOException("Cannot write to $path")
    }

    override fun mkdir(path: String) {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val rest = trimmed.substringAfter('/')
        val name = rest.substringAfterLast('/')
        FsPath.nameError(name)?.let { throw IOException(it) }
        val parentDocId = rest.substringBeforeLast('/', "root")
        val parentUri = DocumentsContract.buildDocumentUri(auth, parentDocId)
        DocumentsContract.createDocument(context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Failed to create folder $name")
    }

    override fun createFile(path: String) {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val rest = trimmed.substringAfter('/')
        val name = rest.substringAfterLast('/')
        FsPath.nameError(name)?.let { throw IOException(it) }
        val parentDocId = rest.substringBeforeLast('/', "root")
        val parentUri = DocumentsContract.buildDocumentUri(auth, parentDocId)
        DocumentsContract.createDocument(context.contentResolver, parentUri, "application/octet-stream", name)
            ?: throw IOException("Failed to create file $name")
    }

    override fun delete(path: String) {
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = trimmed.substringAfter('/')
        val docUri = DocumentsContract.buildDocumentUri(auth, docId)
        if (!DocumentsContract.deleteDocument(context.contentResolver, docUri)) {
            throw IOException("Failed to delete $path")
        }
    }

    override fun rename(path: String, newName: String): String {
        FsPath.nameError(newName)?.let { throw IOException(it) }
        val trimmed = path.removePrefix("doc://")
        val auth = trimmed.substringBefore('/')
        val docId = trimmed.substringAfter('/')
        val docUri = DocumentsContract.buildDocumentUri(auth, docId)
        val resultUri = DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
            ?: throw IOException("Failed to rename $path")
        val newDocId = DocumentsContract.getDocumentId(resultUri)
        return "doc://$auth/$newDocId"
    }
}
