// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import com.droid.wayfinder.data.FileEntry
import com.droid.wayfinder.data.SearchFilter
import com.droid.wayfinder.data.SortKey
import com.droid.wayfinder.data.ViewMode
import com.droid.wayfinder.data.sortEntries

data class TabError(val message: String, val authHost: String? = null)

/** Immutable state of one browser tab. All transitions are pure functions so they are unit-testable. */
data class TabState(
    val id: Long,
    val path: String,
    val back: List<String> = emptyList(),
    val forward: List<String> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: TabError? = null,
    val selection: Set<String> = emptySet(),
    val anchor: String? = null,
    val focused: String? = null,
    val viewMode: ViewMode = ViewMode.Details,
    val sortKey: SortKey = SortKey.Name,
    val sortAsc: Boolean = true,
    val query: String = "",
    val searchResults: List<FileEntry>? = null,
    val searching: Boolean = false,
    val searchFilter: SearchFilter = SearchFilter(),
) {
    val canGoBack get() = back.isNotEmpty()
    val canGoForward get() = forward.isNotEmpty()

    fun visible(showHidden: Boolean): List<FileEntry> =
        sortEntries((searchResults ?: entries).filter { showHidden || !it.isHidden }, sortKey, sortAsc)

    fun navigatedTo(newPath: String): TabState =
        if (newPath == path) copy(selection = emptySet(), anchor = null, focused = null, query = "", searchResults = null)
        else copy(
            path = newPath, back = back + path, forward = emptyList(), entries = emptyList(),
            selection = emptySet(), anchor = null, focused = null, query = "", searchResults = null, error = null,
        )

    fun goBack(): TabState =
        if (back.isEmpty()) this
        else copy(
            path = back.last(), back = back.dropLast(1), forward = forward + path, entries = emptyList(),
            selection = emptySet(), anchor = null, focused = null, query = "", searchResults = null, error = null,
        )

    fun goForward(): TabState =
        if (forward.isEmpty()) this
        else copy(
            path = forward.last(), forward = forward.dropLast(1), back = back + path, entries = emptyList(),
            selection = emptySet(), anchor = null, focused = null, query = "", searchResults = null, error = null,
        )

    /** Windows/Finder-style click selection. */
    fun clicked(target: String, ctrl: Boolean, shift: Boolean, showHidden: Boolean): TabState {
        val list = visible(showHidden).map { it.path }
        return when {
            shift && anchor != null && anchor in list -> {
                val a = list.indexOf(anchor)
                val b = list.indexOf(target)
                val range = list.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
                copy(selection = if (ctrl) selection + range else range, focused = target)
            }
            ctrl -> copy(
                selection = if (target in selection) selection - target else selection + target,
                anchor = target, focused = target,
            )
            else -> copy(selection = setOf(target), anchor = target, focused = target)
        }
    }

    /** Keyboard navigation: move focus by [delta] items; optionally extend the selection. */
    fun moveFocus(delta: Int, extend: Boolean, keepSelection: Boolean, showHidden: Boolean): TabState {
        val list = visible(showHidden).map { it.path }
        if (list.isEmpty()) return this
        val cur = focused?.let { list.indexOf(it) } ?: -1
        val next = (if (cur < 0) (if (delta > 0) 0 else list.lastIndex) else cur + delta).coerceIn(0, list.lastIndex)
        val t = list[next]
        return when {
            extend -> {
                val anc = anchor ?: focused ?: t
                val a = list.indexOf(anc).coerceAtLeast(0)
                copy(selection = list.subList(minOf(a, next), maxOf(a, next) + 1).toSet(), anchor = anc, focused = t)
            }
            keepSelection -> copy(focused = t)
            else -> copy(selection = setOf(t), anchor = t, focused = t)
        }
    }

    fun selectAll(showHidden: Boolean): TabState {
        val list = visible(showHidden).map { it.path }
        return copy(selection = list.toSet(), anchor = list.firstOrNull(), focused = focused ?: list.firstOrNull())
    }

    fun invertSelection(showHidden: Boolean): TabState {
        val list = visible(showHidden).map { it.path }.toSet()
        return copy(selection = list - selection)
    }

    fun selectedEntries(showHidden: Boolean): List<FileEntry> = visible(showHidden).filter { it.path in selection }
}
