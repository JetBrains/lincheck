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
import org.jetbrains.kotlinx.lincheck.transformation.MethodIds
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.MethodVisitor


internal class IntrinsicCandidateMethodFilter(
    private val className: String,
    private val methodName: String,
    private val methodDesc: String,
    private val initialAdapter: MethodVisitor,
    nextAdapter: MethodVisitor
) : MethodVisitor(ASM_API, nextAdapter) {

    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
        if (isIntrinsicCandidateAnnotation(desc)) {
            MethodIds.registerIntrinsicMethod(className, methodName, methodDesc)
            // Change the delegate to redirect to transformer "further" in the chain
            this.mv = initialAdapter
        }
        return super.visitAnnotation(desc, visible)
    }

    private fun isIntrinsicCandidateAnnotation(annotation: String): Boolean = (
        annotation == "Ljdk/internal/HotSpotIntrinsicCandidate;" /* before java 16 */ ||
        annotation == "Ljdk/internal/vm/annotation/IntrinsicCandidate;"
    )
}
