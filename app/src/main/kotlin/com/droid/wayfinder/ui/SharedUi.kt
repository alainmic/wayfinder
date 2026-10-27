// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import android.content.Context
import com.droid.wayfinder.data.Marks
import com.droid.wayfinder.data.Settings
import com.droid.wayfinder.data.TagIndex
import com.droid.wayfinder.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Process-wide preferences shared by every window: stars/color tags and the theme. Keeping a single source
 * avoids two windows overwriting each other's saved marks.
 */
object SharedUi {
    private var settings: Settings? = null
    private val _marks = MutableStateFlow(Marks())
    private val _theme = MutableStateFlow(ThemeMode.System)
    val marks: StateFlow<Marks> = _marks.asStateFlow()
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (settings != null) return
        val s = Settings(context.applicationContext)
        settings = s
        _marks.value = Marks.parse(s.marks)
        TagIndex.marks = _marks.value
        _theme.value = s.themeMode
    }

    fun updateMarks(f: (Marks) -> Marks) {
        val m = _marks.updateAndGet(f)
        TagIndex.marks = m
        settings?.marks = m.serialize()
    }

    fun setTheme(t: ThemeMode) {
        _theme.value = t
        settings?.themeMode = t
    }
}
