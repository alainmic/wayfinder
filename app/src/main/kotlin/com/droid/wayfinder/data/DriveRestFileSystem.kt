// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.accounts.AccountManager
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * FileSystem implementation for Google Drive using the Google Drive REST API v3.
 *
 * Paths: `gdrive://<email>/<folderId>`
 * Top-level "My Drive": `gdrive://<email>/root`
 */
class DriveRestFileSystem(
    private val context: Context? = null,
    private val client: OkHttpClient = OkHttpClient(),
    private val tokenOverride: ((String) -> String?)? = null,
) : RemoteFileSystem {

    private val tokenCache = ConcurrentHashMap<String, String>()
    private val idToParent = ConcurrentHashMap<String, String>()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun setToken(email: String, token: String) {
        tokenCache[email.lowercase()] = token
    }

    override fun disconnect(key: String) {
        tokenCache.remove(key.lowercase())
    }

    /**
     * Obtains an OAuth 2.0 access token for [email] via Android's AccountManager or [tokenOverride].
     */
    fun getToken(email: String): String {
        tokenOverride?.invoke(email)?.let { return it }
        tokenCache[email.lowercase()]?.let { return it }

        return runCatching {
            val am = context?.let { AccountManager.get(it) } ?: throw IOException("AccountManager unavailable")
            val account = android.accounts.Account(email, "com.google")

            val scope = "oauth2:https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive"
            val future = am.getAuthToken(account, scope, null, false, null, null)
            val bundle = future.result
            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                ?: bundle.getString("authtoken")
                ?: throw IOException("Could not obtain auth token for $email. Please ensure OAuth Client ID is registered in Google Cloud Console.")
            tokenCache[email.lowercase()] = token
            token
        }.getOrElse { e ->
            throw IOException(
                "Google Drive authorization required for $email: ${e.message ?: "Authentication failed"}. " +
                    "Ensure an OAuth 2.0 Android client ID for this app's signing certificate is configured, or mount Google Drive via Add folder.",
                e,
            )
        }
    }

    companion object {
        /** True when [id] is a safe Google Drive file/folder identifier (prevents query & URL injection). */
        fun isSafeDriveId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 256 && id != "." && id != ".." &&
                id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }

        fun requireSafeDriveId(id: String): String {
            if (!isSafeDriveId(id)) throw IOException("Invalid Google Drive ID")
            return id
        }
    }

    /** Parses `gdrive://<email>/<folderId>` into (email, folderId). */
    fun parsePath(path: String): Pair<String, String> {
        val trimmed = path.removePrefix("gdrive://").trim('/')
        val email = trimmed.substringBefore('/')
        val rest = trimmed.substringAfter('/', "").ifBlank { "root" }
        return email to rest
    }

    override fun list(path: String): List<FileEntry> {
        val (email, rawFolderId) = parsePath(path)
        val folderId = requireSafeDriveId(rawFolderId)
        val token = getToken(email)

        val query = "'$folderId' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&fields=files(id,name,mimeType,size,modifiedTime)" +
            "&pageSize=1000"

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            val errBody = resp.body?.string().orEmpty()
            resp.close()
            throw IOException("Google Drive list failed (HTTP $code): $errBody")
        }

        val body = resp.body?.string() ?: throw IOException("Empty response from Google Drive")
        val json = JSONObject(body)
        val filesArray = json.optJSONArray("files") ?: JSONArray()
        val entries = mutableListOf<FileEntry>()

        for (i in 0 until filesArray.length()) {
            val f = filesArray.getJSONObject(i)
            val id = f.optString("id")
            if (!isSafeDriveId(id)) continue
            val name = safeDisplayName(f.optString("name", "Untitled")) ?: continue
            val mime = f.optString("mimeType", "")
            val isDir = mime == "application/vnd.google-apps.folder"
            val size = if (isDir) 0L else f.optLong("size", 0L).coerceAtLeast(0L)
            val modifiedTime = parseIsoTime(f.optString("modifiedTime"))
            val childPath = "gdrive://$email/$id"
            idToParent[childPath] = path

            entries += FileEntry(
                path = childPath,
                name = name,
                isDir = isDir,
                size = size,
                modified = modifiedTime,
                subtitle = "Google Drive",
            )
        }
        return entries.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): FileEntry? {
        val (email, fileId) = parsePath(path)
        if (fileId == "root" || fileId.isBlank()) {
            return FileEntry(path = path, name = "My Drive", isDir = true, modified = System.currentTimeMillis())
        }
        if (!isSafeDriveId(fileId)) return null
        return runCatching {
            val token = getToken(email)
            val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=id,name,mimeType,size,modifiedTime"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val f = JSONObject(body)
                val mime = f.optString("mimeType", "")
                val isDir = mime == "application/vnd.google-apps.folder"
                val safeName = safeDisplayName(f.optString("name", fileId)) ?: fileId
                FileEntry(
                    path = path,
                    name = safeName,
                    isDir = isDir,
                    size = if (isDir) 0L else f.optLong("size", 0L).coerceAtLeast(0L),
                    modified = parseIsoTime(f.optString("modifiedTime")),
                    subtitle = "Google Drive",
                )
            }
        }.getOrNull()
    }

    override fun openInput(path: String): InputStream {
        val (email, rawFileId) = parsePath(path)
        val fileId = requireSafeDriveId(rawFileId)
        val token = getToken(email)
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("Failed to download from Google Drive (HTTP $code)")
        }
        val body = resp.body ?: throw IOException("Empty response body from Google Drive")
        return body.byteStream()
    }

    override fun openOutput(path: String): OutputStream {
        val (email, _) = parsePath(path)
        val token = getToken(email)
        val cacheDir = runCatching { context.cacheDir }.getOrNull()
        val tmp = File.createTempFile("wf-gdrive", ".part", cacheDir)
        val fos = try {
            FileOutputStream(tmp).buffered(256 * 1024)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return object : FilterOutputStream(fos) {
            private var closed = false
            override fun write(b: Int) {
                try {
                    out.write(b)
                } catch (e: IOException) {
                    tmp.delete()
                    throw e
                }
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                try {
                    out.write(b, off, len)
                } catch (e: IOException) {
                    tmp.delete()
                    throw e
                }
            }
            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                    val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=media"
                    val body = tmp.asRequestBody("application/octet-stream".toMediaType())
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .post(body)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("Failed to upload to Google Drive (HTTP ${resp.code})")
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    override fun mkdir(path: String) {
        val (email, _) = parsePath(path)
        val name = path.substringAfterLast('/')
        FsPath.nameError(name)?.let { throw IOException(it) }
        val token = getToken(email)
        val parent = requireSafeDriveId(idToParent[path]?.let { parsePath(it).second } ?: "root")
        val payload = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", JSONArray().put(parent))
        }
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to create folder in Google Drive (HTTP ${resp.code})")
        }
    }

    override fun createFile(path: String) {
        val (email, _) = parsePath(path)
        val name = path.substringAfterLast('/')
        FsPath.nameError(name)?.let { throw IOException(it) }
        val token = getToken(email)
        val parent = requireSafeDriveId(idToParent[path]?.let { parsePath(it).second } ?: "root")
        val payload = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().put(parent))
        }
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to create file in Google Drive (HTTP ${resp.code})")
        }
    }

    override fun delete(path: String) {
        val (email, rawFileId) = parsePath(path)
        val fileId = requireSafeDriveId(rawFileId)
        val token = getToken(email)
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to delete file from Google Drive (HTTP ${resp.code})")
        }
    }

    override fun rename(path: String, newName: String): String {
        FsPath.nameError(newName)?.let { throw IOException(it) }
        val (email, rawFileId) = parsePath(path)
        val fileId = requireSafeDriveId(rawFileId)
        val token = getToken(email)
        val payload = JSONObject().apply { put("name", newName) }
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $token")
            .patch(payload.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to rename file in Google Drive (HTTP ${resp.code})")
        }
        return path
    }

    private fun parseIsoTime(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(iso)?.time ?: System.currentTimeMillis()
        }.getOrElse {
            runCatching {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(iso)?.time ?: System.currentTimeMillis()
            }.getOrDefault(System.currentTimeMillis())
        }
    }
}
