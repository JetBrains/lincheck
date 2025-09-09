/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.junit.Assert.*
import org.junit.Test

class TransformationProfileTests {
    private object TestBaseProfile : TransformationProfile {
        // A distinctive configuration that indicates that the base profile was used
        val testConfiguration = TransformationConfiguration(trackMethodCalls = true)

        override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
            return testConfiguration
        }
    }

    private fun prof(include: List<String> = emptyList(), exclude: List<String> = emptyList()): TransformationProfile =
        FilteredTransformationProfile(include, exclude, TestBaseProfile)

    private fun isInstrumented(profile: TransformationProfile, clazz: String): Boolean =
        profile.getMethodConfiguration(clazz, "m", "()V") === TestBaseProfile.testConfiguration

    private fun assertInstrumented(profile: TransformationProfile, clazz: String) =
        assertTrue("Expected $clazz to be instrumented", isInstrumented(profile, clazz))

    private fun assertNotInstrumented(profile: TransformationProfile, clazz: String) =
        assertFalse("Expected $clazz to NOT be instrumented", isInstrumented(profile, clazz))

    // ===== exclude-only =====

    @Test
    fun excludeOnly_singleClass() {
        val p = prof(exclude = listOf("org.jetbrains.MyClass"))
        assertNotInstrumented(p, "org.jetbrains.MyClass")
        assertInstrumented(p, "org.jetbrains.Other")
    }

    @Test
    fun excludeOnly_listOfClasses() {
        val p = prof(exclude = listOf("org.A", "org.B", "org.C"))
        assertNotInstrumented(p, "org.A")
        assertNotInstrumented(p, "org.B")
        assertNotInstrumented(p, "org.C")
        assertInstrumented(p, "org.D")
    }

    @Test
    fun excludeOnly_packagePrefix() {
        val p = prof(exclude = listOf("org.jetbrains.*"))
        assertNotInstrumented(p, "org.jetbrains.Foo")
        assertNotInstrumented(p, "org.jetbrains.bar.Baz")
        assertInstrumented(p, "org.other.Foo")
    }

    @Test
    fun excludeOnly_wildcardInClassName() {
        val p = prof(exclude = listOf("org.jetbrains.Jetbrains*Test"))
        assertNotInstrumented(p, "org.jetbrains.JetbrainsGreatTest")
        assertNotInstrumented(p, "org.jetbrains.JetbrainsSuperTest")
        assertInstrumented(p, "org.jetbrains.JetbrainsGreatProd")
        assertInstrumented(p, "org.jetbrains.OtherJetbrainsGreatTestX")
    }

    // ===== include-only =====

    @Test
    fun includeOnly_singleClass() {
        val p = prof(include = listOf("org.jetbrains.MyClass"))
        assertInstrumented(p, "org.jetbrains.MyClass")
        assertNotInstrumented(p, "org.jetbrains.Other")
    }

    @Test
    fun includeOnly_listOfClasses() {
        val p = prof(include = listOf("org.A", "org.B", "org.C"))
        assertInstrumented(p, "org.A")
        assertInstrumented(p, "org.B")
        assertInstrumented(p, "org.C")
        assertNotInstrumented(p, "org.D")
    }

    @Test
    fun includeOnly_packagePrefix() {
        val p = prof(include = listOf("org.jetbrains.*"))
        assertInstrumented(p, "org.jetbrains.Foo")
        assertInstrumented(p, "org.jetbrains.bar.Baz")
        assertNotInstrumented(p, "org.other.Foo")
    }

    @Test
    fun includeOnly_wildcardInClassName() {
        val p = prof(include = listOf("org.jetbrains.Jetbrains*Test"))
        assertInstrumented(p, "org.jetbrains.JetbrainsGreatTest")
        assertInstrumented(p, "org.jetbrains.JetbrainsSuperTest")
        assertNotInstrumented(p, "org.jetbrains.JetbrainsGreatProd")
        assertNotInstrumented(p, "org.jetbrains.OtherJetbrainsGreatTestX")
    }

    // ===== conflicts and precedence =====

    @Test
    fun sameClass_inIncludeAndExclude_excludeWins() {
        val p = prof(include = listOf("org.X"), exclude = listOf("org.X"))
        assertNotInstrumented(p, "org.X")
    }

    @Test
    fun includePackage_excludeSpecificClass() {
        val p = prof(include = listOf("org.jetbrains.*"), exclude = listOf("org.jetbrains.Special"))
        // specific excluded class
        assertNotInstrumented(p, "org.jetbrains.Special")
        // other classes from the package are included
        assertInstrumented(p, "org.jetbrains.Other")
        assertInstrumented(p, "org.jetbrains.deep.Nested")
        // outside of package is not included
        assertNotInstrumented(p, "org.other.Other")
    }

    @Test
    fun excludePackage_includeSpecificClass() {
        val p = prof(include = listOf("org.jetbrains.Special"), exclude = listOf("org.jetbrains.*"))
        // Exclude has higher priority, even the specifically included class is excluded
        assertNotInstrumented(p, "org.jetbrains.Special")
        // other classes are excluded as well
        assertNotInstrumented(p, "org.jetbrains.Other")
        // outside of excluded package but not included explicitly -> not instrumented
        assertNotInstrumented(p, "org.other.Other")
    }

    @Test
    fun complex_includeAndExclude_patterns() {
        val include = listOf(
            // include two concrete classes
            "com.app.Service", "com.lib.Util",
            // include all tests in org.pkg
            "org.pkg.*Test",
            // include everything from api package
            "api.*"
        )
        val exclude = listOf(
            // exclude a specific class that is also included via api.*
            "api.Internal",
            // exclude any class ending with Impl in any package
            "*Impl",
            // exclude a subpackage of org.pkg entirely
            "org.pkg.internal.*"
        )
        val p = prof(include = include, exclude = exclude)

        // Included specific classes
        assertInstrumented(p, "com.app.Service")
        assertInstrumented(p, "com.lib.Util")
        // Excluded wildcard *.Impl should exclude these even if under api.* include
        assertNotInstrumented(p, "api.LoggerImpl")
        // Excluded specific class under api.*
        assertNotInstrumented(p, "api.Internal")
        // Included: other api classes allowed
        assertInstrumented(p, "api.PublicApi")
        // Included: tests in org.pkg
        assertInstrumented(p, "org.pkg.MathTest")
        assertInstrumented(p, "org.pkg.StringsTest")
        // Excluded: internal subpackage
        assertNotInstrumented(p, "org.pkg.internal.Helper")
        // Not included at all (since include list exists)
        assertNotInstrumented(p, "com.other.Something")
    }
}
