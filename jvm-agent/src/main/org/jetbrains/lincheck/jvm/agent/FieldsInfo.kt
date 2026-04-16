/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import jdk.internal.org.objectweb.asm.Opcodes.ACC_FINAL
import jdk.internal.org.objectweb.asm.Opcodes.ACC_VOLATILE
import org.jetbrains.lincheck.jvm.agent.FieldsInfo.addField
import org.jetbrains.lincheck.jvm.agent.FieldsInfo.collectFieldInformation
import org.jetbrains.lincheck.jvm.agent.FieldsInfo.isFinalField
import java.util.concurrent.ConcurrentHashMap
import org.objectweb.asm.*

/**
 * [FieldsInfo] object is used to track final/volatile fields across different classes.
 * It is used only during byte-code transformation to get information about fields
 * and decide should we track reads of a field or not.
 *
 * During transformation [addField] and [addMutableField] methods are called when
 * we meet a field declaration. Then, when we are faced with field read instruction, [isFinalField] and [isVolatileField] methods
 * are called.
 *
 * However, sometimes due to the order of class processing, we may not have information about this field yet,
 * as its class it is not loaded for now.
 * Then, we fall back to the slow-path algorithm [collectFieldInformation]: we read bytecode and analyze it using [FieldInfoVisitor].
 * If field is found, we record this information and return the result, otherwise we scan superclass and all implemented
 * interfaces in the same way.
 */
// TODO or create a ticket to refactor this and use FieldDescriptor and ClassNode visitor instead.
internal object FieldsInfo {

    /**
     * Stores a map INTERNAL_CLASS_NAME -> { FIELD_NAME -> IS FINAL } for each processed class.
     */
    private val classToFieldsMap = ConcurrentHashMap<String, HashMap<String, FieldInfo>>()

     /**
     * Registers the field [fieldName] as a final field of the class [internalClassName].
     */
    fun addField(internalClassName: String, fieldName: String, access: Int) {
        val fields = classToFieldsMap.computeIfAbsent(internalClassName) { HashMap() }
        fields[fieldName] = FieldsInfo.FieldInfo(access and ACC_FINAL != 0, access and ACC_VOLATILE != 0)
    }

    /**
     * Determines the FieldInfo for the specified [fieldName]. First checks if we have already recorded the information.
     * If not it falls back to the slow path of analyzing the bytecode and stores the results
     */
    private fun getFieldInfo(internalClassName: String, fieldName: String): FieldInfo? {
        val fields = classToFieldsMap.computeIfAbsent(internalClassName) { HashMap() }
        // Fast-path, in case we already have information about this field.
        fields[fieldName]?.let { return it }
        // If we haven't processed this class yet, fall back to a slow-path, reading the class byte-code.
        val fieldFound = collectFieldInformation(internalClassName, fieldName, fields)
        // In case the reading of class byte-code failed, we can't say anything about this field.
        // TODO JBRes-6558 Use `ClassNode` API to collect information about classes/methods/variables
        if (!fieldFound) return null
        // Here we must have information about this field, as we scanned all the hierarchy of this class.
        val fieldInfo = fields[fieldName] ?: error("Internal error: can't find field with $fieldName in class $internalClassName")
        return fieldInfo
    }

    /**
     * Determines if this field is final or not.
     */
    fun isFinalField(internalClassName: String, fieldName: String): Boolean {
        return getFieldInfo(internalClassName, fieldName)?.isFinal ?: false
    }

    /**
     * Determines if this field is volatile or not.
     */
    fun isVolatileField(internalClassName: String, fieldName: String): Boolean {
        return getFieldInfo(internalClassName, fieldName)?.isVolatile ?: false
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
        val classReader = getClassReader(internalClassName) ?: return false
        val visitor = FieldInfoVisitor()
        // Scan class.
        classReader.accept(visitor, 0)
        // Store information about all final and mutable fields.
        visitor.fieldInfoMap.entries.forEach { (field, info) -> fields[field] = info }
        // If the field is found - return it.
        if (fieldName in visitor.fieldInfoMap.keys) return true
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

    private fun getClassReader(internalClassName: String): ClassReader? {
        val resource = "$internalClassName.class"
        val inputStream = ClassLoader.getSystemClassLoader().getResourceAsStream(resource)
            ?: return null
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
    private class FieldInfoVisitor : ClassVisitor(ASM_API) {
        val implementedInterfaces = arrayListOf<String>()
        val fieldInfoMap = hashMapOf<String, FieldInfo>()

        var superClassName: String? = null


        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            superClassName = superName
            interfaces?.let { implementedInterfaces += interfaces }
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitField(access: Int, name: String, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
            fieldInfoMap[name] = FieldInfo(access and Opcodes.ACC_FINAL != 0, access and Opcodes.ACC_VOLATILE != 0)
            return super.visitField(access, name, descriptor, signature, value)
        }
    }

    private data class FieldInfo(
        val isFinal: Boolean,
        val isVolatile: Boolean
    )
}