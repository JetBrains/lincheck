/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
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
}

internal val THREAD_ID_TOKEN = Any()
