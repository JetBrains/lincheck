/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.conditions

import org.jetbrains.lincheck.jvm.agent.analysis.*
import org.jetbrains.lincheck.jvm.agent.conditions.ConditionTestUtils.MethodInfo
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.*
import org.junit.runners.*
import org.junit.runners.Parameterized.*
import java.io.*

/**
 * Tests for UNSAFE condition functions - all should fail compile-time safety verification.
 * Test cases are automatically generated from methods in [UnsafeConditions] using ASM.
 */
@RunWith(Parameterized::class)
class UnsafeConditionsTest(
    private val methodInfo: MethodInfo
) {
    @Test
    fun test() {
        val className = UnsafeConditions::class.java.name

        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            className,
            methodInfo.name,
            methodInfo.descriptor,
            this::class.java.classLoader
        )
        assertNotNull(
            "Method ${methodInfo.name} should be unsafe but no violations found",
            violation
        )
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun unsafeMethods(): Array<MethodInfo> =
            ConditionTestUtils.discoverTestMethods(UnsafeConditions::class.java)
    }
}

/**
 * Contains UNSAFE condition functions - all should fail compile-time safety verification.
 * These functions contain write operations, side effects, or unsafe method calls.
 */
private object UnsafeConditions {

    @JvmStatic
    var staticCounter: Int = 0

    @JvmStatic
    var mutableList: MutableList<Int> = mutableListOf()

    // ============ STATIC FIELD WRITES ============

    @JvmStatic
    fun writeStaticField() {
        staticCounter = 10
    }

    @JvmStatic
    fun incrementStaticField() {
        staticCounter++
    }

    @JvmStatic
    fun compoundAssignStaticField() {
        staticCounter += 5
    }

    // ============ ARRAY WRITES ============

    @JvmStatic
    fun writeIntArray(): IntArray {
        val arr = IntArray(5)
        arr[0] = 10  // Array write
        return arr
    }

    @JvmStatic
    fun writeArrayInLoop(): IntArray {
        val arr = IntArray(10)
        for (i in arr.indices) {
            arr[i] = i  // Array write
        }
        return arr
    }

    // ============ I/O OPERATIONS ============

    @JvmStatic
    fun readFile(): String {
        return File("test.txt").readText()
    }

    @JvmStatic
    fun writeFile() {
        File("test.txt").writeText("content")
    }

    @JvmStatic
    fun printToConsole() {
        println("Hello")
    }

    // ============ THREAD OPERATIONS ============

    @JvmStatic
    fun startThread() {
        Thread { }.start()
    }

    @JvmStatic
    fun sleepThread() {
        Thread.sleep(100)
    }

    // ============ REFLECTION OPERATIONS ============

    @JvmStatic
    fun setFieldViaReflection(obj: Any, value: Int) {
        val field = obj.javaClass.getDeclaredField("instanceField")
        field.isAccessible = true
        field.set(obj, value)
    }

    @JvmStatic
    fun invokeMethodViaReflection(obj: Any) {
        val method = obj.javaClass.getDeclaredMethod("writeStaticField")
        method.invoke(obj)
    }

    // ============ RECURSION ============

    @JvmStatic
    fun recursiveFunction(x: Int): Int {
        return if (x <= 0) 0 else recursiveFunction(x - 1)
    }

    @JvmStatic
    fun mutuallyRecursiveA(x: Int): Int {
        return if (x <= 0) 0 else mutuallyRecursiveB(x - 1)
    }

    @JvmStatic
    fun mutuallyRecursiveB(x: Int): Int {
        return if (x <= 0) 0 else mutuallyRecursiveA(x - 1)
    }

    // ============ NON-TRIVIAL UNSAFE CASES ============

    @JvmStatic
    fun callsUnsafeMethod(x: Int): Int {
        incrementStaticField()  // Calls unsafe function
        return x
    }

    // ============ ADDITIONAL UNSAFE CASES ============

    @JvmStatic
    fun modifyMutableCollection(): Boolean {
        mutableList.add(42)  // Collection modification
        return true
    }

    @JvmStatic
    fun clearCollection(): Boolean {
        mutableList.clear()  // Collection modification
        return true
    }

    @JvmStatic
    fun modifyMapEntry(): Boolean {
        val map = mutableMapOf("key" to 1)
        map["key"] = 2  // Map modification
        return true
    }

    @JvmStatic
    fun arrayWriteAfterRead(arr: IntArray): Int {
        val value = arr[0]  // Read is safe
        arr[1] = value + 1  // But write is unsafe
        return value
    }

    @JvmStatic
    fun stringBuilderMutation(): String {
        val sb = StringBuilder()
        sb.append("test")  // Mutation
        return sb.toString()
    }

    @JvmStatic
    fun arrayListCreation(): Int {
        val list = ArrayList<Int>()
        list.add(1)  // Mutation
        list.add(2)
        return list.size
    }

    @JvmStatic
    fun setFieldOnObject(obj: Any, field: String) {
        // Would fail at runtime but bytecode shows field write
        val clazz = obj.javaClass
        val f = clazz.getDeclaredField(field)
        f.isAccessible = true
        f.set(obj, 42)
    }

    @JvmStatic
    fun multipleArrayWrites(arr: IntArray): IntArray {
        arr[0] = 1
        arr[1] = 2
        arr[2] = 3
        return arr
    }

    @JvmStatic
    fun nestedArrayWrite(matrix: Array<IntArray>) {
        matrix[0][0] = 99
    }

    @JvmStatic
    fun lambdaWithCapture(): () -> Int {
        var counter = 0  // Local variable write
        return { counter++ }  // Lambda captures mutable variable
    }

    @JvmStatic
    fun systemPropertyWrite() {
        System.setProperty("test", "value")
    }

    @JvmStatic
    fun staticFieldReadThenWrite(): Int {
        val current = staticCounter  // Read is safe
        staticCounter = current + 1  // Write is unsafe
        return current
    }

    // ============ READING STATIC FIELDS (UNSAFE DUE TO GETSTATIC ON UNLOADED CLASS) ============

    @JvmStatic
    fun readStaticField(): Int = staticCounter

    @JvmStatic
    fun staticFieldPlusConstant(): Int = staticCounter + 100

    @JvmStatic
    fun compareStaticField(x: Int): Boolean = x > staticCounter

    @JvmStatic
    fun tryWithResources(): String {
        File("test.txt").bufferedReader().use {
            return it.readLine()
        }
    }

    // ============ SYNCHRONIZED BLOCKS ============

    @JvmStatic
    fun synchronizedBlock(lock: Any): Int {
        synchronized(lock) {
            return 42
        }
    }

    // ============ LAMBDAS AND DYNAMIC INVOCATIONS ============

    @JvmStatic
    fun simpleLambda(): () -> Int {
        return { 42 }
    }

    @JvmStatic
    fun lambdaWithParameter(): (Int) -> Int {
        return { x -> x * 2 }
    }

    @JvmStatic
    fun usesLambda(x: Int): Int {
        val func = { y: Int -> y + 1 }
        return func(x)
    }

    // ============ NON-FINAL METHOD TESTS ============

    // Non-final, non-private, non-static method that can be overridden
    @JvmStatic
    fun nonFinalMethod(x: Int): Int = OpenClass().callsOverridableMethod(0)

    // Open class with non-final methods
    open class OpenClass {
        open fun overridableMethod(x: Int): Int = x * 2

        fun callsOverridableMethod(x: Int): Int =
            overridableMethod(x) + 1
    }

    @JvmStatic
    fun callsOpenClassMethod(obj: OpenClass, x: Int): Int =
        obj.overridableMethod(x)

    // ============ ADDITIONAL FIELD WRITE TESTS ============

    @JvmStatic
    var instanceField: Int = 0

    @JvmStatic
    var volatileField: Int = 0

    @JvmStatic
    fun writeInstanceField(obj: TestObject) {
        obj.value = 42  // Instance field write
    }

    @JvmStatic
    fun incrementInstanceField(obj: TestObject) {
        obj.value++  // Instance field write
    }

    @JvmStatic
    fun writeVolatileField() {
        volatileField = 100  // Volatile field write
    }

    @JvmStatic
    fun writeFieldInCondition(flag: Boolean) {
        if (flag) {
            staticCounter = 1  // Field write in conditional
        }
    }

    @JvmStatic
    fun writeFieldInLoop() {
        for (i in 1..5) {
            staticCounter = i  // Field write in loop
        }
    }

    // ============ ADDITIONAL ARRAY WRITE TESTS ============

    @JvmStatic
    fun writeBooleanArray(arr: BooleanArray) {
        arr[0] = true
    }

    @JvmStatic
    fun writeByteArray(arr: ByteArray) {
        arr[0] = 1
    }

    @JvmStatic
    fun writeCharArray(arr: CharArray) {
        arr[0] = 'A'
    }

    @JvmStatic
    fun writeShortArray(arr: ShortArray) {
        arr[0] = 1
    }

    @JvmStatic
    fun writeLongArray(arr: LongArray) {
        arr[0] = 1L
    }

    @JvmStatic
    fun writeFloatArray(arr: FloatArray) {
        arr[0] = 1.0f
    }

    @JvmStatic
    fun writeDoubleArray(arr: DoubleArray) {
        arr[0] = 1.0
    }

    @JvmStatic
    fun writeObjectArray(arr: Array<String>) {
        arr[0] = "test"
    }

    @JvmStatic
    fun writeArrayWithComputedIndex(arr: IntArray, index: Int) {
        arr[index * 2] = 42
    }

    // ============ COLLECTION MODIFICATION TESTS ============

    @JvmStatic
    fun addToList(list: MutableList<Int>) {
        list.add(42)
    }

    @JvmStatic
    fun removeFromList(list: MutableList<Int>) {
        list.removeAt(0)
    }

    @JvmStatic
    fun addToSet(set: MutableSet<Int>) {
        set.add(42)
    }

    @JvmStatic
    fun putInMap(map: MutableMap<String, Int>) {
        map["key"] = 42
    }

    @JvmStatic
    fun removeFromMap(map: MutableMap<String, Int>) {
        map.remove("key")
    }

    // ============ DISALLOWED STANDARD LIBRARY CALLS ============

    @JvmStatic
    fun callSystemExit() {
        System.exit(0)
    }

    @JvmStatic
    fun callSystemGc() {
        System.gc()
    }

    @JvmStatic
    fun callRuntimeExec() {
        Runtime.getRuntime().exec("ls")
    }

    @JvmStatic
    fun createRandomInstance() {
        java.util.Random().nextInt()
    }

    @JvmStatic
    fun getCurrentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    @JvmStatic
    fun getNanoTime(): Long {
        return System.nanoTime()
    }

    // ============ EXCEPTION THROWING (SIDE EFFECT) ============

    @JvmStatic
    fun throwException() {
        throw RuntimeException("Error")
    }

    @JvmStatic
    fun throwExceptionConditionally(flag: Boolean) {
        if (flag) {
            throw IllegalArgumentException("Invalid")
        }
    }

    // ============ OBJECT CREATION WITH SIDE EFFECTS ============

    @JvmStatic
    fun createFileOutputStream() {
        java.io.FileOutputStream("test.txt")
    }

    @JvmStatic
    fun createSocket() {
        java.net.Socket("localhost", 8080)
    }

    // ============ NESTED UNSAFE OPERATIONS ============

    @JvmStatic
    fun nestedFieldWrites(obj: TestObject) {
        if (obj.value > 0) {
            staticCounter = obj.value  // Field write
            obj.value = 0  // Another field write
        }
    }

    @JvmStatic
    fun arrayAndFieldWrite(arr: IntArray) {
        arr[0] = 10  // Array write
        staticCounter = arr[0]  // Field write
    }

    // Helper test class
    class TestObject {
        @JvmField
        var value: Int = 0
    }
}
