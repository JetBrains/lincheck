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

/**
 * In order to simplify understanding traces produced by managed strategies,
 * *lincheck* can print the current state of the testing data structure after
 * each meaningful event (e.g., write to atomic variable or function that potentially
 * changes the data structure call). In order to specify the way for representing
 * the data structure state, a public no-argument function that returns [String]
 * should be marked with this annotation. 
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@Deprecated(
    level = DeprecationLevel.WARNING,
    message = "",
)
annotation class StateRepresentation
