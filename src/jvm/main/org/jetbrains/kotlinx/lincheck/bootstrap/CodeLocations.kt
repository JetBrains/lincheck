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

package sun.nio.ch.lincheck

/**
When Lincheck detects an error, it provides a detailed interleaving trace in the model checking mode.
This trace includes a list of all the shared memory events that occurred during the execution of the program,
along with their corresponding code locations. To minimize overhead, Lincheck assigns unique IDs to all
the code locations that it analyzes, storing more detailed information necessary for trace generation here.
 */
internal object CodeLocations {
    private val codeLocations = ArrayList<StackTraceElement>()

    /**
     * Creates a new code locations specified via [stackTraceElement] and returns its id.
     */
    @JvmStatic
    @Synchronized
    fun newCodeLocation(stackTraceElement: StackTraceElement): Int {
        val id = codeLocations.size
        codeLocations.add(stackTraceElement)
        return id
    }

    /**
     * Gets the [StackTraceElement] associated with the specified [code location][codeLocationId].
     */
    @JvmStatic
    @Synchronized
    fun stackTrace(codeLocationId: Int): StackTraceElement {
        return codeLocations[codeLocationId]
    }
}

internal object AtomicFieldNameMapper {
    private val names = WeakIdentityHashMap<Any, String>()

    @Synchronized
    fun newAtomic(atomicObject: Any, name: String) {
        names.put(atomicObject, name)
    }

    @Synchronized
    fun name(atomicObject: Any): String? = names[atomicObject]
}

internal object FinalFields {
    private val finalFields = HashSet<String>() // <className, fieldName>

    fun addFinalField(className: String, fieldName: String) {
        finalFields.add(className + DELIMITER + fieldName)
    }

    fun isFinalField(className: String, fieldName: String) =
        finalFields.contains(className + DELIMITER + fieldName)

    private const val DELIMITER = "$^&*-#"
}
