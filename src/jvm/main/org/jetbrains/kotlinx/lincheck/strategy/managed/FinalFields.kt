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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.FinalFields.isFinalField
import java.lang.reflect.*

/**
 * [FinalFields] object is used to track final fields across different classes.
 * As a field may be declared in the parent class, [isFinalField] method recursively traverses all the
 * hierarchy to find the field and check it.
 */
internal object FinalFields {

    private val finalFields = HashMap<String, Boolean>() // className + SEPARATOR + fieldName
    private const val SEPARATOR = "$^&*-#"

    /**
     * Checks if the given field of a class is final.
     *
     * @param className Name of the class that contains the field.
     * @param fieldName Name of the field to be checked.
     * @return `true` if the field is final, `false` otherwise.
     */
    fun isFinalField(className: String, fieldName: String): Boolean {
        val fieldKey = className + SEPARATOR + fieldName
        finalFields[fieldKey]?.let { return it }

        val isFinal = try {
            val clazz = Class.forName(className.canonicalClassName)
            val field = findField(clazz, fieldName) ?: throw NoSuchFieldException("No $fieldName in ${clazz.name}")
            (field.modifiers and Modifier.FINAL) == Modifier.FINAL
        } catch (e: ClassNotFoundException) {
            throw RuntimeException(e)
        } catch (e: NoSuchFieldException) {
            throw RuntimeException(e)
        }
        finalFields[fieldKey] = isFinal

        return isFinal
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