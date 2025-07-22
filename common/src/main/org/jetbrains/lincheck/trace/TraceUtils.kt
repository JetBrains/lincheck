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
    // If there's no dollar sign, return the original string
    if (!nestedClassRepresentation.contains('$')) return nestedClassRepresentation

    val result = StringBuilder()
    var currentIndex = 0

    while (currentIndex < nestedClassRepresentation.length) {
        // Find the next dollar sign
        val dollarIndex = nestedClassRepresentation.indexOf('$', currentIndex)
        // If no more dollar signs, append the rest and break
        if (dollarIndex == -1) {
            result.append(nestedClassRepresentation.substring(currentIndex))
            break
        }
        // Append the part before the dollar sign
        val before = nestedClassRepresentation.substring(currentIndex, dollarIndex)
        result.append(before)
        // Check if this dollar separates nested class names
        val afterDollarChar = nestedClassRepresentation[dollarIndex + 1]
        val isNestedClassNameSeparator = before.isNotEmpty() && before[0].isUpperCase() && afterDollarChar.isUpperCase()
        if (isNestedClassNameSeparator) {
            result.append('.')
        } else {
            result.append('$')
        }
        // Move past the dollar sign
        currentIndex = dollarIndex + 1
    }

    return result.toString()
}

/**
 * Removes java lambdas runtime address from classname.
 */
fun removeJavaLambdaRuntimeAddress(classNameRepresentation: String): String {
    if (!isJavaLambdaClass(classNameRepresentation)) return classNameRepresentation
    return classNameRepresentation.substringBeforeLast('/')
}


// Trace polishing functions
fun String.hasCoroutinesCoreSuffix(): Boolean = endsWith("\$kotlinx_coroutines_core")

fun String.removeCoroutinesCoreSuffix(): String = removeSuffix("\$kotlinx_coroutines_core")

fun String.removeInlineIV(): String = removeSuffix("\$iv")

fun String?.isExactDollarThis(): Boolean = equals("\$this")

fun String.removeDollarThis(): String = if (isExactDollarThis()) this else removePrefix("\$this")

fun String.removeLeadingDollar(): String = removePrefix("$")

fun String.removeVolatileDollar(): String = removeSuffix("\$volatile")

fun String.removeVolatileDollarFU(): String = removeSuffix("\$volatile\$FU")