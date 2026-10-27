// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import javax.net.ssl.HttpsURLConnection

/** Maps FTP listings to [FileEntry]s (pure, unit-tested). */
object FtpMapping {
    fun entry(dir: String, f: FTPFile): FileEntry? {
        val name = f.name?.substringAfterLast('/')?.takeIf { FsPath.isSafeName(it) && !hasControl(it) } ?: return null
        val isDir = f.isDirectory || (f.isSymbolicLink && f.link?.endsWith("/") == true)
        return FileEntry(
            path = FsPath.child(FsPath.normalize(dir), name), name = name, isDir = isDir,
            size = if (isDir) 0 else f.size.coerceAtLeast(0), modified = f.timestamp?.timeInMillis ?: 0L,
            isLink = f.isSymbolicLink,
        )
    }

    /** CR / LF / NUL would end the FTP command early and let the rest run as a new command. */
    fun hasControl(s: String): Boolean = s.any { it == '\r' || it == '\n' || it == '\u0000' }

    /** Absolute server path for an `ftp://authority/rel` path; throws if it can't be sent safely as an argument. */
    fun serverPath(path: String): String {
        val p = "/" + RemoteUri.relOf(path)
        if (hasControl(p)) throw IOException("Invalid characters in path")
        return p
    }
}

/**
 * FTP / FTPS (explicit TLS) file system on Apache commons-net: passive mode, binary transfers, UTF-8 control
 * encoding. A small per-server pool of logged-in connections is kept; every operation borrows one exclusively
 * (streams hold theirs until closed), so concurrent copies never share a control connection.
 */
class FtpFileSystem(private val creds: CredentialProvider) : RemoteFileSystem {
    private val idle = HashMap<String, ArrayDeque<FTPClient>>()
    private val features = HashMap<String, Boolean>()

    private fun key(path: String) = RemoteUri.credKeyOf(path) ?: throw IOException("Not an FTP path: $path")

    private fun connect(path: String): FTPClient {
        val secure = path.startsWith("ftps://")
        val proto = if (secure) Protocol.FTPS else Protocol.FTP
        val (host, port) = RemoteUri.splitAuthority(path.substringAfter("://").substringBefore('/'), proto.defaultPort)
        val cred = creds.get(key(path))
        val user = cred?.user?.takeIf { it.isNotBlank() }
        // USER / PASS are sent verbatim on the control connection.
        if (user != null && (FtpMapping.hasControl(user) || FtpMapping.hasControl(cred.password))) {
            throw IOException("The user name or password contains invalid characters")
        }
        val c: FTPClient = if (secure) {
            FTPSClient(false).apply {
                // commons-net's default trust manager only checks certificate dates: use the platform trust store
                // and verify that the certificate matches the host name.
                trustManager = try { TrustManagerUtils.getDefaultTrustManager(null) } catch (e: java.security.GeneralSecurityException) {
                    throw IOException("No trusted certificates available: ${e.message}", e)
                }
                hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                isEndpointCheckingEnabled = true
            }
        } else FTPClient()
        c.controlEncoding = "UTF-8"
        c.connectTimeout = 15_000
        c.setDefaultTimeout(60_000)
        c.setDataTimeout(Duration.ofSeconds(60))
        try {
            c.connect(host.removePrefix("[").removeSuffix("]"), port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("Server refused the connection (${c.replyCode})")
            if (c is FTPSClient) {
                c.execPBSZ(0)
                c.execPROT("P")
            }
        } catch (e: IOException) {
            runCatching { c.disconnect() }
            throw IOException("Can't reach $host: ${e.message ?: "connection failed"}", e)
        } catch (e: java.security.GeneralSecurityException) {
            runCatching { c.disconnect() }
            throw IOException("Can't set up a secure connection to $host: ${e.message}", e)
        }
        val ok = if (user == null) c.login("anonymous", "wayfinder@") else c.login(user, cred.password)
        if (!ok) {
            runCatching { c.disconnect() }
            throw RemoteAuthException(key(path))
        }
        runCatching { c.sendCommand("OPTS UTF8 ON") }
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.enterLocalPassiveMode()
        c.soTimeout = 60_000
        return c
    }

    private fun borrow(path: String): FTPClient {
        val k = key(path)
        while (true) {
            val c = synchronized(this) { idle[k]?.removeFirstOrNull() } ?: return connect(path)
            // Drop connections the server has closed meanwhile.
            if (c.isConnected && runCatching { c.sendNoOp() }.getOrDefault(false)) return c
            runCatching { c.disconnect() }
        }
    }

    private fun release(path: String, c: FTPClient, broken: Boolean = false) {
        if (broken || !c.isConnected) { runCatching { c.disconnect() }; return }
        val k = key(path)
        val keep = synchronized(this) {
            val q = idle.getOrPut(k) { ArrayDeque() }
            if (q.size < 2) { q.addLast(c); true } else false
        }
        if (!keep) runCatching { c.logout(); c.disconnect() }
    }

    private fun <T> withClient(path: String, block: (FTPClient) -> T): T {
        val c = borrow(path)
        var broken = false
        try {
            return block(c)
        } catch (e: IOException) {
            broken = true
            throw e
        } finally {
            release(path, c, broken)
        }
    }

    @Synchronized
    override fun disconnect(key: String) {
        idle.remove(key)?.forEach { c -> runCatching { c.disconnect() } }
        features.remove(key)
    }

    private fun hasMlsd(path: String, c: FTPClient): Boolean {
        val k = key(path)
        synchronized(this) { features[k] }?.let { return it }
        val v = runCatching { c.hasFeature("MLSD") }.getOrDefault(false)
        synchronized(this) { features[k] = v }
        return v
    }

    private fun replyError(c: FTPClient, what: String): Nothing =
        throw IOException("$what: ${c.replyString?.trim()?.ifEmpty { null } ?: "server error"}")

    private fun listRaw(path: String, c: FTPClient): Array<FTPFile> {
        val sp = FtpMapping.serverPath(path)
        val files = if (hasMlsd(path, c)) c.mlistDir(sp) else c.listFiles(sp)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) replyError(c, "Couldn't open ${FsPath.name(path)}")
        return files ?: emptyArray()
    }

    override fun list(path: String): List<FileEntry> = withClient(path) { c ->
        listRaw(path, c).mapNotNull { FtpMapping.entry(path, it) }
    }

    override fun stat(path: String): FileEntry? {
        val p = FsPath.normalize(path)
        if (RemoteUri.relOf(p).isEmpty()) {
            withClient(p) { } // verifies that we can connect and sign in
            return FileEntry(p, FsPath.name(p), true, isServer = true)
        }
        return withClient(p) { c ->
            if (hasMlsd(p, c)) {
                val f = runCatching { c.mlistFile(FtpMapping.serverPath(p)) }.getOrNull()
                if (f != null) return@withClient FtpMapping.entry(FsPath.parent(p)!!, f.apply { name = FsPath.name(p) })
                if (FTPReply.isPositiveCompletion(c.replyCode)) return@withClient null
            }
            val parent = FsPath.parent(p)!!
            val name = FsPath.name(p)
            val files = c.listFiles(FtpMapping.serverPath(parent)) ?: return@withClient null
            files.firstOrNull { it.name?.substringAfterLast('/') == name }?.let { FtpMapping.entry(parent, it) }
        }
    }

    override fun mkdir(path: String) = withClient(path) { c ->
        if (!c.makeDirectory(FtpMapping.serverPath(path))) {
            if (c.changeWorkingDirectory(FtpMapping.serverPath(path))) {
                // Don't leave the pooled session parked inside the folder: many servers refuse to delete a session's CWD.
                c.changeWorkingDirectory("/")
                return@withClient
            }
            replyError(c, "Couldn't create folder ${FsPath.name(path)}")
        }
    }

    override fun createFile(path: String) {
        if (exists(path)) throw IOException("${FsPath.name(path)} already exists")
        withClient(path) { c ->
            if (!c.storeFile(FtpMapping.serverPath(path), java.io.ByteArrayInputStream(ByteArray(0)))) replyError(c, "Couldn't create ${FsPath.name(path)}")
        }
    }

    override fun delete(path: String) {
        val st = stat(path) ?: return
        withClient(path) { c -> deleteTree(path, st.isDir, c) }
    }

    private fun deleteTree(path: String, dir: Boolean, c: FTPClient) {
        val sp = FtpMapping.serverPath(path)
        if (!dir) {
            if (!c.deleteFile(sp)) replyError(c, "Couldn't delete ${FsPath.name(path)}")
            return
        }
        for (f in listRaw(path, c)) {
            val e = FtpMapping.entry(path, f) ?: continue
            deleteTree(e.path, e.isDir && !f.isSymbolicLink, c)
        }
        if (!c.removeDirectory(sp)) replyError(c, "Couldn't delete ${FsPath.name(path)}")
    }

    override fun rename(path: String, newName: String): String {
        if (!FsPath.isSafeName(newName)) throw IOException("Invalid name")
        val dst = FsPath.child(FsPath.parent(path)!!, newName)
        if (exists(dst)) throw IOException("\"$newName\" already exists")
        if (!move(path, dst)) throw IOException("Couldn't rename ${FsPath.name(path)}")
        return dst
    }

    override fun move(src: String, dstPath: String): Boolean {
        if (RemoteUri.rootOf(src) != RemoteUri.rootOf(dstPath) || RemoteUri.relOf(src).isEmpty()) return false
        if (exists(dstPath)) return false
        return withClient(src) { c -> c.rename(FtpMapping.serverPath(src), FtpMapping.serverPath(dstPath)) }
    }

    override fun openInput(path: String): InputStream {
        val c = borrow(path)
        val raw = try {
            c.retrieveFileStream(FtpMapping.serverPath(path))
        } catch (e: IOException) {
            release(path, c, broken = true); throw e
        } ?: run {
            val msg = c.replyString?.trim()
            release(path, c)
            throw IOException("Couldn't open ${FsPath.name(path)}: ${msg ?: "server error"}")
        }
        return object : FilterInputStream(raw.buffered(256 * 1024)) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                var ok = false
                try {
                    super.close()
                    ok = c.completePendingCommand()
                } catch (_: IOException) {
                } finally {
                    release(path, c, broken = !ok)
                }
            }
        }
    }

    override fun openOutput(path: String): OutputStream {
        val c = borrow(path)
        val raw = try {
            c.storeFileStream(FtpMapping.serverPath(path))
        } catch (e: IOException) {
            release(path, c, broken = true); throw e
        } ?: run {
            val msg = c.replyString?.trim()
            release(path, c)
            throw IOException("Couldn't write ${FsPath.name(path)}: ${msg ?: "server error"}")
        }
        return object : FilterOutputStream(raw.buffered(256 * 1024)) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                var ok = false
                try {
                    super.close()
                    ok = c.completePendingCommand()
                    if (!ok) throw IOException("Upload of ${FsPath.name(path)} failed: ${c.replyString?.trim()}")
                } finally {
                    release(path, c, broken = !ok)
                }
            }
        }
    }
}
