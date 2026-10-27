// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.text.Normalizer

/** Case-insensitive comparison that orders embedded numbers numerically (file2 < file10). */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length.compareTo(nb.length)
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

fun sortEntries(
    entries: List<FileEntry>,
    key: SortKey,
    ascending: Boolean,
    foldersFirst: Boolean = true,
): List<FileEntry> {
    val base: Comparator<FileEntry> = when (key) {
        SortKey.Name -> Comparator { x, y -> naturalCompare(x.name, y.name) }
        SortKey.Modified -> Comparator { x, y -> x.modified.compareTo(y.modified) }
        SortKey.Size -> Comparator { x, y -> x.size.compareTo(y.size) }
        SortKey.Type -> Comparator { x, y ->
            val c = x.typeLabel.compareTo(y.typeLabel, ignoreCase = true)
            if (c != 0) c else naturalCompare(x.name, y.name)
        }
        SortKey.Tags -> {
            val marks = TagIndex.marks
            Comparator { x, y ->
                val c = marks.rank(x.path).compareTo(marks.rank(y.path))
                if (c != 0) c else naturalCompare(x.name, y.name)
            }
        }
        SortKey.Permissions -> Comparator { x, y ->
            val c = (x.mode and 0xFFF).compareTo(y.mode and 0xFFF)
            if (c != 0) c else naturalCompare(x.name, y.name)
        }
    }
    val directed = if (ascending) base else base.reversed()
    val cmp = if (foldersFirst) {
        Comparator<FileEntry> { x, y ->
            if (x.isDir != y.isDir) if (x.isDir) -1 else 1 else directed.compare(x, y)
        }
    } else directed
    return entries.sortedWith(cmp)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = -1
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return if (v >= 100 || v == v.toLong().toDouble()) "${v.toLong()} ${units[u]}" else "%.1f %s".format(v, units[u])
}

/** Strips accents for forgiving search matching. */
fun foldForSearch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
