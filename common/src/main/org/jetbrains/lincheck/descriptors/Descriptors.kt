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

    val id: Int
    val key: Key

    companion object {
        const val INVALID_ID = -1
    }
}

data class ClassDescriptor(
    private val context: TraceContext,
    val name: String
) : Descriptor {
    data class Key(val className: String) : Descriptor.Key()

    override val id: Int get() = context.classPool.getId(key)
    override val key: Descriptor.Key get() = Key(name)
}

data class MethodDescriptor(
    private val context: TraceContext,
    val classId: Int,
    val methodSignature: MethodSignature,
    val isInline: Boolean = false
) : Descriptor {
    // ClassName cannot be used in Key, because MethodDescriptor can be read before ClassDescriptor
    // and only classId will be known in such case.
    // Key is needed to restore MethodDescriptor, though.
    data class Key(
        val classId: Int,
        val methodSignature: MethodSignature,
    ) : Descriptor.Key()

    override val id: Int get() = context.methodPool.getId(key)
    override val key: Descriptor.Key get() = Key(classId, methodSignature)

    val classDescriptor: ClassDescriptor get() = context.classPool[classId]
    val className: String get() = classDescriptor.name
    val methodName: String get() = methodSignature.name
    val returnType: Types.Type get() = methodSignature.methodType.returnType
    val argumentTypes: List<Types.Type> get() = methodSignature.methodType.argumentTypes

    // TODO: JBRes-6558 make this field constant and set it in constructor.
    //  This flag is set manually, because we might detect that some method descriptor is intrinsic
    //  after it is already registered in the pool, due to how IntrinsicCandidateMethodFilter and MethodTransformer work.
    //  Foo::foo() { @IntrinsicCandidate Bar.bar() }
    //  If MethodTransformer is invoked for the body of Foo::foo then it will register descriptor for Bar::bar, but
    //  Bar class is not yet loaded by the jvm, so Bar::bar is not detected to be intrinsic yet by the IntrinsicCandidateMethodFilter.
    var isIntrinsic: Boolean = false

    override fun toString(): String = "$className.$methodSignature"
}

data class FieldDescriptor(
    private val context: TraceContext,
    val classId: Int,
    val fieldName: String,
    val type: Types.Type,
    val fieldKind: FieldKind,
    val isFinal: Boolean,
    val isVolatile: Boolean,
) : Descriptor {
    // ClassName cannot be used in Key, because FieldDescriptor can be read before ClassDescriptor
    // and only classId will be known in such case.
    // Key is needed to restore FieldDescriptor, though.
    data class Key(
        val classId: Int,
        val fieldName: String,
        val type: Types.Type,
        val fieldKind: FieldKind
    ) : Descriptor.Key()

    override val id: Int get() = context.fieldPool.getId(key)
    override val key: Descriptor.Key get() = Key(classId, fieldName, type, fieldKind)

    val classDescriptor: ClassDescriptor get() = context.classPool[classId]
    val className: String get() = classDescriptor.name

    val isStatic: Boolean get() = fieldKind == FieldKind.STATIC
}

/**
 * Represents the kind of class field: either static or instance.
 */
enum class FieldKind {
    STATIC, INSTANCE;

    companion object {
        fun fromIsStatic(isStatic: Boolean) = if (isStatic) STATIC else INSTANCE
    }
}

/**
 * As a side effect this function registers in [context] created field descriptor
 * and class descriptor required for field descriptor instantiation.
 */
fun Field.toDescriptor(context: TraceContext) = context.createAndRegisterFieldDescriptor(
    className = this.declaringClass.name,
    fieldName = this.name,
    type = this.type.kotlin.getType(),
    fieldKind = FieldKind.fromIsStatic(Modifier.isStatic(this.modifiers)),
    isFinal = Modifier.isFinal(this.modifiers),
    isVolatile = Modifier.isVolatile(this.modifiers),
)

data class VariableDescriptor(
    private val context: TraceContext,
    val name: String,
    val type: Types.Type,
) : Descriptor {
    data class Key(val name: String, val type: Types.Type) : Descriptor.Key()

    override val id: Int get() = context.variablePool.getId(key)
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