/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("PackageDirectoryMismatch")

package org.jetbrains.kotlinx.lincheck.util

/**
 * Utility object to emulate exception in the Lincheck itself.
 *
 * This object is located is another package
 * as we are using package name to determine if an exception was thrown from user code or from Lincheck,
 * so we need to throw an exception from `org.jetbrains.kotlinx.lincheck` package to emulate a bug in Lincheck
 */
object InternalLincheckExceptionEmulator {
    fun throwException(): Nothing = error("Internal bug")
    fun throwException(exceptionProvider: () -> Exception): Nothing = throw exceptionProvider()
}