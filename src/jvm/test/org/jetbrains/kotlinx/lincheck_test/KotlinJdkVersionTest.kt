/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import junit.framework.TestCase.assertEquals
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.util.TestJdkVersion
import org.jetbrains.kotlinx.lincheck_test.util.isJdk8
import org.jetbrains.kotlinx.lincheck_test.util.testJdkVersion
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * The test checks that if it is run on new JDK (version > 8), its target bytecode version is also new.
 * 
 * Kotlin compiler uses INVOKEDYNAMIC instruction on newer versions of JDK (> 8) to compile string templates.
 * If the test is run on the newer JDKs, the generated bytecode must contain INVOKEDYNAMIC.
 */
class KotlinJdkVersionTest {
    fun invokeDynamicUser() = "$this $this"
    
    @Test
    fun test() {
        val className = this::class.java.name
        val classLoader = this::class.java.classLoader
        val classBytes = classLoader.getResourceAsStream(className.replace('.', '/') + ".class")?.readBytes()
            ?: throw IllegalArgumentException("Could not find class $className")

        val classReader = ClassReader(classBytes)
        val classNode = ClassNode()
        classReader.accept(classNode, 0)
        
        val targetMethod = classNode.methods.find { it.name == "invokeDynamicUser" }
            ?: throw IllegalArgumentException("Method invokeDynamicUser not found in $className")
        
        val containsInvokeDynamic = targetMethod.instructions.any { insn -> insn.opcode == Opcodes.INVOKEDYNAMIC }

        // https://github.com/JetBrains/lincheck/issues/500
        // For trace debugger mode there is a hack because JRE 8 has a bug leading to JVM crash on CI,
        // so we need to run trace debugger tests compiled with JDK 8 with some other JDK.
        // I chose JRE 17 as it is the default one.
        assumeFalse(isInTraceDebuggerMode && testJdkVersion == TestJdkVersion.JDK_17 && classNode.version == 52)
        
        val shouldContainInvokeDynamic = !isJdk8
        
        assertEquals(shouldContainInvokeDynamic, containsInvokeDynamic)
    }
}
