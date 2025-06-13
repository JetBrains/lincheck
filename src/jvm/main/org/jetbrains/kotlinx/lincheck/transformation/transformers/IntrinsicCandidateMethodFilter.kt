/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.jetbrains.kotlinx.lincheck.transformation.ASM_API
import org.jetbrains.kotlinx.lincheck.transformation.getInterned
import org.jetbrains.kotlinx.lincheck.transformation.methodCache
import org.jetbrains.kotlinx.lincheck.transformation.toCanonicalClassName
import org.jetbrains.kotlinx.lincheck.util.MethodDescriptor
import org.jetbrains.kotlinx.lincheck.util.isTrackedIntrinsic
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.MethodVisitor


internal class IntrinsicCandidateMethodFilter(
    private val className: String,
    private val methodName: String,
    private val methodDesc: String,
    private val initialAdapter: MethodVisitor,
    nextAdapter: MethodVisitor
) : MethodVisitor(ASM_API, nextAdapter) {

    override fun visitCode() {
        // Java 8 does not have `@HotSpotIntrinsicCandidate`/`@IntrinsicCandidate` annotations, thus,
        // here we manually specify intrinsic methods that could lead to error in lincheck analysis.
        // Also, some methods are intrinsified even though they do not have mentioned annotations
        // (such as Arrays.copyOf(...) methods).
        val methodDescriptor = MethodDescriptor(className.toCanonicalClassName(), methodName, methodDesc)
        if (methodDescriptor.isTrackedIntrinsic()) {
            methodCache.getInterned(methodDescriptor).isIntrinsic = true
            delegate()
        }
        return super.visitCode()
    }

    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
        val methodDescriptor = MethodDescriptor(className.toCanonicalClassName(), methodName, methodDesc)
        if (isIntrinsicCandidateAnnotation(desc)) {
            methodCache.getInterned(methodDescriptor).isIntrinsic = true
            delegate()
        }
        return super.visitAnnotation(desc, visible)
    }

    /**
     * Changes the delegate [MethodVisitor] to redirect to transformer "further" in the chain.
     * Essentially it allows to skip all transformers between `nextAdapter` and `initialAdapter`.
     */
    private fun delegate() {
        this.mv = initialAdapter
    }

    private fun isIntrinsicCandidateAnnotation(annotation: String): Boolean = (
        annotation == "Ljdk/internal/HotSpotIntrinsicCandidate;" /* from java 9 to java 16 */ ||
        annotation == "Ljdk/internal/vm/annotation/IntrinsicCandidate;" /* for java 17 and after */
    )
}
