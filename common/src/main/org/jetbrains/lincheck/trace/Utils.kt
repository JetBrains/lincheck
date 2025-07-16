/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.util.isJavaLambdaClass

/**
 * Replaces nested class dollars (if present) from string with dots.
 */
fun replaceNestedClassDollar(nestedClassRepresentation: String): String {
    val before = nestedClassRepresentation.substringBefore('$')
    val after = nestedClassRepresentation.substringAfter('$', "")
    if (after.isEmpty()) return nestedClassRepresentation
    val firstPart = if (before.isNotEmpty() && before[0].isUpperCase() && after[0].isUpperCase()) "$before."
    else "$before$"
    return firstPart + replaceNestedClassDollar(after)
}

/**
 * Removes java lambdas runtime address from classname.
 */
fun removeJavaLambdaRuntimeAddress(classNameRepresentation: String): String {
    if (!isJavaLambdaClass(classNameRepresentation)) return classNameRepresentation
    return classNameRepresentation.substringBeforeLast('/')
}