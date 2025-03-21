/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.PredefinedTypes.ARRAY_OF_OBJECTS_TYPE
import org.jetbrains.kotlinx.lincheck.util.PredefinedTypes.CLASS_TYPE
import sun.nio.ch.lincheck.MethodSignature
import sun.nio.ch.lincheck.Types.*

internal object IntrinsicCallNames {

    internal fun getIntrinsicMethodCallType(owner: String, methodSignature: MethodSignature): IntrinsicCallType {
        val methodName = methodSignature.name
        val argumentTypes = methodSignature.methodType.argumentTypes
        val returnType = methodSignature.methodType.returnType

        when {
            owner == "java.util.Arrays" -> when {
                methodName == "copyOf" &&
                returnType == ARRAY_OF_OBJECTS_TYPE &&
                argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE, CLASS_TYPE) -> return IntrinsicCallType.ArraysCopyOfIntrinsicCall

                methodName == "copyOfRange" &&
                returnType == ARRAY_OF_OBJECTS_TYPE &&
                argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE, INT_TYPE, CLASS_TYPE) -> return IntrinsicCallType.ArraysCopyOfIntrinsicCall
            }
        }

        return IntrinsicCallType.UnknownIntrinsicCall
    }
}

internal sealed interface IntrinsicCallType {
    data object UnknownIntrinsicCall : IntrinsicCallType
    data object ArraysCopyOfIntrinsicCall : IntrinsicCallType
    data object ArraysCopyOfRangeIntrinsicCall : IntrinsicCallType
}