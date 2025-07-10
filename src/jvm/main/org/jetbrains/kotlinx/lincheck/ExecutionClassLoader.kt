/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution
import org.jetbrains.lincheck.util.runInsideIgnoredSection

/**
 * This classloader is mostly used by runner to separate parallel iterations,
 * and define generated [test executions][TestThreadExecution].
 */
class ExecutionClassLoader : ClassLoader() {
    fun defineClass(className: String?, bytecode: ByteArray): Class<out TestThreadExecution?> {
        @Suppress("UNCHECKED_CAST")
        return super.defineClass(className, bytecode, 0, bytecode.size) as Class<out TestThreadExecution?>
    }

    override fun loadClass(name: String?): Class<*> = runInsideIgnoredSection {
        return super.loadClass(name)
    }

    override fun loadClass(name: String?, resolve: Boolean): Class<*> = runInsideIgnoredSection {
        return super.loadClass(name, resolve)
    }
}