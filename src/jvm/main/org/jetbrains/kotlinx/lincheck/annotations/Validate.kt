/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.annotations

import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*

/**
 * It is possible in **lincheck** to add the validation of the testing data structure invariants,
 * which is implemented via functions that can be executed multiple times during execution
 * when there is no running operation in an intermediate state (e.g., in the stress mode
 * they are invoked after each of the init and post part operations and after the whole parallel part).
 * Thus, these functions should not modify the data structure.
 *
 * Validation functions should be marked with this annotation, should not have arguments,
 * and should not return anything (in other words, the returning type is `void`).
 * In case the testing data structure is in an invalid state, they should throw exceptions
 * ([AssertionError] or [IllegalStateException] are the preferable ones).
 */
@Retention(RUNTIME)
@Target(FUNCTION)
annotation class Validate
