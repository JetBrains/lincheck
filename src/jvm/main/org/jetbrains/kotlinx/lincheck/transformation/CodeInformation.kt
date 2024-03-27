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

import org.jetbrains.kotlinx.lincheck.LincheckClassLoader
import org.jetbrains.kotlinx.lincheck.LincheckClassLoader.REMAPPED_PACKAGE_INTERNAL_NAME
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes

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
 * It is used only during byte-code transformation to get information about fields
 * and decide should we track reads of a field or not.
 *
 * During transformation [addFinalField] and [addMutableField] methods are called when
 * we meet a field declaration. Then, when we are faced with field read instruction, [isFinalField] method
 * is called.
 *
 * However, sometimes due to the order of class processing, we may not have information about this field yet,
 * as its class it is not loaded for now.
 * Then, we fall back to the slow-path algorithm [findField]: we read bytecode and analyze it using [FinalFieldsVisitor].
 * If field is found, we record this information and return the result, otherwise we scan superclass and all implemented
 * interfaces in the same way.
 */
internal object FinalFields {

    /**
     * Stores a map FIELD NAME -> IS FINAL for each processed class.
     */
    private val finalFields = HashMap<String, HashMap<String, Boolean>>()

    /**
     * Registers the field [fieldName] as a final field of the class [className]
     */
    fun addFinalField(className: String, fieldName: String) {
        val internalName = className.normalizedClassName
        val fieldsForClass = finalFields[internalName]
        if (fieldsForClass == null) {
            finalFields[className] = hashMapOf(fieldName to true)
        } else {
            fieldsForClass[fieldName] = true
        }
    }

    /**
     * Registers the field [fieldName] as a mutable field of the class [className]
     */
    fun addMutableField(className: String, fieldName: String) {
        val internalName = className.normalizedClassName
        val fieldsForClass = finalFields[internalName]
        if (fieldsForClass == null) {
            finalFields[className] = hashMapOf(fieldName to false)
        } else {
            fieldsForClass[fieldName] = false
        }
    }

    /**
     * Determines if this field is final or not.
     */
    fun isFinalField(classLoader: LincheckClassLoader, className: String, fieldName: String): Boolean {
        val internalName = className.normalizedClassName
        val fieldsForClass = finalFields.computeIfAbsent(internalName) { hashMapOf() }
        // Fast-path, in case we have information about this field
        fieldsForClass[fieldName]?.let { return it }
        // If we haven't processed this class yet, fall back to a slow-path, reading byte-code of a class
        findField(classLoader, internalName, fieldsForClass, fieldName)
        // Here we must have information about this field, as we scanned all the hierarchy of this class
        val isFieldFinal = fieldsForClass[fieldName] ?: error("Internal error: can't find field with $fieldName in class $className")
        return isFieldFinal
    }

    /**
     * When processing of a superclass is done, it's important to add all information about
     * parent fields to child map to avoid potential scanning of a child class in the search of a parent field.
     */
    private fun addFieldsInfoFromSuperclass(superType: String, type: String) {
        val superFields = finalFields[superType] ?: return
        val fields = finalFields.computeIfAbsent(type) { hashMapOf() }
        superFields.forEach { (fieldName, isFinal) ->
            // If we have a field shadowing (the same field name in the child class),
            // it's important not to override this information.
            // For example, we may have a final field X in a parent class, and a mutable field X in a child class.
            // So, while copying information from the parent class to the child class, we mustn't override that field
            // X in the child class is mutable, as it may lead to omitted beforeRead events.
            if (fieldName !in fields) {
                fields[fieldName] = isFinal
            }
        }
    }


    /**
     * The slow-path of deciding if this field is final or not.
     * Reads class from the classloader, scans it and extracts information about declared fields.
     * If the field is not found, recursively searches in the superclass and implemented interfaces.
     */
    private fun findField(
        classLoader: ClassLoader,
        type: String,
        typeIsFinalFieldMap: MutableMap<String, Boolean>,
        fieldName: String
    ): Boolean {
        // Read class from classLoader.
        val classReader = typeInfo(classLoader, type)
        val visitor = FinalFieldsVisitor()
        // Scan class.
        classReader.accept(visitor, 0)
        // Store information about all final and mutable fields.
        visitor.finalFields.forEach { field -> typeIsFinalFieldMap[field] = true }
        visitor.mutableFields.forEach { field -> typeIsFinalFieldMap[field] = false }
        // If the field is found - return it.
        if (fieldName in visitor.finalFields || fieldName in visitor.mutableFields) return true
        // If field is not present in this class - search in the superclass recursively.
        visitor.superClassName?.let { superName ->
            val normalizedSuperName = superName.normalizedClassName
            val superclassFields = finalFields.computeIfAbsent(normalizedSuperName) { hashMapOf() }
            val fieldFound = findField(classLoader, normalizedSuperName, superclassFields, fieldName)
            // Copy all field information found in the superclass to the current class map.
            addFieldsInfoFromSuperclass(normalizedSuperName, type)
            if (fieldFound) return true
        }
        // If field is not present in this class - search in the all implemented interfaces recursively.
        visitor.implementedInterfaces.forEach { interfaceName ->
            val normalizedInterfaceName = interfaceName.normalizedClassName
            val interfaceFields = finalFields.computeIfAbsent(normalizedInterfaceName) { hashMapOf() }
            val fieldFound = findField(classLoader, normalizedInterfaceName, interfaceFields, fieldName)
            // Copy all field information found in the interface to the current class map.
            addFieldsInfoFromSuperclass(normalizedInterfaceName, type)
            if (fieldFound) return true
        }
        // There is no such field in this class.
        return false
    }

    private fun typeInfo(classLoader: ClassLoader, type: String): ClassReader {
        val resource = "$type.class"
        val inputStream = classLoader.getResourceAsStream(resource) ?: error("Cannot create ClassReader for type $type")
        return inputStream.use { ClassReader(inputStream) }
    }

    private val String.normalizedClassName: String
        get() {
            var internalName = this
            if (internalName.startsWith(REMAPPED_PACKAGE_INTERNAL_NAME)) {
                internalName = internalName.substring(REMAPPED_PACKAGE_INTERNAL_NAME.length)
            }
            return internalName
        }

    /**
     * This visitor collects information about fields, declared in this class,
     * about superclass and implemented interfaces.
     */
    private class FinalFieldsVisitor : ClassVisitor(Opcodes.ASM9) {

        var superClassName: String? = null
        lateinit var implementedInterfaces: Array<String>
        val finalFields = arrayListOf<String>()
        val mutableFields = arrayListOf<String>()

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            superClassName = superName
            implementedInterfaces = interfaces ?: emptyArray()
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitField(access: Int, name: String, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
            if ((access and Opcodes.ACC_FINAL) != 0) {
                finalFields.add(name)
            } else {
                mutableFields.add(name)
            }
            return super.visitField(access, name, descriptor, signature, value)
        }
    }

}