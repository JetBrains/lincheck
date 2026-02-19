/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.descriptors

import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.createAndRegisterFieldDescriptor
import java.lang.reflect.Modifier
import java.lang.reflect.Field


/**
 * Common descriptor contract used by pools/registries.
 *
 * Implementations should expose a stable [key] that uniquely identifies a
 * descriptor logically, and an [id] that is assigned by a pool upon
 * registration.
 */
interface Descriptor {
    abstract class Key

    val key: Key
    var id: Int
}

data class ClassDescriptor(
    val name: String,
) : Descriptor {
    override var id: Int = -1
    data class Key(val className: String) : Descriptor.Key()
    override val key: Descriptor.Key get() = Key(name)
}

data class MethodDescriptor(
    private val context: TraceContext,
    val classId: Int,
    val methodSignature: MethodSignature,
    val isIntrinsic: Boolean = false,
    val isInline: Boolean = false
) : Descriptor {
    override var id: Int = -1

    val classDescriptor: ClassDescriptor get() = context.classPool[classId]
    val className: String get() = classDescriptor.name
    val methodName: String get() = methodSignature.name
    val returnType: Types.Type get() = methodSignature.methodType.returnType
    val argumentTypes: List<Types.Type> get() = methodSignature.methodType.argumentTypes

    override fun toString(): String = "$className.$methodSignature"

    data class Key(
        val className: String,
        val methodSignature: MethodSignature,
    ) : Descriptor.Key()

    override val key: Descriptor.Key
        get() = Key(className, methodSignature)
}

data class FieldDescriptor(
    private val context: TraceContext,
    val classId: Int,
    val fieldName: String,
    val type: Types.Type,
    val isStatic: Boolean,
    val isFinal: Boolean,
) : Descriptor {
    override var id: Int = -1
    val classDescriptor: ClassDescriptor get() = context.classPool[classId]
    val className: String get() = classDescriptor.name

    data class Key(
        val className: String,
        val fieldName: String,
        val type: Types.Type
    ) : Descriptor.Key()

    // TODO: jvm bytecode allows static and regular fields of the same name in a class, should the `isStatic` be included in key?
    override val key: Descriptor.Key
        get() = Key(className, fieldName, type)
}

/**
 * As a side effect this function registers in [context] created field descriptor
 * and class descriptor required for field descriptor instantiation.
 */
fun Field.toDescriptor(context: TraceContext) = context.createAndRegisterFieldDescriptor(
    className = this.declaringClass.name,
    fieldName = this.name,
    type = this.type.kotlin.getType(),
    isStatic = Modifier.isStatic(this.modifiers),
    isFinal = Modifier.isFinal(this.modifiers),
)

data class VariableDescriptor(
    val name: String,
    val type: Types.Type,
) : Descriptor {
    override var id: Int = -1
    data class Key(val name: String, val type: Types.Type) : Descriptor.Key()
    override val key: Descriptor.Key get() = Key(name, type)
}


data class ActiveLocal(
    val localName: String,
    val localKind: LocalKind,
)

enum class LocalKind {
    THIS,
    PARAMETER,
    VARIABLE,
}