// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

/** What the app that launched us with GET_CONTENT / OPEN_DOCUMENT asked for. */
data class PickerRequest(val allowMultiple: Boolean = false, val mimeTypes: List<String> = emptyList()) {
    /** Human-readable summary of [mimeTypes] ("images", "PDF documents", ...), or null for any file. */
    val description: String?
        get() = mimeTypes.filter { it != "*/*" }.takeIf { it.isNotEmpty() }?.joinToString(", ") { m ->
            when {
                m == "application/pdf" -> "PDF documents"
                m.endsWith("/*") -> m.substringBefore('/') + " files"
                else -> m
            }
        }
}

/** Light MIME matching for picker mode (pure). */
object MimeFilter {
    /** True when [mime] satisfies any of [patterns] ("any", "image/" + "*", "application/pdf"); an empty list accepts all. */
    fun matches(mime: String?, patterns: List<String>): Boolean {
        val ps = patterns.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (ps.isEmpty() || "*/*" in ps || "*" in ps) return true
        val m = mime?.lowercase() ?: return false
        return ps.any { p ->
            if (p.endsWith("/*")) m.substringBefore('/') == p.substringBefore('/') else m == p
        }
    }
}
