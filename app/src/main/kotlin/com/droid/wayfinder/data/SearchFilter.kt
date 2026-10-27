// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import java.util.Calendar
import java.util.TimeZone

enum class SearchScope(val label: String) { Folder("This folder"), Everywhere("Everywhere") }

enum class SearchCategory(val label: String) {
    All("All"), Images("Images"), Videos("Videos"), Audio("Audio"), Docs("Docs"), Archives("Archives"), Code("Code"), Apks("APKs");

    fun matches(e: FileEntry): Boolean = when (this) {
        All -> true
        Images -> e.kind == FileKind.Image
        Videos -> e.kind == FileKind.Video
        Audio -> e.kind == FileKind.Audio
        Docs -> e.kind in setOf(FileKind.Pdf, FileKind.Document, FileKind.Sheet, FileKind.Slides, FileKind.Text)
        Archives -> e.kind == FileKind.Archive
        Code -> e.kind == FileKind.Code
        Apks -> e.kind == FileKind.Apk
    }
}

enum class ModifiedRange(val label: String) {
    Any("Any time"), Today("Today"), Week("Last 7 days"), Month("Last 30 days"), Year("This year");

    /** Earliest modification time (ms) that passes, or null for no limit. */
    fun since(now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val day = 24L * 60 * 60 * 1000
        return when (this) {
            Any -> null
            Week -> now - 7 * day
            Month -> now - 30 * day
            Today, Year -> Calendar.getInstance(tz).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (this@ModifiedRange == Year) set(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }
    }
}

/** Filters applied to search results (scope decides where the search walks, the rest filter matches). */
data class SearchFilter(
    val scope: SearchScope = SearchScope.Folder,
    val category: SearchCategory = SearchCategory.All,
    val modified: ModifiedRange = ModifiedRange.Any,
) {
    val isDefault: Boolean get() = this == SearchFilter()

    /** True when [e] (whose name already matched the query) passes the category and date filters. */
    fun matches(e: FileEntry, now: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (category != SearchCategory.All && e.isDir) return false
        if (!category.matches(e)) return false
        val since = modified.since(now, tz) ?: return true
        return e.modified >= since
    }
}
