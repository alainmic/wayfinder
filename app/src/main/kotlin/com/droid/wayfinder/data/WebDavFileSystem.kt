// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * WebDAV / WebDAVS file system on OkHttp. Paths look like `dav://host[:port]/base/dir/file` (plain HTTP)
 * or `davs://...` (HTTPS); credentials (HTTP Basic) are looked up by `scheme://authority`.
 */
class WebDavFileSystem(private val creds: CredentialProvider) : RemoteFileSystem {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Redirects are validated manually in exec() so cross-origin redirects are never followed.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val xmlType = "application/xml; charset=utf-8".toMediaType()

    override fun disconnect(key: String) = client.connectionPool.evictAll()

    private fun key(path: String) = RemoteUri.credKeyOf(path) ?: throw IOException("Not a WebDAV path: $path")

    /** HTTP(S) URL for [path]; [dir] adds the trailing slash collections expect. */
    private fun url(path: String, dir: Boolean = false): HttpUrl {
        val scheme = if (path.startsWith("davs://")) "https" else "http"
        val authority = path.substringAfter("://").substringBefore('/')
        val b = try { "$scheme://$authority/".toHttpUrl().newBuilder() } catch (e: IllegalArgumentException) {
            throw IOException("Invalid server address: $authority", e)
        }
        val segs = RemoteUri.relOf(path).split('/').filter { it.isNotEmpty() }
        // OkHttp resolves "." / ".." segments, which would silently leave the folder we were asked for.
        if (segs.any { !FsPath.isSafeName(it) }) throw IOException("Invalid path: $path")
        segs.forEach { b.addPathSegment(it) }
        if (dir && segs.isNotEmpty()) b.addPathSegment("")
        return b.build()
    }

    private fun request(path: String, url: HttpUrl = url(path)): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", "Wayfinder")
        val c = creds.get(key(path))
        if (c != null && c.user.isNotBlank()) b.header("Authorization", Credentials.basic(c.user, c.password, Charsets.UTF_8))
        return b
    }

    private fun exec(path: String, req: Request): Response {
        var current = req
        for (hop in 0..5) {
            val r = try {
                client.newCall(current).execute()
            } catch (e: IOException) {
                throw IOException("Can't reach ${url(path).host}: ${e.message ?: "connection failed"}", e)
            }
            if (r.code == 401) {
                r.close()
                throw RemoteAuthException(key(path))
            }
            if (r.code in listOf(301, 302, 303, 307, 308)) {
                val loc = r.header("Location")
                r.close()
                if (loc.isNullOrBlank() || hop == 5) {
                    throw IOException("Too many or invalid redirects from ${req.url.host}")
                }
                val target = current.url.resolve(loc)
                    ?: throw IOException("Invalid redirect target from ${req.url.host}")
                if (
                    target.scheme != req.url.scheme ||
                    !target.host.equals(req.url.host, ignoreCase = true) ||
                    target.port != req.url.port
                ) {
                    throw IOException("Refused cross-origin redirect to ${target.scheme}://${target.host}:${target.port}")
                }
                current = current.newBuilder().url(target).build()
                continue
            }
            return r
        }
        throw IOException("Too many redirects from ${req.url.host}")
    }

    private fun fail(r: Response, what: String): Nothing {
        val code = r.code
        r.close()
        throw IOException(
            when (code) {
                403 -> "$what: access denied"
                404 -> "$what: not found"
                405 -> "$what: not allowed by the server"
                409 -> "$what: the parent folder doesn't exist"
                423 -> "$what: the item is locked"
                507 -> "$what: the server is out of space"
                else -> "$what failed (HTTP $code)"
            },
        )
    }

    private fun propfind(path: String, depth: Int): Response {
        val req = request(path, url(path, dir = depth == 1)).header("Depth", depth.toString())
            .method("PROPFIND", WebDavXml.PROPFIND_BODY.toRequestBody(xmlType)).build()
        return exec(path, req)
    }

    /** Reads a PROPFIND response, refusing absurdly large bodies (memory exhaustion by a hostile server). */
    private fun bodyText(r: Response): String {
        val body = r.body ?: return ""
        if (body.contentLength() > WebDavXml.MAX_RESPONSE_BYTES) throw IOException("The server's response is too large")
        val bytes = BoundedInputStream(body.byteStream(), WebDavXml.MAX_RESPONSE_BYTES, "The server's response").use { it.readBytes() }
        return String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    override fun list(path: String): List<FileEntry> {
        val base = FsPath.normalize(path)
        propfind(base, 1).use { r ->
            if (r.code != 207 && !r.isSuccessful) fail(r, "Couldn't open ${FsPath.name(base)}")
            val self = WebDavXml.hrefToPath(url(base).encodedPath)
            return WebDavXml.parseMultistatus(bodyText(r))
                // Names come from the server: ".." or names that decoded to contain "/" must never become paths.
                .filter { it.path != self && FsPath.isSafeName(it.name) }
                .distinctBy { it.name }
                .map { res ->
                    FileEntry(
                        path = FsPath.child(base, res.name), name = res.name, isDir = res.isDir,
                        size = res.size, modified = res.modified,
                    )
                }
        }
    }

    override fun stat(path: String): FileEntry? {
        val p = FsPath.normalize(path)
        propfind(p, 0).use { r ->
            if (r.code == 404) return null
            if (r.code != 207 && !r.isSuccessful) fail(r, "Couldn't read ${FsPath.name(p)}")
            val res = WebDavXml.parseMultistatus(bodyText(r)).firstOrNull() ?: return null
            val root = RemoteUri.relOf(p).isEmpty()
            return FileEntry(p, FsPath.name(p), res.isDir || root, if (res.isDir) 0 else res.size, res.modified, isServer = root)
        }
    }

    override fun mkdir(path: String) {
        exec(path, request(path, url(path, dir = true)).method("MKCOL", null).build()).use { r ->
            if (r.isSuccessful) return
            if (r.code == 405 && stat(path)?.isDir == true) return
            fail(r, "Couldn't create folder ${FsPath.name(path)}")
        }
    }

    override fun createFile(path: String) {
        val req = request(path).header("If-None-Match", "*").put(ByteArray(0).toRequestBody(null)).build()
        exec(path, req).use { r ->
            if (r.code == 412) throw IOException("${FsPath.name(path)} already exists")
            if (!r.isSuccessful) fail(r, "Couldn't create ${FsPath.name(path)}")
        }
    }

    override fun delete(path: String) {
        exec(path, request(path).delete().build()).use { r ->
            if (!r.isSuccessful && r.code != 404) fail(r, "Couldn't delete ${FsPath.name(path)}")
        }
    }

    override fun rename(path: String, newName: String): String {
        if (!FsPath.isSafeName(newName)) throw IOException("Invalid name")
        val dst = FsPath.child(FsPath.parent(path)!!, newName)
        if (!move(path, dst)) throw IOException("\"$newName\" already exists")
        return dst
    }

    override fun move(src: String, dstPath: String): Boolean {
        if (RemoteUri.rootOf(src) != RemoteUri.rootOf(dstPath) || RemoteUri.relOf(src).isEmpty()) return false
        val req = request(src).header("Destination", url(dstPath).toString()).header("Overwrite", "F")
            .method("MOVE", null).build()
        exec(src, req).use { r ->
            if (r.code == 412) return false
            if (!r.isSuccessful) fail(r, "Couldn't move ${FsPath.name(src)}")
            return true
        }
    }

    override fun openInput(path: String): InputStream {
        val r = exec(path, request(path).get().build())
        if (!r.isSuccessful) fail(r, "Couldn't open ${FsPath.name(path)}")
        val body = r.body ?: run { r.close(); throw IOException("Empty response") }
        return object : FilterInputStream(body.byteStream().buffered(256 * 1024)) {
            override fun close() = try { super.close() } finally { r.close() }
        }
    }

    /** Uploads are spooled to a temporary file and sent with a known length on close (no chunked PUTs). */
    override fun openOutput(path: String): OutputStream {
        val tmp = File.createTempFile("wf-dav", ".part")
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
                    val req = request(path).put(tmp.asRequestBody("application/octet-stream".toMediaType())).build()
                    exec(path, req).use { r -> if (!r.isSuccessful) fail(r, "Couldn't upload ${FsPath.name(path)}") }
                } finally {
                    tmp.delete()
                }
            }
        }
    }
}
