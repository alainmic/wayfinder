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
 *
 * In production (`rootOverride == null`), this bridges to real connected phone roots provided by
 * `QuickAccessDocumentsProvider` (`com.google.ambient.streaming.multidevicesync.quickaccess`),
 * `MtpDocumentsProvider` (`com.android.mtp.documents`), or a secondary mounted phone `StorageVolume`.
 * It NEVER maps `phone://` to the local host device's `/storage/emulated/0`.
 *
 * In unit tests (`rootOverride != null`), it operates inside the isolated test sandbox directory.
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

    /**
     * Resolves a secondary mounted phone storage volume (`!isPrimary`), never returning the local
     * device's primary/emulated storage.
     */
    fun resolveRealDirectory(): File? {
        if (rootOverride != null) return null
        val ctx = context ?: return null
        // 1. Check StorageManager secondary storageVolumes for a phone volume
        runCatching {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                if (v.isPrimary) continue
                val desc = v.getDescription(ctx).orEmpty()
                if (PhoneDetector.isPhoneMount(desc) || PhoneDetector.isPhoneMount(v.mediaStoreVolumeName.orEmpty())) {
                    val d = v.directory
                    if (d != null && d.exists() && d.canRead() && (d.listFiles()?.isNotEmpty() == true)) {
                        return d
                    }
                }
            }
        }
        // 2. Check /storage mounts (excluding primary/emulated storage so host storage is never mistaken for a phone)
        runCatching {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "emulated" && f.name != "self" && PhoneDetector.isPhoneMount(f.name)) {
                    if (f.listFiles()?.isNotEmpty() == true) return f
                }
            }
        }
        return null
    }

    private fun discoverProviderRoot(): DiscoveredRoot? {
        if (rootOverride != null) return null
        val ctx = context ?: return null
        return runCatching {
            val roots = SystemDocumentsBridge.discoverRoots(ctx)
            roots.firstOrNull { r ->
                r.authority == PhoneDetector.QUICK_ACCESS_AUTHORITY || r.authority == PhoneDetector.MTP_AUTHORITY
            } ?: roots.firstOrNull { r ->
                PhoneDetector.isPhoneAuthority(r.authority)
            }
        }.getOrNull()
    }

    private fun toDocPath(root: DiscoveredRoot, segs: List<String>): String {
        val baseDocId = root.documentId.trimEnd('/')
        val fullDocId = if (segs.isEmpty()) baseDocId else "$baseDocId/${segs.joinToString("/")}"
        return "doc://${root.authority}/$fullDocId"
    }

    private fun resolveSandboxDir(segs: List<String>): File {
        val base = rootOverride ?: throw IOException(
            "Phone storage is not connected. Connect your phone via Cross-Device Services (Phone Hub) or USB File Transfer (MTP).",
        )
        var cur = base
        for (s in segs) {
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
        return resolveSandboxDir(segs)
    }

    override fun list(path: String): List<FileEntry> {
        val segs = validateSegments(path)
        val sub = segs.joinToString("/")

        discoverProviderRoot()?.let { root ->
            val ctx = context ?: return emptyList()
            return DocFileSystem(ctx).list(toDocPath(root, segs))
        }

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

        // When no real phone provider or volume is mounted in production, return an empty root listing
        // (so the UI shows the Phone connection onboarding screen) and never expose local laptop storage.
        if (rootOverride == null) {
            if (segs.isEmpty()) return emptyList()
            throw IOException("Phone storage is not connected. Connect your phone via Cross-Device Services (Phone Hub) or USB File Transfer (MTP).")
        }

        // Sandbox mode (used by unit tests with explicit rootOverride)
        if (segs.isEmpty()) {
            val baseTime = 1744848000000L
            return standardFolders.map { name ->
                val host = resolveSandboxDir(listOf(name))
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

        val hostDir = resolveSandboxDir(segs)
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
        discoverProviderRoot()?.let { root ->
            val ctx = context ?: return null
            return DocFileSystem(ctx).stat(toDocPath(root, segs))
        }
        if (segs.isEmpty()) {
            return FileEntry(FsPath.PHONE_ROOT, "Connected Phone", isDir = true, size = 0L, modified = System.currentTimeMillis(), subtitle = "Internal Storage")
        }
        val normPath = "${FsPath.PHONE_ROOT}${segs.joinToString("/")}"
        val realDir = resolveRealDirectory()
        if (realDir != null) {
            val f = resolveTarget(segs, realDir)
            if (!f.exists() && !FileInfo.isSymlink(f.path)) return null
            return FileEntry(normPath, f.name, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified(), isLink = FileInfo.isSymlink(f.path), subtitle = null)
        }
        if (rootOverride == null) return null
        val top = segs.first()
        if (segs.size == 1 && standardFolders.any { it.equals(top, ignoreCase = true) }) {
            val canonicalTop = standardFolders.first { it.equals(top, ignoreCase = true) }
            return FileEntry(normPath, canonicalTop, isDir = true, size = 0L, modified = 1744848000000L, subtitle = null)
        }
        val hostFile = resolveSandboxDir(segs)
        if (!hostFile.exists() && !FileInfo.isSymlink(hostFile.path)) return null
        return FileEntry(normPath, hostFile.name, hostFile.isDirectory, if (hostFile.isDirectory) 0L else hostFile.length(), hostFile.lastModified(), isLink = FileInfo.isSymlink(hostFile.path), subtitle = null)
    }

    override fun openInput(path: String): InputStream {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot open root as file")
        discoverProviderRoot()?.let { root ->
            val ctx = context ?: throw IOException("Context unavailable")
            return DocFileSystem(ctx).openInput(toDocPath(root, segs))
        }
        val file = resolveTarget(segs, resolveRealDirectory())
        return file.inputStream().buffered(64 * 1024)
    }

    override fun openOutput(path: String): OutputStream {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot write to phone root")
        discoverProviderRoot()?.let { root ->
            val ctx = context ?: throw IOException("Context unavailable")
            return DocFileSystem(ctx).openOutput(toDocPath(root, segs))
        }
        val file = resolveTarget(segs, resolveRealDirectory())
        file.parentFile?.mkdirs()
        return file.outputStream().buffered(64 * 1024)
    }

    override fun mkdir(path: String) {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot create phone root")
        discoverProviderRoot()?.let { root ->
            val ctx = context ?: throw IOException("Context unavailable")
            DocFileSystem(ctx).mkdir(toDocPath(root, segs))
            return
        }
        val file = resolveTarget(segs, resolveRealDirectory())
        if (!file.mkdirs() && !file.isDirectory) throw IOException("Couldn't create folder ${file.name}")
    }

    override fun createFile(path: String) {
        val segs = validateSegments(path)
        if (segs.isEmpty()) throw IOException("Cannot create file at phone root")
        discoverProviderRoot()?.let { root ->
            val ctx = context ?: throw IOException("Context unavailable")
            DocFileSystem(ctx).createFile(toDocPath(root, segs))
            return
        }
        val file = resolveTarget(segs, resolveRealDirectory())
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) throw IOException("Couldn't create file ${file.name}")
    }

    override fun delete(path: String) {
        val segs = validateSegments(path)
        discoverProviderRoot()?.let { root ->
            if (segs.isEmpty()) throw IOException("Cannot delete phone root")
            val ctx = context ?: throw IOException("Context unavailable")
            DocFileSystem(ctx).delete(toDocPath(root, segs))
            return
        }
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
        discoverProviderRoot()?.let { root ->
            if (segs.isEmpty()) throw IOException("Cannot rename phone root")
            val ctx = context ?: throw IOException("Context unavailable")
            return DocFileSystem(ctx).rename(toDocPath(root, segs), newName)
        }
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
