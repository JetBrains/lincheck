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
package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.Remapper

/**
 * Implementation of this class describes how to run the generated execution.
 *
 * Note that strategy can run execution several times. For strategy creating
 * [.createStrategy] method is used. It is impossible to add a new strategy
 * without any code change.
 */
abstract class Strategy protected constructor(
    val scenario: ExecutionScenario
) {
    open fun needsTransformation() = false
    open fun createTransformer(cv: ClassVisitor?): ClassVisitor {
        throw UnsupportedOperationException("$javaClass strategy does not transform classes")
    }

    /**
     * Returns remapper that is used for transformation.
     * null if there is no need in renamings.
     */
    open fun createRemapper(): Remapper? = null

    abstract fun run(): LincheckFailure?

    /**
     * Check if Strategy allows to resume the coroutine.
     */
    open fun canResumeCoroutine(threadId: Int): Boolean {
        return true
    }

    /**
     * Is invoked when continuation is cancelled by lincheck.
     */
    open fun onContinuationCancel(threadId: Int) {}

    /**
     * Is invoked before each actor execution in a thread.
     */
    open fun onActorStart(threadId: Int) {}
}
