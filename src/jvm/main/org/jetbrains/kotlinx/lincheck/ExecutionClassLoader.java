/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck;

import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution;

/**
 * This classloader is mostly used by runner in order to separate parallel iterations,
 * and define generated {@link TestThreadExecution test executions}.
 */
public class ExecutionClassLoader extends ClassLoader {
    public Class<? extends TestThreadExecution> defineClass(String className, byte[] bytecode) {
        return (Class<? extends TestThreadExecution>) super.defineClass(className, bytecode, 0, bytecode.length);
    }
}