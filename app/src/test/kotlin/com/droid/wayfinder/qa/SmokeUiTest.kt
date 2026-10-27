// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droid.wayfinder.ui.AppRoot
import com.droid.wayfinder.ui.theme.WayfinderTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1360dp-h860dp-land-xhdpi")
class SmokeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var h: VmHarness? = null

    @After fun tearDown() { h?.close() }

    @Test fun appRootRendersFolderListing() {
        val hh = VmHarness().also { h = it }
        hh.file("hello.txt")
        hh.navigate(hh.root.path)
        assertTrue(hh.names().toString(), "hello.txt" in hh.names())
        rule.setContent { WayfinderTheme(false) { AppRoot(hh.vm) } }
        rule.waitForIdle()
        rule.onNodeWithText("hello.txt").assertIsDisplayed()
    }
}
