/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.annotations

import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator.Dummy
import kotlin.reflect.KClass

/**
 * Use this annotation to specify parameter generators.
 * @see ParameterGenerator
 */
@kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Repeatable
expect annotation class Param constructor(
        /**
         * If the annotation is set on a class, creates a [parameter generator][ParameterGenerator]
         * which can be used in [operations][Operation] by this name. If is set on an operation,
         * uses the specified named parameter generator which is created as described before.
         */
        val name: String,
        /**
         * Specifies the [ParameterGenerator] class which should be used for this parameter.
         */
        val gen: KClass<out ParameterGenerator<*>>,
        /**
         * Specifies the configuration for the [parameter generator][.gen].
         */
        val conf: String
) {
    /**
     * Holder annotation for [Param].
     * Not a public API.
     */
    @kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
    annotation class Params constructor(vararg val value: Param)
}