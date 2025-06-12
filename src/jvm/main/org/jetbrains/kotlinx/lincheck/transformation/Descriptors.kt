/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

internal data class FieldDescriptor(
    val optimizedClassName: OptimizedString,
    val optimizedFieldName: OptimizedString,
    val isStatic: Boolean,
    val isFinal: Boolean,
) {
    val className: String get() = optimizedClassName.toString()
    val fieldName: String get() = optimizedFieldName.toString()

    constructor(className: String, fieldName: String, isStatic: Boolean, isFinal: Boolean) :
            this(className.optimized(), fieldName.optimized(), isStatic, isFinal)
}

internal val fieldCache = IndexedPool<FieldDescriptor>()

internal data class VariableDescriptor(
    val optimizedName: OptimizedString,
) {
    val name: String get() = optimizedName.toString()

    constructor(name: String) : this(name.optimized())
}

internal val variableCache = IndexedPool<VariableDescriptor>()

internal class IndexedPool<T> {
    private val items = mutableListOf<T>()
    private val index = hashMapOf<T, Int>()

    @Synchronized
    operator fun get(id: Int): T = items[id]

    @Synchronized
    fun getOrCreateId(item: T): Int = index.getOrPut(item) {
        items.add(item)
        items.lastIndex
    }
}

internal fun <T> IndexedPool<T>.getInterned(item: T) = get(getOrCreateId(item))

@JvmInline
internal value class OptimizedString(val id: Int) {
    override fun toString(): String = stringRepresentations[id]
    companion object {
        private val pool = HashMap<String, OptimizedString>()
        private val stringRepresentations = mutableListOf<String>()
        
        @Synchronized
        operator fun invoke(value: String): OptimizedString = pool.getOrPut(value) {
            val optimizedString = OptimizedString(pool.size)
            stringRepresentations.add(value)
            optimizedString
        }
    }
}

internal fun String.optimized() = OptimizedString(this)
