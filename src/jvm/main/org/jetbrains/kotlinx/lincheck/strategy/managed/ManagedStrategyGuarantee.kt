/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import kotlin.reflect.*

/**
 * ManagedGuarantee will be constructed for classes with a name listed in [fullClassNames].
 */
fun forClasses(vararg fullClassNames: String) = forClasses { it in fullClassNames }

/**
 * ManagedGuarantee will be constructed for all [classes].
 */
fun forClasses(vararg classes: KClass<*>) = forClasses {
    it in classes.map { kClass ->
        kClass.qualifiedName ?: error("The class $it is local or is a class of an anonymous object")
    }
}

/**
 * ManagedGuarantee will be constructed for all classes satisfying [classPredicate].
 */
fun forClasses(classPredicate: (fullClassName: String) -> Boolean) = ManagedStrategyGuarantee.MethodBuilder({ true }, classPredicate)

/**
 * ManagedGuarantee will be constructed for all [instances].
 */
internal fun forInstances(vararg instances: Any) = forInstances { it in instances }

/**
 * ManagedGuarantee will be constructed for all objects satisfying [instancePredicate].
 */
internal fun forInstances(instancePredicate: (instance: Any?) -> Boolean) = ManagedStrategyGuarantee.MethodBuilder(instancePredicate, { true })

class ManagedStrategyGuarantee private constructor(
    internal val instancePredicate: (instance: Any?) -> Boolean,
    internal val classPredicate: (fullClassName: String) -> Boolean,
    internal val methodPredicate: (methodName: String) -> Boolean,
    internal val type: ManagedGuaranteeType
) {
    class MethodBuilder internal constructor(
        private val instancePredicate: (instance: Any?) -> Boolean,
        private val classPredicate: (fullClassName: String) -> Boolean
    ) {
        /**
         * ManagedGuarantee will be constructed for methods with names [methodNames]
         */
        fun methods(vararg methodNames: String) = methods { it in methodNames }

        /**
         * ManagedGuarantee will be constructed for all methods
         */
        fun allMethods() = methods { true }

        /**
         * ManagedGuarantee will be constructed for all methods satisfying [methodPredicate]
         */
        fun methods(methodPredicate: (methodName: String) -> Boolean) = GuaranteeBuilder(instancePredicate, classPredicate, methodPredicate)
    }

    class GuaranteeBuilder internal constructor(
        private val instancePredicate: (instance: Any?) -> Boolean,
        private val classPredicate: (fullClassName: String) -> Boolean,
        private val methodPredicate: (methodName: String) -> Boolean
    ) {
        /**
         * The methods will be treated by model checking strategy as if they do not have
         * interesting code locations inside, and no switch point will be added due to the
         * specified method calls.
         */
        fun ignore() = ManagedStrategyGuarantee(instancePredicate, classPredicate, methodPredicate, ManagedGuaranteeType.IGNORE)

        /**
         * The methods will be treated by model checking strategy as an atomic operation, so that
         * context switches will not happen inside these methods, what significantly reduces the
         * number of possible interleavings and makes it possible to test data structures modularly.
         *
         * In contract with the [ignore] mode, switch points are added right before and after the
         * specified method calls.
         */
        fun treatAsAtomic() = ManagedStrategyGuarantee(instancePredicate, classPredicate, methodPredicate, ManagedGuaranteeType.TREAT_AS_ATOMIC)
    }
}

internal enum class ManagedGuaranteeType {
    IGNORE,
    TREAT_AS_ATOMIC
}