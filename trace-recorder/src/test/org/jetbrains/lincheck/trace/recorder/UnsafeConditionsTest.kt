/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*
import java.io.*
import kotlin.test.*

/**
 * Tests for UNSAFE condition functions - all should fail compile-time safety verification.
 * Test cases are automatically generated from methods in [UnsafeConditions] using ASM.
 */
class UnsafeConditionsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeMethods")
    fun test(methodInfo: ConditionTestUtils.MethodInfo) {
        val className = UnsafeConditions::class.java.name

        val violations = ConditionSafetyChecker.checkForSideEffectAbsence(
            className,
            methodInfo.name,
            methodInfo.descriptor,
            this::class.java.classLoader
        )
        assertFalse(violations.isEmpty(), "Method ${methodInfo.name} should be unsafe")
    }

    companion object {
        @JvmStatic
        fun unsafeMethods(): List<ConditionTestUtils.MethodInfo> =
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

    // ============ LOCAL VARIABLE WRITES ============

    @JvmStatic
    fun writeLocalInt(): Int {
        val x = 5  // Local variable write
        return x
    }

    @JvmStatic
    fun writeLocalString(): String {
        val s = "test"  // Local variable write
        return s
    }

    @JvmStatic
    fun multipleLocalWrites(): Int {
        val a = 1
        val b = 2
        val c = a + b
        return c
    }

    @JvmStatic
    fun reassignLocalVariable(): Int {
        var x = 10
        x = 20  // Reassignment
        return x
    }

    @JvmStatic
    fun writeLocalWithMath(): Int {
        val x = Math.abs(-5)  // Local variable write
        return x
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
    fun complexUnsafeWithMultipleWrites(): Int {
        val a = 10  // Write
        val b = 20  // Write
        val c = a + b  // Write
        return c
    }

    @JvmStatic
    fun conditionalWrite(flag: Boolean): Int {
        val result = if (flag) {
            val temp = 10  // Write
            temp
        } else {
            val temp = 20  // Write
            temp
        }
        return result
    }

    @JvmStatic
    fun loopWithLocalVariable(): Int {
        var sum = 0  // Write
        return sum
    }

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
    fun localVariableInLoop(): Int {
        var total = 0
        for (i in 1..10) {
            total += i  // Local variable write
        }
        return total
    }

    @JvmStatic
    fun localVariableWithWhen(x: Int): String {
        val result = when (x) {  // Local variable write
            1 -> "one"
            2 -> "two"
            else -> "other"
        }
        return result
    }

    @JvmStatic
    fun destructuringAssignment(pair: Pair<Int, Int>): Int {
        val (a, b) = pair  // Two local variable writes
        return a + b
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

    @JvmStatic
    fun tryWithResources(): String {
        File("test.txt").bufferedReader().use {
            return it.readLine()
        }
    }

    @JvmStatic
    fun exceptionWithLocalVariable(): Int {
        return try {
            val x = 10  // Local write
            x
        } catch (e: Exception) {
            0
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
}
