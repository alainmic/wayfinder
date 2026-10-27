// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

/**
 * Stars and color tags, keyed by full path. Immutable: every change returns a new instance so it can live in
 * the UI state. Persisted as plain text lines (`S\t<path>` / `T\t<Tag>\t<path>`).
 */
data class Marks(
    val starred: Set<String> = emptySet(),
    val tags: Map<String, Set<ColorTag>> = emptyMap(),
) {
    fun isStarred(path: String) = path in starred
    fun tagsOf(path: String): Set<ColorTag> = tags[path].orEmpty()
    fun pathsWith(tag: ColorTag): List<String> = tags.filterValues { tag in it }.keys.sorted()

    /** Stars all [paths] unless every one is already starred, in which case they are all unstarred. */
    fun toggleStar(paths: Collection<String>): Marks {
        if (paths.isEmpty()) return this
        return if (paths.all { it in starred }) copy(starred = starred - paths.toSet()) else copy(starred = starred + paths)
    }

    /** Adds [tag] to all [paths] unless all already have it, in which case it is removed from all of them. */
    fun toggleTag(paths: Collection<String>, tag: ColorTag): Marks {
        if (paths.isEmpty()) return this
        val remove = paths.all { tag in tagsOf(it) }
        val out = tags.toMutableMap()
        for (p in paths) {
            val next = if (remove) tagsOf(p) - tag else tagsOf(p) + tag
            if (next.isEmpty()) out.remove(p) else out[p] = next
        }
        return copy(tags = out)
    }

    /** Re-keys marks after [from] was renamed/moved to [to] (also for everything below a folder). */
    fun renamed(from: String, to: String): Marks {
        if (from == to) return this
        fun map(p: String): String = when {
            p == from -> to
            p.startsWith("$from/") -> to + p.substring(from.length)
            else -> p
        }
        val touches = starred.any { it == from || it.startsWith("$from/") } || tags.keys.any { it == from || it.startsWith("$from/") }
        if (!touches) return this
        return Marks(starred.mapTo(LinkedHashSet(), ::map), tags.mapKeys { map(it.key) })
    }

    /** Sort key: index of the "most important" tag (untagged items last). */
    fun rank(path: String): Int = tagsOf(path).minOfOrNull { it.ordinal } ?: Int.MAX_VALUE

    fun serialize(): String = buildString {
        // A line break inside a path (legal in Linux file names) would split the record and could forge another
        // one, so such paths are not persisted.
        fun ok(p: String) = p.isNotEmpty() && p.none { it == '\n' || it == '\r' }
        starred.filter(::ok).sorted().forEach { append("S\t").append(it).append('\n') }
        tags.keys.filter(::ok).sorted().forEach { p -> tags[p].orEmpty().sortedBy { it.ordinal }.forEach { append("T\t").append(it.name).append('\t').append(p).append('\n') } }
    }

    companion object {
        fun parse(text: String?): Marks {
            if (text.isNullOrBlank()) return Marks()
            val stars = LinkedHashSet<String>()
            val tags = LinkedHashMap<String, Set<ColorTag>>()
            text.split('\n').forEach { raw ->
                val line = raw.removeSuffix("\r")
                when {
                    line.startsWith("S\t") -> line.substring(2).takeIf { it.isNotEmpty() }?.let { stars += it }
                    line.startsWith("T\t") -> {
                        val parts = line.split('\t', limit = 3)
                        if (parts.size == 3 && parts[2].isNotEmpty()) ColorTag.fromName(parts[1])?.let { t -> tags[parts[2]] = tags[parts[2]].orEmpty() + t }
                    }
                }
            }
            return Marks(stars, tags)
        }
    }
}

/** Process-wide snapshot used for sorting by tag (shared by every window, kept in sync by the view models). */
object TagIndex {
    @Volatile var marks: Marks = Marks()
}
