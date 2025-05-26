/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util


/**
 * Represents a set of Java Development Kit (JDK) versions on which the tests are run.
 */
internal enum class TestJdkVersion {
    JDK_8, JDK_11, JDK_13, JDK_15, JDK_17, JDK_19, JDK_20, JDK_21;
    override fun toString(): String {
        return "jdk${name.removePrefix("JDK_")}"
    }
}

internal val DEFAULT_TEST_JDK_VERSION = TestJdkVersion.JDK_17

/**
 * Determines the current JDK version based on the `java.specification.version` system property.
 * If the system property indicates an unsupported JDK version, an error is thrown.
 */
internal val testJdkVersion: TestJdkVersion = run {
    val jdkVersion = System.getProperty("java.specification.version")
    // java.specification.version is "1.x" for Java prior to 8 and "x" for the newer ones
    when {
        jdkVersion.removePrefix("1.") == "8"    -> TestJdkVersion.JDK_8
        jdkVersion == "11"                      -> TestJdkVersion.JDK_11
        jdkVersion == "13"                      -> TestJdkVersion.JDK_13
        jdkVersion == "15"                      -> TestJdkVersion.JDK_15
        jdkVersion == "17"                      -> TestJdkVersion.JDK_17
        jdkVersion == "19"                      -> TestJdkVersion.JDK_19
        jdkVersion == "20"                      -> TestJdkVersion.JDK_20
        jdkVersion == "21"                      -> TestJdkVersion.JDK_21
        else ->
            error("Unsupported JDK version: $jdkVersion")
    }
}

/**
 * Indicates whether the current Java Development Kit (JDK) version is JDK 8.
 */
internal val isJdk8 = (testJdkVersion == TestJdkVersion.JDK_8)
