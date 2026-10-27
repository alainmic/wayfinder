// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.io.File
import java.util.PriorityQueue

/** Bounded breadth-first walk that finds the most recently modified files under some roots. */
object RecentFiles {
    /**
     * Returns up to [limit] files modified at or after [since], newest first. Hidden folders, `Android/` at a
     * root and anything [skip] rejects are not entered; the walk stops after [maxDirs] folders or when
     * [isActive] turns false.
     */
    fun scan(
        roots: List<String>,
        since: Long,
        limit: Int = 200,
        maxDirs: Int = 20_000,
        skip: (File) -> Boolean = { false },
        isActive: () -> Boolean = { true },
    ): List<FileEntry> {
        val best = PriorityQueue<FileEntry>(compareBy { it.modified })
        val queue = ArrayDeque<File>()
        roots.forEach { queue.add(File(it)) }
        val rootSet = roots.map { File(it).path }.toSet()
        var dirs = 0
        while (queue.isNotEmpty() && dirs < maxDirs && isActive()) {
            val dir = queue.removeFirst()
            dirs++
            val kids = dir.listFiles() ?: continue
            for (f in kids) {
                val name = f.name
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (dir.path in rootSet && name == "Android") continue
                    if (FileInfo.isSymlink(f.path)) continue
                    if (!skip(f)) queue.add(f)
                    continue
                }
                val m = f.lastModified()
                if (m < since) continue
                if (best.size >= limit && m <= best.peek()!!.modified) continue
                best.add(FileEntry(f.path, name, false, size = f.length(), modified = m))
                if (best.size > limit) best.poll()
            }
        }
        return best.sortedByDescending { it.modified }
    }
}
