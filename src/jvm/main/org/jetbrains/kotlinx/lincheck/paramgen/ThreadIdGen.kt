/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.paramgen

import org.jetbrains.kotlinx.lincheck.RandomProvider

/**
 * This generator puts the number of the
 * executing thread as the parameter value.
 * The `0`-th thread specifies the init part
 * of the execution, while the `t+1`-th thread
 * references the post part (here we assume that
 * the parallel part has `t` threads).
 *
 * Note, that this API is unstable and is subject to change.
 */
class ThreadIdGen(randomProvider: RandomProvider, configuration: String) : ParameterGenerator<Any> {
    override fun generate() = THREAD_ID_TOKEN

    override fun resetRange() {
    }
}

internal val THREAD_ID_TOKEN = Any()
