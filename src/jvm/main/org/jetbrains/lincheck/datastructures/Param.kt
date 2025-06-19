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

import org.jetbrains.kotlinx.lincheck.paramgen.DummyParameterGenerator
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

/**
 * Use this annotation to specify parameter generators.
 * @see ParameterGenerator
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS)
@JvmRepeatable(Param.Params::class)
@Inherited
annotation class Param(
    /**
     * If the annotation is set on a class, creates a [parameter generator][ParameterGenerator]
     * which can be used in [operations][Operation] by this name. If is set on an operation,
     * uses the specified named parameter generator which is created as described before.
     */
    val name: String = "",

    /**
     * Specifies the [ParameterGenerator] class which should be used for this parameter.
     */
    val gen: KClass<out ParameterGenerator<*>> = DummyParameterGenerator::class,

    /**
     * Specifies the configuration for the [parameter generator][.gen].
     */
    val conf: String = "",
) {
    /**
     * Holder annotation for [Param].
     * Not a public API.
     */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS)
    @Inherited
    annotation class Params(vararg val value: Param)
}