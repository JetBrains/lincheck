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
 * It is possible to check the data structure consistency at the end of the execution
 * by specifying a validation function that is called at the end of each scenario.
 *
 * The validation functions should be marked with this annotation,
 * have no arguments, and not modify the data structure state.
 * In case the testing data structure is in an invalid state, they should throw an exception.
 * ([AssertionError] or [IllegalStateException] are the preferable ones).
 */
@Retention(RUNTIME)
@Target(FUNCTION)
annotation class Validate
