/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

// we need to use some "legal" package for the bootstrap class loader
@file:Suppress("PackageDirectoryMismatch")

package sun.nio.ch.lincheck

import sun.misc.*
import java.lang.reflect.Modifier.*
import java.util.concurrent.atomic.*

/**
 * [CodeLocations] object is used to maintain the mapping between unique IDs and code locations.
 * When Lincheck detects an error in the model checking mode, it provides a detailed interleaving trace.
 * This trace includes a list of all shared memory events that occurred during the execution of the program,
 * along with their corresponding code locations. To minimize overhead, Lincheck assigns unique IDs to all
 * code locations it analyses, and stores more detailed information necessary for trace generation in this object.
 */
internal object CodeLocations {
    private val codeLocations = ArrayList<StackTraceElement>()

    /**
     * Registers a new code location and returns its unique ID.
     *
     * @param stackTraceElement Stack trace element representing the new code location.
     * @return Unique ID of the new code location.
     */
    @JvmStatic
    @Synchronized
    fun newCodeLocation(stackTraceElement: StackTraceElement): Int {
        val id = codeLocations.size
        codeLocations.add(stackTraceElement)
        return id
    }

    /**
     * Returns the [StackTraceElement] associated with the specified code location ID.
     *
     * @param codeLocationId ID of the code location.
     * @return [StackTraceElement] corresponding to the given ID.
     */
    @JvmStatic
    @Synchronized
    fun stackTrace(codeLocationId: Int): StackTraceElement {
        return codeLocations[codeLocationId]
    }
}

/**
 * [AtomicFields] is used to map atomic objects to their names (usually, the corresponding field names)
 * and class owners. The weak identity hash map ensures that atomic objects are compared using reference
 * equality and does not prevent them from being garbage collected.
 */
internal object AtomicFields {
    private val unsafe: Unsafe =
        try {
            val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            unsafeField.get(null) as Unsafe
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }

    fun getAtomicFieldName(updater: Any): String? {
        if (updater !is AtomicIntegerFieldUpdater<*> &&
            updater !is AtomicLongFieldUpdater<*> &&
            updater !is AtomicReferenceFieldUpdater<*, *>) {
            throw IllegalArgumentException("Provided object is not a recognized Atomic*FieldUpdater type.")
        }

        try {
            // Cannot use both reflection and MethodHandles.Lookup, as they lead to a warning.
            val tclassField = updater.javaClass.getDeclaredField("tclass")
            val targetType = unsafe.getObject(updater, unsafe.objectFieldOffset(tclassField)) as Class<*>

            val offsetField = updater.javaClass.getDeclaredField("offset")
            val offset = unsafe.getLong(updater, unsafe.objectFieldOffset(offsetField))

            for (field in targetType.declaredFields) {
                try {
                    if (isNative(field.modifiers)) continue
                    val fieldOffset = if (isStatic(field.modifiers)) unsafe.staticFieldOffset(field)
                                            else unsafe.objectFieldOffset(field)
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

/**
 * [FinalFields] object is used to track final fields across different classes.
 * It is used to maintain a set of all final fields and
 * provides helper functions to add and check final fields.
 */
internal object FinalFields {
    private val finalFields = HashSet<String>() // className + SEPARATOR + fieldName
    private const val SEPARATOR = "$^&*-#"

    /**
     * Adds a new final field to the set.
     *
     * @param className Name of the class that contains the field.
     * @param fieldName Name of the final field.
     */
    fun addFinalField(className: String, fieldName: String) {
        finalFields.add(className + SEPARATOR + fieldName)
    }

    /**
     * Checks if the given field of a class is final.
     *
     * @param className Name of the class that contains the field.
     * @param fieldName Name of the field to be checked.
     * @return `true` if the field is final, `false` otherwise.
     */
    fun isFinalField(className: String, fieldName: String) =
        finalFields.contains(className + SEPARATOR + fieldName)
}

internal object CoroutineInternalCallTracker {
    private val coroutineInternalClasses = HashSet<String>()

    init {
        coroutineInternalClasses += "kotlinx/coroutines/CancellableContinuationImpl"
        coroutineInternalClasses += "kotlin/coroutines/jvm/internal/ContinuationImpl"
        coroutineInternalClasses += "kotlin/coroutines/intrinsics/IntrinsicsKt"
    }

    fun isCoroutineInternalClass(internalClassName: String): Boolean =
        internalClassName in coroutineInternalClasses
}
