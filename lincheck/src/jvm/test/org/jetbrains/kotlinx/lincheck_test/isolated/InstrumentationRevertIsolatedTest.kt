/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.isolated

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.withLincheckJavaAgent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.ClassReader
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

/**
 * Checks that repeated instrumentation install/uninstall cycles do not grow
 * the class file that the JVM passes to the agents on re-transformation.
 *
 * Regression test for https://github.com/JetBrains/lincheck/issues/564:
 * on JDK 20+ every cycle used to feed the previously merged constant pool back into the next merge,
 * doubling the number of duplicated entries until the merged pool exceeded the u2 limit of 65535 entries
 * and `Instrumentation.retransformClasses` started failing.
 */
class InstrumentationRevertIsolatedTest {

    @Test
    fun testConstantPoolDoesNotGrowAcrossInstallUninstallCycles() {
        // A Kotlin stdlib class that Lincheck instruments and that has a large constant pool.
        val targetClass = Class.forName("kotlin.collections.ArraysKt___ArraysKt")
        val constantPoolSizes = (1..INSTALL_UNINSTALL_CYCLES).map {
            withLincheckJavaAgent(InstrumentationMode.MODEL_CHECKING) {
                LincheckInstrumentation.ensureClassHierarchyIsTransformed(targetClass)
            }
            constantPoolSize(targetClass)
        }
        assertEquals(
            "Constant pool of ${targetClass.name} grows on each install/uninstall cycle: $constantPoolSizes",
            1, constantPoolSizes.distinct().size
        )
    }

    /**
     * Re-transforms [clazz] and returns the constant pool size of the class file the JVM hands to the agents.
     */
    private fun constantPoolSize(clazz: Class<*>): Int {
        var constantPoolSize = -1
        val probe = object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?,
                className: String?,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain?,
                classBytes: ByteArray
            ): ByteArray? {
                if (classBeingRedefined != clazz) return null
                constantPoolSize = ClassReader(classBytes).itemCount
                // Hand the bytes back to mark the class as modified by a re-transformation capable agent;
                // returning `null` here would make the JVM drop its cached copy of the original class file,
                // so the measurement itself would trigger the growth it is supposed to detect.
                return classBytes
            }
        }
        val instrumentation = LincheckInstrumentation.instrumentation
        instrumentation.addTransformer(probe, true)
        try {
            instrumentation.retransformClasses(clazz)
        } finally {
            instrumentation.removeTransformer(probe)
        }
        check(constantPoolSize > 0) { "The probe transformer was not called for ${clazz.name}" }
        return constantPoolSize
    }
}

// The growth is exponential, so a handful of cycles is enough to detect it.
private const val INSTALL_UNINSTALL_CYCLES = 10
