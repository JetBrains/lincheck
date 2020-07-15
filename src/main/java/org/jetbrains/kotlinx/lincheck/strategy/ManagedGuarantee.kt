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
package org.jetbrains.kotlinx.lincheck.strategy

/**
 * ManagedGuarantee will be constructed for classes with names [fullClassNames].
 */
fun forClasses(vararg fullClassNames: String) = ManagedGuarantee.TypeBuilder { it in fullClassNames }

/**
 * ManagedGuarantee will be constructed for all classes.
 */
fun forAllClasses() = ManagedGuarantee.TypeBuilder { true }

/**
 * ManagedGuarantee will be constructed for all classes satisfying [predicate].
 */
fun forClasses(predicate: (fullClassName: String) -> Boolean) = ManagedGuarantee.TypeBuilder(predicate)

class ManagedGuarantee private constructor(
        val classPredicate: (fullClassName: String) -> Boolean,
        val type: ManagedGuaranteeType,
        val methodPredicate: (methodName: String) -> Boolean
) {
    class TypeBuilder internal constructor(private val classPredicate: (fullClassName: String) -> Boolean) {
        /**
         * The methods will be treated by model checking strategy as if they do not have
         * interesting code locations inside.
         */
        fun ignore() = MethodBuilder(classPredicate, ManagedGuaranteeType.IGNORE)

        /**
         * The methods will be treated by model checking strategy as an atomic instruction.
         * No thread context switches are possible inside these methods.
         * Contrary to IGNORE mode, model checking strategy can add thread context switches
         * immediately before or after method invocations.
         */
        fun treatAsAtomic() = MethodBuilder(classPredicate, ManagedGuaranteeType.TREAT_AS_ATOMIC)
    }

    class MethodBuilder internal constructor(
            protected val classPredicate: (fullClassName: String) -> Boolean,
            protected val type: ManagedGuaranteeType
    ) {
        /**
         * ManagedGuarantee will be constructed for methods with names [methodNames]
         */
        fun methods(vararg methodNames: String) = ManagedGuarantee(classPredicate, type) { it in methodNames }

        /**
         * ManagedGuarantee will be constructed for all methods
         */
        fun allMethods() = ManagedGuarantee(classPredicate, type) { true }

        /**
         * ManagedGuarantee will be constructed for all methods satisfying [methodPredicate]
         */
        fun methodsSatisfying(methodPredicate: (methodName: String) -> Boolean) = ManagedGuarantee(classPredicate, type, methodPredicate)
    }
}

enum class ManagedGuaranteeType {
    IGNORE,
    TREAT_AS_ATOMIC
}