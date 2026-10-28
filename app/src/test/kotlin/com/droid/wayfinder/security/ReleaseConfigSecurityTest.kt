// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.security

import com.droid.wayfinder.data.DriveRestFileSystem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class ReleaseConfigSecurityTest {
    @Test
    fun driveRestAuthFailureDoesNotLeakDeveloperKeySha1() {
        val fs = DriveRestFileSystem(context = null)
        try {
            fs.getToken("user@example.com")
            fail("Expected IOException when AccountManager is unavailable")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(msg.contains("user@example.com"))
            assertFalse("Error message must not contain hardcoded developer SHA-1", msg.contains("43:ED:0A:A8"))
        }
    }

    @Test
    fun gradleBuildScriptDoesNotSignReleaseWithDebugKey() {
        val gradleFile = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).firstOrNull { it.isFile } ?: return
        val content = gradleFile.readText()
        assertFalse(
            "Release buildType must not use debug signingConfig",
            content.contains("signingConfigs.getByName(\"debug\")"),
        )
        assertTrue(
            "Release buildType must enable R8 minification",
            content.contains("isMinifyEnabled = true"),
        )
        assertTrue(
            "Release buildType must enable resource shrinking",
            content.contains("isShrinkResources = true"),
        )
    }
}
