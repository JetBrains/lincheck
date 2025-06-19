/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.datastructures

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * It is possible to check the data structure consistency at the end of the execution
 * by specifying a validation function that is called at the end of each scenario.
 * At most one validation function is allowed.
 *
 * The validation function should be marked with this annotation and have no arguments.
 * In case the testing data structure is in an invalid state, it should throw an exception.
 * ([AssertionError] or [IllegalStateException] are the preferable ones).
 */
@Retention(RUNTIME)
@Target(FUNCTION)
annotation class Validate
