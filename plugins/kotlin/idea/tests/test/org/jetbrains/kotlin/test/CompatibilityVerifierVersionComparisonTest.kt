// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.test

import com.intellij.testFramework.LightPlatformTestCase
import org.jetbrains.kotlin.idea.*
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinIdePlugin
import org.jetbrains.kotlin.idea.test.util.ignored
import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.runner.RunWith

@RunWith(JUnit38ClassRunner::class)
class CompatibilityVerifierVersionComparisonTest : LightPlatformTestCase() {
    fun testValidVersion() {
        testVersion("203-1.4.20-dev-4575-IJ1234.45-1", "1.4.20", "dev", isAndroidStudio = false, "203.1234", "45")
        testVersion("211-1.4.30-release-AS193", "1.4.30", null, isAndroidStudio = true, "211", "193")
        testVersion("202-1.4.30-AS", "1.4.30", null, isAndroidStudio = true, "202", null)
        testVersion("1.2.40-dev-193-Studio3.0-1", "1.2.40", "dev", isAndroidStudio = true, "3.0", "193")
        testVersion("1.4-M1-42-IJ2020.1-1", "1.4", null, isAndroidStudio = false, "2020.1", "42")
        testVersion("1.4-M1-eap-27-IJ2020.1-1", "1.4", "eap", isAndroidStudio = false, "2020.1", "27")
    }

    fun testInvalidVersion() {
        testInvalidVersion("203-1.4.20-dev-M5-4575-IJ1234.45-1")
        testInvalidVersion("203-1.4.20-IJ.45")
        testInvalidVersion("203-1.4.20-1234.45")
        testInvalidVersion("1.4-release-M5-IJ2020.1-1")
    }

    fun testReleaseVersionDoesntHaveBuildNumber() {
        val version = KotlinPluginVersion.parse("1.2.40-release-Studio3.0-1") ?: throw AssertionError("Version should not be null")

        assertNull(version.buildNumber)
    }

    /**
     *  This test is disabled in kt-212-[master|1.6.0] because it is stable fails here, because
     *  KotlinPluginUtil.getPluginVersion() == "212-1.6.20-dev-4868-IJSNAPSHOT" and SNAPSHOT idea version is not supported in version parser.
     *  That is happening because on teamcity, when tests are running there is no `kotlin.plugin.version` parameter and
     *  KotlinPluginUtil.getPluginVersion() fallbacks to reading version from plugin.xml.
     *  But this version is set by another logic: org.jetbrains.intellij.build.kotlin.KotlinPluginKind#version and it is reading
     *  IDEA version from the file community/build.txt (212-SNAPSHOT is stored there)
     *  In 211 and 213 branches test are ok because logic there is different somehow
     */
    fun testCurrentPluginVersionParsing() {
        if (ignored("see above")) return

        if (KotlinIdePlugin.isSnapshot) return

        val currentVersion = KotlinPluginVersion.getCurrent()
        assert(currentVersion is NewKotlinPluginVersion) { "Can not parse current Kotlin Plugin version: ${KotlinIdePlugin.version}" }
    }

    fun testNewParser() {
        val version = KotlinPluginVersion.parse("203-1.4.20-dev-4575-IJ1234.45-7") as? NewKotlinPluginVersion ?: throw AssertionError("Version should not be null")

        assertEquals("1.4.20", version.kotlinVersion)
        assertEquals("dev", version.status)
        assertEquals("4575", version.kotlinCompilerVersion.buildNumber)
        assertEquals("45", version.buildNumber)
        assertEquals(false, version.isAndroidStudio)
        assertEquals("203.1234", version.platformVersion)
        assertEquals("7", version.patchNumber)
    }

    fun testOldParser() {
        val version = KotlinPluginVersion.parse("1.2.40-dev-193-Studio3.0-1") as? OldKotlinPluginVersion ?: throw AssertionError("Version should not be null")

        assertEquals("1.2.40", version.kotlinVersion)
        assertNull(version.milestone)
        assertEquals("dev", version.status)
        assertEquals("193", version.buildNumber)
        assertEquals(true, version.isAndroidStudio)
        assertEquals("3.0", version.platformVersion)
        assertEquals("1", version.patchNumber)
    }

    fun testOldParserMilestoneVersion() {
        val version = OldKotlinPluginVersion.parse("1.4-M1-eap-27-IJ2020.1-1") ?: throw AssertionError("Version should not be null")

        assertEquals("1.4", version.kotlinVersion)
        assertEquals("M1", version.milestone)
        assertEquals("eap", version.status)
        assertEquals("27", version.buildNumber)
        assertEquals(false, version.isAndroidStudio)
        assertEquals("2020.1", version.platformVersion)
        assertEquals("1", version.patchNumber)
    }

    fun testOldParserMilestoneVersionWithoutStatus() {
        val version = OldKotlinPluginVersion.parse("1.4-M1-42-IJ2020.1-1") ?: throw AssertionError("Version should not be null")

        assertEquals("1.4", version.kotlinVersion)
        assertEquals("M1", version.milestone)
        assertEquals("42", version.buildNumber)
        assertEquals(false, version.isAndroidStudio)
        assertEquals("2020.1", version.platformVersion)
        assertEquals("1", version.patchNumber)
    }

    private fun testVersion(
        version: String,
        expectedKotlinVersion: String,
        expectedStatus: String?,
        isAndroidStudio: Boolean,
        expectedPlatformVersion: String,
        expectedBuildNumber: String?
    ) {
        val parsed = KotlinPluginVersion.parse(version) ?: throw AssertionError("Version should not be null")

        assertEquals(expectedKotlinVersion, parsed.kotlinVersion)
        assertEquals(expectedStatus, parsed.status)
        assertEquals(isAndroidStudio, parsed.isAndroidStudio)
        assertEquals(expectedPlatformVersion, parsed.platformVersion)
        assertEquals(expectedBuildNumber, parsed.buildNumber)
    }

    private fun testInvalidVersion(version: String) {
        assertNull(KotlinPluginVersion.parse(version))
    }
}