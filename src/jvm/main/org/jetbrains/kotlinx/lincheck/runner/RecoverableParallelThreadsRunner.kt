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

package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.nvm.CrashTransformer
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.objectweb.asm.ClassVisitor
import java.lang.reflect.Method


internal class RecoverableParallelThreadsRunner(
    strategy: Strategy,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    timeoutMs: Long,
    useClocks: UseClocks
) : ParallelThreadsRunner(strategy, testClass, validationFunctions, stateRepresentationFunction, timeoutMs, useClocks) {
    override fun needsTransformation() = true
    override fun createTransformer(cv: ClassVisitor): ClassVisitor = CrashTransformer(super.createTransformer(cv), _testClass)
}
