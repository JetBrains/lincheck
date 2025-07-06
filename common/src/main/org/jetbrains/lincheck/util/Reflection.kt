/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.lang.reflect.Field
import java.util.ArrayList

/**
 * Returns all found fields in the hierarchy.
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
val Class<*>.allDeclaredFieldWithSuperclasses get(): List<Field> {
    val fields: MutableList<Field> = ArrayList<Field>()
    var currentClass: Class<*>? = this
    while (currentClass != null) {
        val declaredFields: Array<Field> = currentClass.declaredFields
        fields.addAll(declaredFields)
        currentClass = currentClass.superclass
    }
    return fields
}

/**
 * Finds the field name of [this] object that directly references the given object [obj].
 *
 * @param this the object which fields are look-up.
 * @param obj the target object to search for in instance fields of [this].
 * @return the name of the field that references the given object, or null if no such field is found.
 */
fun Any.findInstanceFieldReferringTo(obj: Any): Field? {
    for (field in this.javaClass.allDeclaredFieldWithSuperclasses) {
        if (readFieldSafely(this, field).getOrNull() === obj) {
            return field
        }
    }
    return null
}