/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.getObjectNumber
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.coroutines.Continuation

/**
 * Traverses an object to enumerate it and all nested objects.
 * Enumeration is required for the Plugin as we want to see on the diagram if some object was replaced by a new one.
 * Uses the same a numeration map as TraceReporter via [getObjectNumber] method, so objects have the
 * same numbers, as they have in the trace.
 */
internal fun enumerateObjects(obj: Any): Map<Any, Int> {
    val objectNumberMap = hashMapOf<Any, Int>()
    enumerateObjects(obj, objectNumberMap)
    return objectNumberMap
}

/**
 * Recursively traverses an object to enumerate it and all nested objects.
 *
 * @param obj object to traverse
 * @param objectNumberMap result enumeration map
 */
private fun enumerateObjects(obj: Any, objectNumberMap: MutableMap<Any, Int>) {
    if (obj is Class<*> || obj is ClassLoader) return
    objectNumberMap[obj] = getObjectNumber(obj.javaClass, obj)

    val processObject: (Any?) -> Any? = { value: Any? ->
        if (value == null || value is Class<*> || value is ClassLoader) null
        else {
            // We jump through most of the atomic classes
            var jumpObj: Any? = value

            // Special treatment for java atomic classes, because they can be extended but user classes,
            // in case if a user extends java atomic class, we do not want to jump through it.
            while (jumpObj?.javaClass?.name != null && isAtomicJavaClass(jumpObj.javaClass.name)) {
                jumpObj = jumpObj.javaClass.getMethod("get").invoke(jumpObj)
            }

            if (isAtomicFU(jumpObj)) {
                val readNextJumpObjectByFieldName = { fieldName: String ->
                    readFieldViaUnsafe(jumpObj, jumpObj?.javaClass?.getDeclaredField(fieldName)!!)
                }

                while (jumpObj is kotlinx.atomicfu.AtomicRef<*>) {
                    jumpObj = readNextJumpObjectByFieldName("value")
                }

                if (isAtomicFU(jumpObj)) {
                    jumpObj =
                        if (jumpObj is kotlinx.atomicfu.AtomicBoolean) readNextJumpObjectByFieldName("_value")
                        else readNextJumpObjectByFieldName("value")
                }
            }

            if (jumpObj != null) {
                objectNumberMap[jumpObj] = getObjectNumber(jumpObj.javaClass, jumpObj)
                if (shouldAnalyseObjectRecursively(jumpObj, objectNumberMap)) jumpObj else null
            }
            else null
        }
    }

    traverseObjectGraph(
        obj,
        onArrayElement = { _, _, value ->
            if (value?.javaClass?.isEnum == true) {
                null
            }
            else {
                try {
                    processObject(value)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }
            }
        },
        onField = { _, f, value ->
            if (f.isEnumConstant || f.name == "serialVersionUID") {
                null
            }
            else {
                try {
                    processObject(value)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }
            }
        }
    )
}

/**
 * Determines should we dig recursively into this object's fields.
 */
private fun shouldAnalyseObjectRecursively(obj: Any?, objectNumberMap: MutableMap<Any, Int>): Boolean {
    if (obj == null || obj.isImmutable)
        return false

    if (obj is CharSequence) {
        return false
    }
    if (obj is Continuation<*>) {
        objectNumberMap[obj] = getObjectNumber(obj.javaClass, obj)
        return false
    }
    return true
}
