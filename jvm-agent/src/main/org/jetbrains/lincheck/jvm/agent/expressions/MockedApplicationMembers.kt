/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions

import org.jetbrains.lincheck.jvm.agent.ClassModel
import org.jetbrains.lincheck.jvm.agent.FieldModel
import org.jetbrains.lincheck.jvm.agent.MethodModel

/**
 * Records application members that temporary compiler inputs made accessible.
 *
 * The enclosing-class evaluators compile the expression against source facades or mocked class files.
 * Those inputs may expose a real non-public member so the language compiler can resolve it.
 * They register the member here, and [ExpressionBytecodeRewriter] uses the record to find the resulting direct
 * access and replace it with a reflective helper.
 *
 * For `count > limit`, where `Counter.count` is a private `int`, this class records the equivalent of:
 * ```
 * FieldModel(declaringBinaryName="sample.Counter", name="count", type=I, isStatic=false, ...)
 * ```
 * It does not generate code itself; the record tells the later rewrite exactly which instruction was compiled
 * against the mock.
 */
internal class MockedApplicationMembers {
    private val fields = HashMap<MemberKey, FieldModel>()
    private val methods = HashMap<MemberKey, MethodModel>()
    private val classFiles = HashMap<String, ClassModel>()

    fun field(owner: String, name: String, descriptor: String): FieldModel? =
        fields[MemberKey(owner, name, descriptor)] ?: classFileMember(owner) { type ->
            type.declaredFields[name]?.takeIf { it.type.descriptor == descriptor }
        }?.takeUnless { it.isPublic }

    fun method(owner: String, name: String, descriptor: String): MethodModel? =
        methods[MemberKey(owner, name, descriptor)] ?: classFileMember(owner) { type ->
            type.declaredMethods.firstOrNull { it.name == name && it.descriptor == descriptor }
        }?.takeUnless { it.isPublic }

    /** Records original declarations before the compiler's class-file view widens their visibility. */
    fun addClassFile(type: ClassModel) {
        classFiles[type.binaryName] = type
    }

    private fun <T> classFileMember(owner: String, member: (ClassModel) -> T?): T? {
        var type = classFiles[owner.replace('/', '.')]
        while (type != null) {
            // javac may name a subclass as owner, including for a private member made public in its superclass.
            // Stop at public declarations too: a public subclass member can hide a mocked superclass member.
            member(type)?.let { return it }
            type = type.superclassBinaryName?.let(classFiles::get)
        }
        return null
    }

    fun add(field: FieldModel) {
        fields[MemberKey(field.declaringBinaryName.replace('.', '/'), field.name, field.type.descriptor)] = field
    }

    fun add(method: MethodModel) {
        methods[MemberKey(method.declaringBinaryName.replace('.', '/'), method.name, method.descriptor)] = method
    }

    fun addNonPublicMembers(type: ClassModel) {
        type.declaredFields.values.filterNot { it.isPublic }.forEach { field ->
            add(field)
        }
        type.declaredMethods.filterNot { it.isPublic }.forEach { method ->
            add(method)
        }
    }

    /** Registers declared members and the subclass-owner aliases emitted for inherited non-public members. */
    fun addNonPublicMembers(types: Collection<ClassModel>) {
        val byName = types.associateBy(ClassModel::binaryName)
        types.forEach { type ->
            addNonPublicMembers(type)
            var superclass = type.superclassBinaryName?.let(byName::get)
            while (superclass != null) {
                addInheritedMembers(type, superclass)
                superclass = superclass.superclassBinaryName?.let(byName::get)
            }
        }
    }

    fun addAll(other: MockedApplicationMembers) {
        fields.putAll(other.fields)
        methods.putAll(other.methods)
        classFiles.putAll(other.classFiles)
    }

    private fun addInheritedMembers(subclass: ClassModel, superclass: ClassModel) {
        val compiledOwner = subclass.binaryName.replace('.', '/')
        val samePackage = subclass.binaryName.substringBeforeLast('.', "") ==
            superclass.binaryName.substringBeforeLast('.', "")
        superclass.declaredFields.values
            .filter { !it.isPublic && !it.isPrivate && (it.isProtected || samePackage) }
            .forEach { field ->
                fields.putIfAbsent(
                    MemberKey(compiledOwner, field.name, field.type.descriptor),
                    field,
                )
            }
        superclass.declaredMethods
            .filter { !it.isPublic && !it.isPrivate && (it.isProtected || samePackage) }
            .forEach { method ->
                methods.putIfAbsent(
                    MemberKey(compiledOwner, method.name, method.descriptor),
                    method,
                )
            }
    }

    private data class MemberKey(val owner: String, val name: String, val descriptor: String)
}
