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
import org.jetbrains.lincheck.util.FieldKind
import org.jetbrains.lincheck.descriptors.Descriptor.Companion.INVALID_ID
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

open class ClassDescriptor(
    private val context: TraceContext?,
    val name: String
) : Descriptor {
    data class Key(val className: String) : Descriptor.Key()

    override val id: Int get() = context?.classPool?.getId(key) ?: INVALID_ID
    override val key: Descriptor.Key get() = Key(name)
}

class ClassDescriptorWithNoContext(name: String, override val id: Int) : ClassDescriptor(null, name)

data class MethodDescriptor(
    private val context: TraceContext,
    val classId: Int,
    val methodSignature: MethodSignature,
    val isInline: Boolean = false
) : Descriptor {

    // TODO: JBRes-6558 make this field constant and set it in constructor.
    //  This flag is set manually, because we might detect that some method descriptor is intrinsic
    //  after it is already registered in the pool, due to how IntrinsicCandidateMethodFilter and MethodTransformer work.
    //  Foo::foo() { @IntrinsicCandidate Bar.bar() }
    //  If MethodTransformer is invoked for the body of Foo::foo then it will register descriptor for Bar::bar, but
    //  Bar class is not yet loaded by the jvm, so Bar::bar is not detected to be intrinsic yet by the IntrinsicCandidateMethodFilter.
    var isIntrinsic: Boolean = false

    override val id: Int get() = context.methodPool.getId(key)

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
    val fieldKind: FieldKind,
    val isFinal: Boolean,
) : Descriptor {

    override val id: Int get() = context.fieldPool.getId(key)

    val isStatic: Boolean get() = fieldKind == FieldKind.STATIC
    val classDescriptor: ClassDescriptor get() = context.classPool[classId]
    val className: String get() = classDescriptor.name

    data class Key(
        val className: String,
        val fieldName: String,
        val type: Types.Type,
        val fieldKind: FieldKind
    ) : Descriptor.Key()

    override val key: Descriptor.Key
        get() = Key(className, fieldName, type, fieldKind)
}

/**
 * As a side effect this function registers in [context] created field descriptor
 * and class descriptor required for field descriptor instantiation.
 */
fun Field.toDescriptor(context: TraceContext) = context.createAndRegisterFieldDescriptor(
    className = this.declaringClass.name,
    fieldName = this.name,
    type = this.type.kotlin.getType(),
    fieldKind = FieldKind.fromBoolean(Modifier.isStatic(this.modifiers)),
    isFinal = Modifier.isFinal(this.modifiers),
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