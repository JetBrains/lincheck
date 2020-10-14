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
package org.jetbrains.kotlinx.lincheck.strategy.managed

/**
 * ManagedGuarantee will be constructed for classes with a name listed in [fullClassNames].
 */
fun forClasses(vararg fullClassNames: String) = forClasses { it in fullClassNames }

/**
 * ManagedGuarantee will be constructed for all classes satisfying [classPredicate].
 */
fun forClasses(classPredicate: (fullClassName: String) -> Boolean) = ManagedStrategyGuarantee.MethodBuilder(classPredicate)

class ManagedStrategyGuarantee private constructor(
    internal val classPredicate: (fullClassName: String) -> Boolean,
    internal val methodPredicate: (methodName: String) -> Boolean,
    internal val type: ManagedGuaranteeType
) {
    class MethodBuilder internal constructor(
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
        fun methods(methodPredicate: (methodName: String) -> Boolean) = GuaranteeBuilder(classPredicate, methodPredicate)
    }

    class GuaranteeBuilder internal constructor(
        private val classPredicate: (fullClassName: String) -> Boolean,
        private val methodPredicate: (methodName: String) -> Boolean
    ) {
        /**
         * The methods will be treated by model checking strategy as if they do not have
         * interesting code locations inside, and no switch point will be added due to the
         * specified method calls.
         */
        fun ignore() = ManagedStrategyGuarantee(classPredicate, methodPredicate, ManagedGuaranteeType.IGNORE)

        /**
         * The methods will be treated by model checking strategy as an atomic operation, so that
         * context switches will not happen inside these methods, what significantly reduces the
         * number of possible interleavings and makes it possible to test data structures modularly.
         *
         * In contract with the [ignore] mode, switch points are added right before and after the
         * specified method calls.
         */
        fun treatAsAtomic() = ManagedStrategyGuarantee(classPredicate, methodPredicate, ManagedGuaranteeType.TREAT_AS_ATOMIC)
    }
}

internal enum class ManagedGuaranteeType {
    IGNORE,
    TREAT_AS_ATOMIC
}