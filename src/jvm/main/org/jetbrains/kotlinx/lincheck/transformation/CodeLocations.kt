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

import org.jetbrains.kotlinx.lincheck.transformation.FinalFields.FieldInfo.*
import org.jetbrains.kotlinx.lincheck.transformation.FinalFields.addFinalField
import org.jetbrains.kotlinx.lincheck.transformation.FinalFields.addMutableField
import org.jetbrains.kotlinx.lincheck.transformation.FinalFields.collectFieldInformation
import org.jetbrains.kotlinx.lincheck.transformation.FinalFields.isFinalField
import org.jetbrains.kotlinx.lincheck.util.MethodDescriptor
import org.objectweb.asm.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

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
        // actors do not have a code location (for now)
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return EMPTY_STACK_TRACE
        return codeLocations[codeLocationId]
    }
}

internal const val UNKNOWN_CODE_LOCATION_ID = -1
private val EMPTY_STACK_TRACE = StackTraceElement("", "", "", 0)

/**
 * Provides unique IDs for all the methods that are called from the instrumented code.
 * These IDs are used to detect the first recursive call in case of a recursive spin-cycle.
 */
internal val methodCache = IndexedPool<MethodDescriptor>()

// TODO or create a ticket to refactor this and use FieldDescriptor and ClassNode visitor instead.
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
 * Then, we fall back to the slow-path algorithm [collectFieldInformation]: we read bytecode and analyze it using [FinalFieldsVisitor].
 * If field is found, we record this information and return the result, otherwise we scan superclass and all implemented
 * interfaces in the same way.
 */
internal object FinalFields {

    /**
     * Stores a map INTERNAL_CLASS_NAME -> { FIELD_NAME -> IS FINAL } for each processed class.
     */
    private val classToFieldsMap = ConcurrentHashMap<String, HashMap<String, FieldInfo>>()

    /**
     * Registers the field [fieldName] as a final field of the class [internalClassName].
     */
    fun addFinalField(internalClassName: String, fieldName: String) {
        val fields = classToFieldsMap.computeIfAbsent(internalClassName) { HashMap() }
        fields[fieldName] = FINAL
    }

    /**
     * Registers the field [fieldName] as a mutable field of the class [internalClassName].
     */
    fun addMutableField(internalClassName: String, fieldName: String) {
        val fields = classToFieldsMap.computeIfAbsent(internalClassName) { HashMap() }
        fields[fieldName] = MUTABLE
    }

    /**
     * Determines if this field is final or not.
     */
    fun isFinalField(internalClassName: String, fieldName: String): Boolean {
        val fields = classToFieldsMap.computeIfAbsent(internalClassName) { HashMap() }
        // Fast-path, in case we already have information about this field.
        fields[fieldName]?.let { return it == FINAL }
        // If we haven't processed this class yet, fall back to a slow-path, reading the class byte-code.
        collectFieldInformation(internalClassName, fieldName, fields)
        // Here we must have information about this field, as we scanned all the hierarchy of this class.
        val fieldInfo = fields[fieldName] ?: error("Internal error: can't find field with $fieldName in class $internalClassName")
        return fieldInfo == FINAL
    }

    /**
     * The slow-path of deciding if this field is final or not.
     * Reads class from the classloader, scans it and extracts information about declared fields.
     * If the field is not found, recursively searches in the superclass and implemented interfaces.
     */
    private fun collectFieldInformation(
        internalClassName: String,
        fieldName: String,
        fields: MutableMap<String, FieldInfo>
    ): Boolean {
        // Read the class from classLoader.
        val classReader = getClassReader(internalClassName)
        val visitor = FinalFieldsVisitor()
        // Scan class.
        classReader.accept(visitor, 0)
        // Store information about all final and mutable fields.
        visitor.finalFields.forEach { field -> fields[field] = FINAL }
        visitor.mutableFields.forEach { field -> fields[field] = MUTABLE }
        // If the field is found - return it.
        if (fieldName in visitor.finalFields || fieldName in visitor.mutableFields) return true
        // If field is not present in this class - search in the superclass recursively.
        visitor.superClassName?.let { internalSuperClassName ->
            val superClassFields = classToFieldsMap.computeIfAbsent(internalSuperClassName) { hashMapOf() }
            val fieldFound = collectFieldInformation(internalSuperClassName, fieldName, superClassFields)
            // Copy all field information found in the superclass to the current class map.
            addFieldsInfoFromSuperclass(internalSuperClassName, internalClassName)
            if (fieldFound) return true
        }
        // If field is not present in this class - search in the all implemented interfaces recursively.
        visitor.implementedInterfaces.forEach { interfaceName ->
            val interfaceFields = classToFieldsMap.computeIfAbsent(interfaceName) { hashMapOf() }
            val fieldFound = collectFieldInformation(interfaceName, fieldName, interfaceFields)
            // Copy all field information found in the interface to the current class map.
            addFieldsInfoFromSuperclass(interfaceName, internalClassName)
            if (fieldFound) return true
        }
        // There is no such field in this class.
        return false
    }

    private fun getClassReader(internalClassName: String): ClassReader {
        val resource = "$internalClassName.class"
        val inputStream = ClassLoader.getSystemClassLoader().getResourceAsStream(resource)
            ?: error("Cannot create ClassReader for type $internalClassName")
        return inputStream.use { ClassReader(inputStream) }
    }

    /**
     * When processing of a superclass is done, it's important to add all information about
     * parent fields to child map to avoid potential scanning of a child class in the search of a parent field.
     */
    private fun addFieldsInfoFromSuperclass(superType: String, type: String) {
        val superFields = classToFieldsMap[superType] ?: return
        val fields = classToFieldsMap.computeIfAbsent(type) { hashMapOf() }
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
     * This visitor collects information about fields, declared in this class,
     * about superclass and implemented interfaces.
     */
    private class FinalFieldsVisitor : ClassVisitor(ASM_API) {
        val implementedInterfaces = arrayListOf<String>()
        val finalFields = arrayListOf<String>()
        val mutableFields = arrayListOf<String>()

        var superClassName: String? = null


        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            superClassName = superName
            interfaces?.let { implementedInterfaces += interfaces }
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

    private enum class FieldInfo {
        FINAL, MUTABLE
    }
}