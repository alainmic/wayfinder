// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

/** One way of computing new names for a batch of items. */
sealed interface RenameRule {
    data class FindReplace(val find: String, val replace: String, val matchCase: Boolean = true) : RenameRule
    /** [suffix] is inserted before the extension. */
    data class PrefixSuffix(val prefix: String, val suffix: String) : RenameRule
    /** "base 1.ext", "base 2.ext", … numbers are zero-padded to the widest number. Extensions are kept. */
    data class Numbered(val base: String, val start: Int = 1) : RenameRule
}

data class RenameItem(val name: String, val isDir: Boolean)

data class RenamePlan(val old: String, val new: String, val conflict: String? = null) {
    val changed get() = old != new
}

/** Splits "photo.tar.gz" → ("photo.tar", ".gz"); folders and dot-files have no extension. */
fun splitExtension(name: String, isDir: Boolean): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    return if (isDir || dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
}

fun applyRule(rule: RenameRule, item: RenameItem, index: Int, count: Int): String = when (rule) {
    is RenameRule.FindReplace ->
        if (rule.find.isEmpty()) item.name else item.name.replace(rule.find, rule.replace, ignoreCase = !rule.matchCase)
    is RenameRule.PrefixSuffix -> {
        val (base, ext) = splitExtension(item.name, item.isDir)
        rule.prefix + base + rule.suffix + ext
    }
    is RenameRule.Numbered -> {
        val (_, ext) = splitExtension(item.name, item.isDir)
        val width = (rule.start + count - 1).coerceAtLeast(rule.start).toString().length
        val n = (rule.start + index).toString().padStart(width, '0')
        (if (rule.base.isEmpty()) n else "${rule.base} $n") + ext
    }
}

/**
 * Computes the new name of every item and flags conflicts: invalid names, duplicates within the batch,
 * and clashes with [existing] names in the folder that are not themselves being renamed.
 * Comparison is case-insensitive because Android shared storage is.
 */
fun planBatchRename(items: List<RenameItem>, rule: RenameRule, existing: Collection<String>): List<RenamePlan> {
    val news = items.mapIndexed { i, it -> applyRule(rule, it, i, items.size) }
    val renaming = items.mapTo(HashSet()) { it.name.lowercase() }
    val others = existing.map { it.lowercase() }.filterTo(HashSet()) { it !in renaming }
    val counts = news.groupingBy { it.lowercase() }.eachCount()
    return items.mapIndexed { i, it ->
        val n = news[i]
        val conflict = when {
            n.isBlank() -> "Name can't be empty"
            n.contains('/') || n.contains('\\') -> "Names can't contain slashes"
            n == "." || n == ".." -> "Invalid name"
            // NUL / control characters, over-long names.
            FsPath.nameError(n) != null -> FsPath.nameError(n)
            (counts[n.lowercase()] ?: 0) > 1 -> "Duplicate name"
            n.lowercase() in others -> "Already exists"
            else -> null
        }
        RenamePlan(it.name, n, conflict)
    }
}

/**
 * "name copy.ext", then "name copy 2.ext", … — the first name for which [exists] is false. Gives up with an
 * [java.io.IOException] after [MAX_NAME_ATTEMPTS] candidates (e.g. a server claiming every name exists).
 */
fun duplicateName(name: String, isDir: Boolean, exists: (String) -> Boolean): String {
    val (base, ext) = splitExtension(name, isDir)
    var candidate = "$base copy$ext"
    var n = 2
    while (exists(candidate)) {
        if (n > MAX_NAME_ATTEMPTS) throw java.io.IOException("Couldn't find a free name for $name")
        candidate = "$base copy ${n++}$ext"
    }
    return candidate
}

/** Upper bound of candidates tried by [duplicateName] / `FileOperations.uniqueName`. */
const val MAX_NAME_ATTEMPTS = 10_000

/**
 * Fuzzy subsequence score of [query] in [text] (case-insensitive); null when not all query characters
 * appear in order. Higher is better: contiguous runs, word starts and prefix matches score extra.
 */
fun fuzzyScore(query: String, text: String): Int? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return 0
    val t = text.lowercase()
    val direct = t.indexOf(q)
    if (direct >= 0) return 1000 - direct * 2 + (if (direct == 0) 200 else 0) - (t.length - q.length)
    var score = 0
    var ti = 0
    var prev = -2
    for (c in q) {
        if (c == ' ') continue
        var found = -1
        while (ti < t.length) { if (t[ti] == c) { found = ti; break }; ti++ }
        if (found < 0) return null
        score += 10
        if (found == prev + 1) score += 15
        if (found == 0 || !t[found - 1].isLetterOrDigit()) score += 20
        prev = found; ti = found + 1
    }
    return score - t.length / 4
}
