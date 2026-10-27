// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.File
import java.io.IOException

data class Trashed(val original: String, val trashed: String)

/**
 * Recycle bin for local files. Each deleted item lives in `<root>/<id>/<name>` with the
 * original location recorded in `<root>/<id>/.origin`.
 *
 * The bin lives on shared storage, so its contents are treated as untrusted: `.origin` must name an absolute
 * local path outside the bin, permanent deletion is confined to the bin, and nothing here follows symbolic links.
 */
class TrashManager(private val root: File) {

    fun isInTrash(path: String) = path == FsPath.TRASH_ROOT || FsPath.isWithin(root.path, path)

    /** The `<root>/<id>` folder holding [trashedPath], or null when it isn't an item directly inside the bin. */
    private fun slotOf(trashedPath: String): File? {
        val dir = File(trashedPath).parentFile ?: return null
        if (dir.parentFile?.path != root.path) return null
        if (!FsPath.isSafeName(dir.name) || !FsPath.isSafeName(File(trashedPath).name)) return null
        return dir
    }

    /** Creates a fresh, unique slot (two deletions in the same millisecond must not share one). */
    private fun newSlot(i: Int): File {
        val stamp = System.currentTimeMillis()
        var n = 0
        while (true) {
            val d = File(root, if (n == 0) "${stamp}_$i" else "${stamp}_${i}_$n")
            if (d.mkdir()) return d
            if (!root.isDirectory) throw IOException("Couldn't create the Trash folder")
            if (++n > 10_000) throw IOException("Couldn't create a Trash slot")
        }
    }

    fun moveToTrash(paths: List<String>): List<Trashed> {
        root.mkdirs()
        val out = mutableListOf<Trashed>()
        paths.forEachIndexed { i, p ->
            val src = File(p)
            if (isInTrash(src.path) || FsPath.isWithin(src.path, root.path)) throw IOException("Can't move the Trash into itself")
            if (!src.exists() && !FileInfo.isSymlink(src.path)) throw IOException("${src.name} no longer exists")
            val dir = newSlot(i)
            val dst = File(dir, src.name)
            File(dir, ".origin").writeText(src.path)
            if (!src.renameTo(dst)) {
                try {
                    FileInfo.copyTree(src, dst)
                } catch (e: Exception) {
                    FileInfo.deleteTree(dir)
                    throw IOException("Couldn't move ${src.name} to Trash: ${e.message}", e)
                }
                if (!FileInfo.deleteTree(src)) throw IOException("Couldn't delete ${src.name}")
            }
            out += Trashed(src.path, dst.path)
        }
        return out
    }

    fun list(): List<FileEntry> {
        val dirs = root.listFiles { f -> f.isDirectory && !FileInfo.isSymlink(f.path) } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val item = d.listFiles { f -> f.name != ".origin" }?.firstOrNull() ?: return@mapNotNull null
            val origin = readOrigin(d)
            FileEntry(
                path = item.path, name = item.name, isDir = item.isDirectory,
                size = if (item.isDirectory) 0 else item.length(), modified = d.lastModified(),
                isHidden = false, subtitle = origin?.let { File(it).parent }, isLink = FileInfo.isSymlink(item.path),
            )
        }
    }

    /** The recorded original location, or null when missing or not a plausible absolute local path. */
    private fun readOrigin(slot: File): String? {
        val f = File(slot, ".origin")
        if (!f.isFile || f.length() > 64 * 1024) return null
        val raw = runCatching { f.readText() }.getOrNull()?.trim() ?: return null
        return validOrigin(raw, root.path)
    }

    fun originalOf(trashedPath: String): String? {
        val dir = slotOf(trashedPath) ?: return null
        return readOrigin(dir)
    }

    /** Returns the restored path. */
    fun restore(trashedPath: String): String {
        val src = File(trashedPath)
        val slot = slotOf(trashedPath) ?: throw IOException("Not an item in the Trash")
        val origin = originalOf(trashedPath) ?: throw IOException("Unknown original location")
        var dst = File(origin)
        dst.parentFile?.mkdirs()
        if (dst.exists() || FileInfo.isSymlink(dst.path)) {
            val base = dst.nameWithoutExtension
            val ext = dst.extension.let { if (it.isEmpty()) "" else ".$it" }
            var n = 1
            while (File(dst.parentFile, "$base (restored $n)$ext").let { it.exists() || FileInfo.isSymlink(it.path) }) n++
            dst = File(dst.parentFile, "$base (restored $n)$ext")
        }
        if (!src.renameTo(dst)) {
            try {
                FileInfo.copyTree(src, dst)
            } catch (e: Exception) {
                FileInfo.deleteTree(dst)
                throw IOException("Couldn't restore ${src.name}: ${e.message}", e)
            }
            FileInfo.deleteTree(src)
        }
        FileInfo.deleteTree(slot)
        return dst.path
    }

    /** Deletes an item of the bin for good; paths outside the bin are refused. */
    fun deletePermanently(trashedPath: String) {
        val slot = slotOf(trashedPath)
        val target = when {
            slot != null -> slot
            // Something inside a trashed folder (browsed into from the Trash view).
            FsPath.isWithin(root.path, trashedPath) && FsPath.normalize(trashedPath) != FsPath.normalize(root.path) &&
                trashedPath.split('/').none { it == ".." } -> File(trashedPath)
            else -> throw IOException("Not an item in the Trash")
        }
        if (!FileInfo.deleteTree(target)) throw IOException("Couldn't delete ${File(trashedPath).name}")
    }

    fun empty() {
        // Removes links found in the bin, never what they point to.
        FileInfo.deleteTree(root)
    }

    companion object {
        /**
         * Validates a `.origin` record: a single-line absolute, normalised local path (no `.`/`..` segments, no NUL)
         * that is not inside [trashRoot]. Returns null when it doesn't qualify.
         */
        fun validOrigin(raw: String, trashRoot: String): String? {
            if (raw.isEmpty() || raw.length > 4096 || !raw.startsWith("/")) return null
            if (raw.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
            val segs = raw.split('/').filter { it.isNotEmpty() }
            if (segs.isEmpty() || segs.any { it == "." || it == ".." }) return null
            val p = "/" + segs.joinToString("/")
            if (FsPath.isZip(p) || FsPath.isWithin(trashRoot, p)) return null
            return p
        }
    }
}
