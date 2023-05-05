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

import org.jetbrains.kotlinx.lincheck.*
import org.objectweb.asm.commons.*
import org.reflections.*
import org.reflections.scanners.*

/**
 * Changes package of transformed classes from `java.util` package, excluding some,
 * because transformed classes can not lie in `java.util`
 */
internal class JavaUtilRemapper : Remapper() {
    override fun map(name: String): String {
        if (name.startsWith("java/util/")) {
            val normalizedName = name.canonicalClassName
            val originalClass = ClassLoader.getSystemClassLoader().loadClass(normalizedName)
            // transformation of exceptions causes a lot of trouble with catching expected exceptions
            val isException = Throwable::class.java.isAssignableFrom(originalClass)
            // function package is not transformed, because AFU uses it, and thus, there will be transformation problems
            val inFunctionPackage = name.startsWith("java/util/function/")
            // some api classes that provide low-level access can not be transformed
            val isImpossibleToTransformApi = isImpossibleToTransformApiClass(normalizedName)
            // interfaces are not transformed by default and are in the special set when they should be transformed
            val isTransformedInterface = originalClass.isInterface && originalClass.name.internalClassName in TRANSFORMED_JAVA_UTIL_INTERFACES
            // classes are transformed by default and are in the special set when they should not be transformed
            val isTransformedClass = !originalClass.isInterface && originalClass.name.internalClassName !in NOT_TRANSFORMED_JAVA_UTIL_CLASSES
            // no need to transform enum
            val isEnum = originalClass.isEnum
            if (!isImpossibleToTransformApi && !isException && !inFunctionPackage && !isEnum && (isTransformedClass || isTransformedInterface))
                return TransformationClassLoader.REMAPPED_PACKAGE_INTERNAL_NAME + name
        }
        return name
    }
}

/**
 * Finds the transformation problems related to the `java.util`
 * remapping in standard java packages. Use it for each JDK update
 * to check the consistency of the related work-arounds.
 */
fun main() = listOf(
    "java.util",
    "java.lang",
    "java.math",
    "java.text",
    "java.time"
).forEach { findAllTransformationProblemsIn(it) }

/**
 * Finds transformation problems because of `java.util` in the given package.
 * Note that it supposes that all classes, other than `java.util` classes, are not transformed,
 * because transformed classes do not cause problems.
 *
 * There are still some problems left, but with rarely used classes such as `java.lang.System`.
 */
private fun findAllTransformationProblemsIn(packageName: String) {
    val classes = getAllClasses(packageName).map { Class.forName(it) }
    for (clazz in classes) {
        if (clazz.name.startsWith("java.util")) {
            // skip if the class is transformed
            if (clazz.isInterface && clazz.name.internalClassName in TRANSFORMED_JAVA_UTIL_INTERFACES) continue
            if (!clazz.isInterface && clazz.name.internalClassName !in NOT_TRANSFORMED_JAVA_UTIL_CLASSES) continue
        }
        val superInterfaces = clazz.interfaces
        superInterfaces.firstOrNull { it.name.internalClassName in TRANSFORMED_JAVA_UTIL_INTERFACES }?.let {
            println("CONFLICT: ${clazz.name} is not transformed, but its sub-interface ${it.name} is" )
        }
        clazz.superclass?.let {
            if (it.causesTransformationProblem)
                println("CONFLICT: ${clazz.name} is not transformed, but its subclass ${it.name} is")
        }
        for (f in clazz.fields) {
            if (f.type.causesTransformationProblem)
                println("CONFLICT: ${clazz.name} is not transformed, but its public field of type `${f.type.name}` is")
        }
        for (m in clazz.methods) {
            if (m.returnType.causesTransformationProblem)
                println("CONFLICT: ${clazz.name} is not transformed, but the return type ${m.returnType.name} in its method `${m.name}` is")
            for (p in m.parameterTypes) {
                if (p.causesTransformationProblem)
                    println("CONFLICT: ${clazz.name} is not transformed, but the parameter type ${p.name} in its method `${m.name}` is")
            }
        }
    }
}

private fun getAllClasses(packageName: String): List<String> {
    check(packageName != "java") { "For some reason unable to find classes in `java` package. Use more specified name such as `java.util`" }
    val reflections = Reflections(
        packageName.replace('.', '/'),
        SubTypesScanner(false)
    )
    return reflections.allTypes.toList()
}

/**
 * Checks whether class causes transformation problem if is used from a non-transformed class.
 * Transformed `java.util` classes cause such problems, because they are moved to another package.
 */
private val Class<*>.causesTransformationProblem: Boolean get() = when {
    !name.startsWith("java.util.") -> false
    isEnum -> false
    isArray -> this.componentType.causesTransformationProblem
    isInterface -> name.internalClassName in TRANSFORMED_JAVA_UTIL_INTERFACES
    else -> name.internalClassName !in NOT_TRANSFORMED_JAVA_UTIL_CLASSES
}