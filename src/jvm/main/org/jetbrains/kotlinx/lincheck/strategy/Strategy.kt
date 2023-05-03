/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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
    open fun createTransformer(cv: ClassVisitor): ClassVisitor {
        throw UnsupportedOperationException("$javaClass strategy does not transform classes")
    }

    abstract fun run(): LincheckFailure?

    /**
     * Is invoked before each actor execution.
     */
    open fun onActorStart(iThread: Int) {}
}
