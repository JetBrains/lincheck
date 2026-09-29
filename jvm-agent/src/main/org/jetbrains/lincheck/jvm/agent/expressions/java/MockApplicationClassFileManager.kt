/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.java

import org.jetbrains.lincheck.jvm.agent.ClassModel
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionBytecodeRewriter
import org.jetbrains.lincheck.jvm.agent.expressions.MockedApplicationMembers
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.tools.ForwardingJavaFileManager
import javax.tools.ForwardingJavaFileObject
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.StandardLocation

/**
 * Gives javac a lazy compiler-only view in which non-public application fields and methods are public.
 *
 * The standard file manager discovers classes normally. Objects returned for `CLASS_PATH` class files are wrapped,
 * and their bytes are changed only if the compiler opens them. The running application never sees these bytes;
 * [ExpressionBytecodeRewriter] replaces every instruction that relied on widened visibility before the compiled
 * expression wrapper is loaded.
 *
 * This deliberately has the same discovery limits as the standard file manager. A class that exists only in a
 * custom or nested-jar class loader, or the bytes of a class that have no corresponding classpath entry yet, cannot
 * be mocked because javac never asks the delegate manager for it. Named-module exports, inaccessible class types,
 * constructors, and `super` calls are not widened. Widening every requested member may also expose a member that was
 * not accessible at the original source location; callers must treat the debugger's reflective-access contract as
 * authoritative rather than Java source visibility.
 */
internal class MockApplicationClassFileManager(
    fileManager: JavaFileManager,
) : ForwardingJavaFileManager<JavaFileManager>(fileManager) {
    val mockedMembers = MockedApplicationMembers()

    private val shadows = HashMap<String, ShadowClassFileObject>()
    private val transformedBytes = HashMap<String, ByteArray>()

    override fun list(
        location: JavaFileManager.Location,
        packageName: String,
        kinds: MutableSet<JavaFileObject.Kind>,
        recurse: Boolean,
    ): Iterable<JavaFileObject> = super.list(location, packageName, kinds, recurse).map { file ->
        shadowIfApplicationClass(location, file)
    }

    override fun getJavaFileForInput(
        location: JavaFileManager.Location,
        className: String,
        kind: JavaFileObject.Kind,
    ): JavaFileObject? = super.getJavaFileForInput(location, className, kind)?.let { file ->
        shadowIfApplicationClass(location, file, className)
    }

    override fun inferBinaryName(location: JavaFileManager.Location, file: JavaFileObject): String =
        if (file is ShadowClassFileObject) file.binaryName else super.inferBinaryName(location, file)

    private fun shadowIfApplicationClass(
        location: JavaFileManager.Location,
        file: JavaFileObject,
        knownBinaryName: String? = null,
    ): JavaFileObject {
        if (location != StandardLocation.CLASS_PATH || file.kind != JavaFileObject.Kind.CLASS) return file
        val binaryName = knownBinaryName ?: runCatching { super.inferBinaryName(location, file) }.getOrNull()
            ?: return file
        return shadows.getOrPut(binaryName) { ShadowClassFileObject(binaryName, file) }
    }

    private inner class ShadowClassFileObject(
        val binaryName: String,
        private val delegate: JavaFileObject,
    ) : ForwardingJavaFileObject<JavaFileObject>(delegate) {
        override fun openInputStream(): InputStream {
            val bytes = transformedBytes.getOrPut(binaryName) {
                delegate.openInputStream().use { it.readBytes() }.mockMemberVisibility(mockedMembers)
            }
            return ByteArrayInputStream(bytes)
        }
    }
}

private fun ByteArray.mockMemberVisibility(mockedMembers: MockedApplicationMembers): ByteArray {
    mockedMembers.addClassFile(ClassModel.fromClassBytes(this))
    val reader = ClassReader(this)
    val writer = ClassWriter(reader, 0)
    reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            return super.visitField(access.publicIfNeeded(), name, descriptor, signature, value)
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name == "<init>" || name == "<clinit>") {
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }
            return super.visitMethod(access.publicIfNeeded(), name, descriptor, signature, exceptions)
        }
    }, 0)
    return writer.toByteArray()
}

private fun Int.publicIfNeeded(): Int {
    if (this and Opcodes.ACC_PUBLIC != 0) return this
    return (this and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv()) or Opcodes.ACC_PUBLIC
}
