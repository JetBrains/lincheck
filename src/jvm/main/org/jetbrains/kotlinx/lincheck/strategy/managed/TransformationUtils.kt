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

import org.jetbrains.kotlinx.lincheck.CancellabilitySupportClassTransformer
import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.internalClassName
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.collections.set

object LincheckClassFileTransformer : ClassFileTransformer {
    val oldClasses = HashMap<Pair<ClassLoader?, String>, ByteArray>()
    val transformedClasses = HashMap<Pair<ClassLoader?, String>, ByteArray>()

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray = synchronized(LincheckClassFileTransformer) {
        if (!shouldTransform(className.canonicalClassName)) return classfileBuffer
        return transformedClasses.computeIfAbsent(loader to className) {
            val reader = ClassReader(classfileBuffer)
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)

            var classVisitor: ClassVisitor = writer
            classVisitor = CancellabilitySupportClassTransformer(classVisitor)

            reader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

            oldClasses[loader to className] = classfileBuffer

            writer.toByteArray()
        }
    }

    fun shouldTransform(canonicalClassName: String, clazz: Class<*>? = null): Boolean {
        if (canonicalClassName.startsWith("org.gradle.")) return false
        if (canonicalClassName.startsWith("worker.org.gradle.")) return false
        if (canonicalClassName.startsWith("org.objectweb.asm.")) return false
        if (canonicalClassName.startsWith("net.bytebuddy.")) return false
        if (canonicalClassName.startsWith("org.junit.")) return false
        if (canonicalClassName.startsWith("junit.framework.")) return false
        if (canonicalClassName.startsWith("com.sun.tools.")) return false
        if (canonicalClassName.startsWith("java.util.")) {
            if (clazz == null) return true
            // transformation of exceptions causes a lot of trouble with catching expected exceptions
            val isException = Throwable::class.java.isAssignableFrom(clazz)
            // function package is not transformed, because AFU uses it, and thus, there will be transformation problems
            val inFunctionPackage = canonicalClassName.startsWith("java.util.function.")
            // some api classes that provide low-level access can not be transformed
            val isImpossibleToTransformApi = isImpossibleToTransformApiClass(canonicalClassName)
            // classes are transformed by default and are in the special set when they should not be transformed
            val isTransformedClass = !clazz.isInterface && canonicalClassName.internalClassName !in NOT_TRANSFORMED_JAVA_UTIL_CLASSES
            // no need to transform enum
            val isEnum = clazz.isEnum
            if (!isImpossibleToTransformApi && !isException && !inFunctionPackage && !isEnum && isTransformedClass)
                return true
            return false
        }

        if (canonicalClassName.startsWith("java.util.concurrent.atomic.Atomic") && canonicalClassName.endsWith("FieldUpdater"))
            return false

        if (canonicalClassName.startsWith("sun.") ||
            canonicalClassName.startsWith("java.") ||
            canonicalClassName.startsWith("jdk.internal.") ||
            canonicalClassName.startsWith("kotlin.") &&
            !canonicalClassName.startsWith("kotlin.collections.") &&  // transform kotlin collections
            !(canonicalClassName.startsWith("kotlin.jvm.internal.Array") && canonicalClassName.contains("Iterator")) &&
            !canonicalClassName.startsWith("kotlin.ranges.") ||
            canonicalClassName.startsWith("com.intellij.rt.coverage.") ||
            canonicalClassName.startsWith("org.jetbrains.kotlinx.lincheck.") &&
            !canonicalClassName.startsWith("org.jetbrains.kotlinx.lincheck.test.")
        ) return false

        return true
    }
}