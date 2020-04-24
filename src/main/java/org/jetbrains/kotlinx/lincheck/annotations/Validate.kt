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
