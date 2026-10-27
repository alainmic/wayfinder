// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FileSystem implementation for paired phone browsing (`phone://`).
 * Bridges to real mounted phone/MTP volumes when present, or provides the standard Android phone
 * filesystem hierarchy (Download, Pictures, DCIM, Documents, etc.) mapped to device/Quick Share directories.
 */
class PhoneFileSystem(
    private val context: Context? = null,
    private val rootOverride: File? = null,
) : RemoteFileSystem {
    override fun disconnect(key: String) {}

    val standardFolders = listOf(
        "Download", "Pictures", "DCIM", "Movies", "Music",
        "Audiobooks", "Documents", "Recordings", "Alarms",
        "Notifications", "Ringtones", "Podcasts", "Android"
    )

    /**
     * Parses and validates segments of a `phone://...` path.
     * Rejects `.`, `..`, backslashes, NUL bytes, and excessive nesting.
     */
    fun validateSegments(path: String): List<String> {
        if (!path.startsWith(FsPath.PHONE_ROOT)) throw IOException("Not a phone path: $path")
        val raw = path.removePrefix(FsPath.PHONE_ROOT)
        if (raw.contains('\\') || raw.contains('\u0000')) throw IOException("Unsafe phone path: $path")
        val segs = raw.split('/').filter { it.isNotEmpty() }
        if (segs.size > FsPath.MAX_DEPTH) throw IOException("Phone path nested too deeply")
        for (seg in segs) {
            if (!FsPath.isSafeName(seg)) throw IOException("Unsafe path segment in phone path: $seg")
        }
        return segs
    }

    fun resolveRealDirectory(): File? {
        if (rootOverride != null) return null
        val ctx = context ?: return null
        // 1. Check StorageManager storageVolumes for phone volume
        runCatching {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                val desc = v.getDescription(ctx).orEmpty()
                if (!v.isPrimary || desc.contains("Pixel", true) || desc.contains("Phone", true)) {
                    val d = v.directory
                    if (d != null && d.exists() && d.canRead() && (d.listFiles()?.isNotEmpty() == true)) {
                        return d
                    }
                }
            }
        }
        // 2. Check /storage mounts
        runCatching {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "emulated" && f.name != "self") {
                    if (f.listFiles()?.isNotEmpty() == true) return f
                }
            }
        }
        // 3. Check /storage/emulated/0
        runCatching {
            val f0 = File("/storage/emulated/0")
            if (f0.isDirectory && f0.canRead() && f0.listFiles()?.any { it.name.equals("DCIM", true) } == true) {
                return f0
            }
        }
        return null
    }

    private fun hostRootDir(): File = rootOverride
        ?: runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.takeIf { it.exists() }
        ?: File("/storage/emulated/0")

    private fun mapFolderToHost(segs: List<String>): File {
        if (segs.isEmpty()) return hostRootDir()
        val root = segs.first()
        val relSegs = segs.drop(1)
        val hostBase = hostRootDir()
        val base = when (root.lowercase()) {
            "download", "downloads" -> {
                val qs = File(hostBase, "Download/Quick Share")
                if (qs.exists()) qs else if (rootOverride == null) {
                    runCatching {
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Quick Share").takeIf { it.exists() }
                    }.getOrNull() ?: File(hostBase, "Download")
                } else File(hostBase, "Download")
            }
            "pictures" -> File(hostBase, "Pictures")
            "dcim" -> File(hostBase, "DCIM")
            "documents" -> File(hostBase, "Documents")
            "movies" -> File(hostBase, "Movies")
            "music" -> File(hostBase, "Music")
            "audiobooks" -> File(hostBase, "Audiobooks")
            "recordings" -> File(hostBase, "Recordings")
            "alarms" -> File(hostBase, "Alarms")
            "notifications" -> File(hostBase, "Notifications")
            "ringtones" -> File(hostBase, "Ringtones")
            "podcasts" -> File(hostBase, "Podcasts")
            "android" -> File(hostBase, "Android")
            else -> File(hostBase, root)
        }
        var cur = base
        for (s in relSegs) {
            cur = File(cur, s)
        }
        return cur
    }

    private fun resolveTarget(segs: List<String>, realDir: File?): File {
        if (realDir != null) {
            var cur = realDir
            for (s in segs) cur = File(cur, s)
            return cur
        }
        return mapFolderToHost(segs)
    }

    override fun list(path: String): List<FileEntry> {
        val segs = validateSegments(path)
        val sub = segs.joinToString("/")
        val realDir = resolveRealDirectory()

        if (realDir != null) {
            val target = resolveTarget(segs, realDir)
            val files = target.listFiles() ?: emptyArray()
            return files.filter { FsPath.isSafeName(it.name) && !it.name.startsWith(".") }.map { f ->
                val p = if (sub.isEmpty()) "${FsPath.PHONE_ROOT}${f.name}" else "${FsPath.PHONE_ROOT}$sub/${f.name}"
                val link = FileInfo.isSymlink(f.path)
                FileEntry(
                    path = p,
                    name = f.name,
                    isDir = f.isDirectory,
                    size = if (f.isDirectory) 0L else f.length(),
                    modified = f.lastModified(),
                    isLink = link,
                    subtitle = null,
                )
            }.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }

        // Virtual phone filesystem matching system Files app
        if (segs.isEmpty()) {
            val baseTime = 1744848000000L // Apr 17, 2025 phone setup timestamp
            return standardFolders.map { name ->
                val host = mapFolderToHost(listOf(name))
                val mod = if (host.exists() && host.lastModified() > baseTime) host.lastModified() else baseTime
                FileEntry(
                    path = "${FsPath.PHONE_ROOT}$name",
                    name = name,
                    isDir = true,
                    size = 0L,
                    modified = mod,
                    subtitle = null,
                )
            }.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }

        val hostDir = mapFolderToHost(segs)
        val files = hostDir.listFiles()?.filter { FsPath.isSafeName(it.name) && !it.name.startsWith(".") } ?: emptyList()
        return files.map { f ->
            val link = FileInfo.isSymlink(f.path)
            FileEntry(
                path = "${FsPath.PHONE_ROOT}$sub/${f.name}",
                name = f.name,
                isDir = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                modified = f.lastModified(),
                isLink = link,
                subtitle = null,
            )
        }.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): FileEntry? {
        val segs = try { validateSegments(path) } catch (_: IOException) { return null }
        if (segs.isEmpty()) {
            return FileEntry(FsPath.PHONE_ROOT, "Pixel 9", isDir = true, size = 0L, modified = System.currentTimeMillis(), subtitle = "Internal Storage")
        }
        val normPath = "${FsPath.PHONE_ROOT}${segs.joinToString("/")}"
        val realDir = resolveRealDirectory()
        if (realDir != null) {
            val f = resolveTarget(segs, realDir)
            if (!f.exists() && !FileInfo.isSymlink(f.path)) return null
            return FileEntry(normPath, f.name, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified(), isLink = FileInfo.isSymlink(f.path), subtitle = null)
        }
        val top = segs.first()
        if (segs.size == 1 && standardFolders.any { it.equals(top, ignoreCase = true) }) {
            val canonicalTop = standardFolders.first { it.equals(top, ignoreCase = true) }
            return FileEntry(normPath, canonicalTop, isDir = true, size = 0L, modified = 1744848000000L, subtitle = null)
        }
        val hostFile = mapFolderToHost(segs)
        if (!hostFile.exists() && !FileInfo.isSymlink(hostFile.path)) return null
        return FileEntry(normPath, hostFile.name, hostFile.isDirectory, if (hostFile.isDirectory) 0L else hostFile.length(), hostFile.lastModified(), isLink = FileInfo.isSymlink(hostFile.path), subtitle = null)
    }

    override fun openInput(path: String): InputStream {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot open root as file")
        val file = resolveTarget(segs, resolveRealDirectory())
        return file.inputStream().buffered(64 * 1024)
    }

    override fun openOutput(path: String): OutputStream {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot write to phone root")
        val file = resolveTarget(segs, resolveRealDirectory())
        file.parentFile?.mkdirs()
        return file.outputStream().buffered(64 * 1024)
    }

    override fun mkdir(path: String) {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot create phone root")
        val file = resolveTarget(segs, resolveRealDirectory())
        if (!file.mkdirs() && !file.isDirectory) throw IOException("Couldn't create folder ${file.name}")
    }

    override fun createFile(path: String) {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot create file at phone root")
        val file = resolveTarget(segs, resolveRealDirectory())
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) throw IOException("Couldn't create file ${file.name}")
    }

    override fun delete(path: String) {
        val segs = validateSegments(path)
        val realDir = resolveRealDirectory()
        if (segs.isEmpty() || (segs.size == 1 && realDir == null && standardFolders.any { it.equals(segs[0], ignoreCase = true) })) {
            throw IOException("Cannot delete system phone folder")
        }
        val file = resolveTarget(segs, realDir)
        if (!FileInfo.deleteTree(file)) throw IOException("Couldn't delete ${file.name}")
    }

    override fun rename(path: String, newName: String): String {
        FsPath.nameError(newName)?.let { throw IOException(it) }
        val segs = validateSegments(path)
        val realDir = resolveRealDirectory()
        if (segs.isEmpty() || (segs.size == 1 && realDir == null && standardFolders.any { it.equals(segs[0], ignoreCase = true) })) {
            throw IOException("Cannot rename system phone folder")
        }
        val file = resolveTarget(segs, realDir)
        val dst = File(file.parentFile, newName)
        if (!file.renameTo(dst)) throw IOException("Couldn't rename ${file.name}")
        val parentSegs = segs.dropLast(1)
        return if (parentSegs.isEmpty()) "${FsPath.PHONE_ROOT}$newName" else "${FsPath.PHONE_ROOT}${parentSegs.joinToString("/")}/$newName"
    }
}
