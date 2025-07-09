/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util


/**
 * Represents a set of Java Development Kit (JDK) major versions since JDK 6.
 */
internal  enum class JdkVersion {
    JDK_6, JDK_7, JDK_8, JDK_9, JDK_10, JDK_11, JDK_12, JDK_13, JDK_14, JDK_15,
    JDK_16, JDK_17, JDK_18, JDK_19, JDK_20, JDK_21, JDK_22, JDK_23, JDK_24, JDK_25;
    override fun toString(): String {
        return "jdk${name.removePrefix("JDK_")}"
    }
}

internal val DEFAULT_TEST_JDK_VERSION = JdkVersion.JDK_17

/**
 * Determines the current JDK version based on the `java.specification.version` system property.
 * If the system property indicates an unsupported JDK version, an error is thrown.
 */
internal val jdkVersion: JdkVersion = run {
    val jdkVersion = System.getProperty("java.specification.version")
    // java.specification.version is "1.x" for Java prior to 8 and "x" for the newer ones
    when {
        jdkVersion.startsWith("1.") ->
            when (jdkVersion.removePrefix("1.")) {
                "6" -> JdkVersion.JDK_6
                "7" -> JdkVersion.JDK_7
                "8" -> JdkVersion.JDK_8
                else -> error("Unsupported JDK version: $jdkVersion")
            }
        jdkVersion == "9"                       -> JdkVersion.JDK_9
        jdkVersion == "10"                      -> JdkVersion.JDK_10
        jdkVersion == "11"                      -> JdkVersion.JDK_11
        jdkVersion == "12"                      -> JdkVersion.JDK_12
        jdkVersion == "13"                      -> JdkVersion.JDK_13
        jdkVersion == "14"                      -> JdkVersion.JDK_14
        jdkVersion == "15"                      -> JdkVersion.JDK_15
        jdkVersion == "16"                      -> JdkVersion.JDK_16
        jdkVersion == "17"                      -> JdkVersion.JDK_17
        jdkVersion == "18"                      -> JdkVersion.JDK_18
        jdkVersion == "19"                      -> JdkVersion.JDK_19
        jdkVersion == "20"                      -> JdkVersion.JDK_20
        jdkVersion == "21"                      -> JdkVersion.JDK_21
        jdkVersion == "22"                      -> JdkVersion.JDK_22
        jdkVersion == "23"                      -> JdkVersion.JDK_23
        jdkVersion == "24"                      -> JdkVersion.JDK_24
        jdkVersion == "25"                      -> JdkVersion.JDK_25
        else ->
            error("Unsupported JDK version: $jdkVersion")
    }
}

/**
 * Indicates whether the current Java Development Kit (JDK) version is JDK 8.
 */
internal val isJdk8 = (jdkVersion == JdkVersion.JDK_8)
