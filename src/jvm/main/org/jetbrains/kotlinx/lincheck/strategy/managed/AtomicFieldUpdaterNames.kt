/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder.UNSAFE
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicLongFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

/**
 * [AtomicFieldUpdaterNames] is used to map atomic objects to their names (usually, the corresponding field names)
 * and class owners. The weak identity hash map ensures that atomic objects are compared using reference
 * equality and does not prevent them from being garbage collected.
 */
internal object AtomicFieldUpdaterNames {
    fun getAtomicFieldUpdaterName(updater: Any): String? {
        if (updater !is AtomicIntegerFieldUpdater<*> && updater !is AtomicLongFieldUpdater<*> && updater !is AtomicReferenceFieldUpdater<*, *>) {
            throw IllegalArgumentException("Provided object is not a recognized Atomic*FieldUpdater type.")
        }
        // Extract the private offset value and find the matching field.
        try {
            // Cannot use neither reflection not MethodHandles.Lookup, as they lead to a warning.
            val tclassField = updater.javaClass.getDeclaredField("tclass")
            val targetType = UNSAFE.getObject(updater, UNSAFE.objectFieldOffset(tclassField)) as Class<*>

            val offsetField = updater.javaClass.getDeclaredField("offset")
            val offset = UNSAFE.getLong(updater, UNSAFE.objectFieldOffset(offsetField))

            for (field in targetType.declaredFields) {
                try {
                    if (Modifier.isNative(field.modifiers)) continue
                    val fieldOffset = if (Modifier.isStatic(field.modifiers)) UNSAFE.staticFieldOffset(field)
                    else UNSAFE.objectFieldOffset(field)
                    if (fieldOffset == offset) return field.name
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        return null // Field not found
    }
}