/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.util

/**
 * Indicates whether the current Java Development Kit (JDK) version is JDK 8.
 *
 * This property checks the system's Java specification version
 * and determines if the major version corresponds to '8', signifying JDK 8.
 */
// java.specification.version is "1.$x" for Java prior to 8 and "$x" for the newer ones
internal val isJdk8 = System.getProperty("java.specification.version").removePrefix("1.") == "8"
