/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.jmx

interface TracingJmxController {
    fun install(format: String?, formatOption: String?, traceDumpFilePath: String?)
    fun uninstall()

    fun startTracing()
    fun stopTracing(traceDumpFilePath: String, packTrace: Boolean)

    fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean)
}