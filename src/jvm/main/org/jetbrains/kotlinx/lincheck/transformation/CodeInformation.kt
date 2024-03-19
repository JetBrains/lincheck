/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.LincheckClassLoader.REMAPPED_PACKAGE_INTERNAL_NAME
import java.lang.reflect.Field
import java.lang.reflect.Modifier.*

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
 * [FinalFields] object is used to track final fields across different classes.
 * As a field may be declared in the parent class, [isFinalField] method recursively traverses all the
 * hierarchy to find the field and check it.
 */
internal object FinalFields {

    fun isFinalField(ownerInternal: String, fieldName: String): Boolean {
        var internalName = ownerInternal
        if (internalName.startsWith(REMAPPED_PACKAGE_INTERNAL_NAME)) {
            internalName = internalName.substring(REMAPPED_PACKAGE_INTERNAL_NAME.length)
        }
        return try {
            val clazz = Class.forName(internalName.canonicalClassName)
            val field = findField(clazz, fieldName) ?: throw NoSuchFieldException("No $fieldName in ${clazz.name}")
            (field.modifiers and FINAL) == FINAL
        } catch (e: ClassNotFoundException) {
            throw RuntimeException(e)
        } catch (e: NoSuchFieldException) {
            throw RuntimeException(e)
        }
    }

    private fun findField(clazz: Class<*>?, fieldName: String): Field? {
        if (clazz == null) return null
        val fields = clazz.declaredFields
        for (field in fields) if (field.name == fieldName) return field
        // No field found in this class.
        // Search in super class first, then in interfaces.
        findField(clazz.superclass, fieldName)?.let { return it }
        clazz.interfaces.forEach { iClass ->
            findField(iClass, fieldName)?.let { return it }
        }
        return null
    }
}