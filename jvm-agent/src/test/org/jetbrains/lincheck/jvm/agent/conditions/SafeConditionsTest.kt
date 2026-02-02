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

/**
 * Tests for SAFE condition functions - all should pass compile-time safety verification.
 * Test cases are automatically generated from methods in [SafeConditions] using ASM.
 */
@RunWith(Parameterized::class)
class SafeConditionsTest(
    private val methodInfo: MethodInfo
) {
    @Test
    fun test() {
        val className = SafeConditions::class.java.name

        val violations = ConditionSafetyChecker.checkMethodForSideEffects(
            className,
            methodInfo.name,
            methodInfo.descriptor,
            this::class.java.classLoader
        )
        assertTrue(
            "Method ${methodInfo.name} should be safe but has violations: $violations",
            violations.isEmpty()
        )
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun safeMethods(): Array<MethodInfo> =
            ConditionTestUtils.discoverTestMethods(SafeConditions::class.java)
    }
}

/**
 * Contains SAFE condition functions - all should pass compile-time safety verification.
 * These functions contain only read operations, pure computations, and whitelisted method calls.
 */
@Suppress("UNUSED_PARAMETER", "unused", "MemberVisibilityCanBePrivate")
private object SafeConditions {

    @JvmStatic
    var staticField: Int = 100

    // ============ PURE ARITHMETIC ============

    @JvmStatic
    fun pureAdd(a: Int, b: Int): Int = a + b

    @JvmStatic
    fun pureSubtract(a: Int, b: Int): Int = a - b

    @JvmStatic
    fun pureMultiply(a: Int, b: Int): Int = a * b

    @JvmStatic
    fun pureDivide(a: Int, b: Int): Int = if (b != 0) a / b else 0

    @JvmStatic
    fun pureNegate(a: Int): Int = -a

    @JvmStatic
    fun pureBitwiseAnd(a: Int, b: Int): Int = a and b

    @JvmStatic
    fun pureBitwiseOr(a: Int, b: Int): Int = a or b

    @JvmStatic
    fun pureBitwiseXor(a: Int, b: Int): Int = a xor b

    // ============ COMPARISONS ============

    @JvmStatic
    fun compareEquals(a: Int, b: Int): Boolean = a == b

    @JvmStatic
    fun compareGreater(a: Int, b: Int): Boolean = a > b

    @JvmStatic
    fun compareLess(a: Int, b: Int): Boolean = a < b

    // ============ LOGICAL OPERATIONS ============

    @JvmStatic
    fun logicalAnd(a: Boolean, b: Boolean): Boolean = a && b

    @JvmStatic
    fun logicalOr(a: Boolean, b: Boolean): Boolean = a || b

    @JvmStatic
    fun logicalNot(a: Boolean): Boolean = !a

    // ============ CONDITIONAL EXPRESSIONS ============

    @JvmStatic
    fun maxOfTwo(a: Int, b: Int): Int = if (a > b) a else b

    @JvmStatic
    fun minOfTwo(a: Int, b: Int): Int = if (a < b) a else b

    @JvmStatic
    fun absoluteValue(x: Int): Int = if (x < 0) -x else x

    @JvmStatic
    fun signOfNumber(x: Int): Int = if (x > 0) 1 else if (x < 0) -1 else 0

    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int =
        if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun isEven(x: Int): Boolean = x % 2 == 0

    @JvmStatic
    fun inRange(x: Int, min: Int, max: Int): Boolean = x >= min && x <= max

    // ============ WHITELISTED MATH OPERATIONS ============

    @JvmStatic
    fun mathAbs(x: Int): Int = Math.abs(x)

    @JvmStatic
    fun mathMax(a: Int, b: Int): Int = Math.max(a, b)

    @JvmStatic
    fun mathMin(a: Int, b: Int): Int = Math.min(a, b)

    @JvmStatic
    fun mathSqrt(x: Double): Double = Math.sqrt(x)

    // ============ COMPLEX ARITHMETIC ============

    @JvmStatic
    fun quadraticDiscriminant(a: Int, b: Int, c: Int): Int = b * b - 4 * a * c

    @JvmStatic
    fun sumOfSquares(a: Int, b: Int): Int = a * a + b * b

    @JvmStatic
    fun distanceSquared(x1: Int, y1: Int, x2: Int, y2: Int): Int =
        (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)

    @JvmStatic
    fun isPowerOfTwo(x: Int): Boolean = x > 0 && (x and (x - 1)) == 0

    @JvmStatic
    fun countSetBits(x: Int): Int = Integer.bitCount(x)

    // ============ READING STATIC FIELDS ============

    @JvmStatic
    fun readStaticField(): Int = staticField

    @JvmStatic
    fun staticFieldPlusConstant(): Int = staticField + 100

    @JvmStatic
    fun compareStaticField(x: Int): Boolean = x > staticField

    // ============ COMPLEX BOOLEAN EXPRESSIONS ============

    @JvmStatic
    fun booleanExpression(a: Boolean, b: Boolean, c: Boolean): Boolean =
        (a || b) && (!c || a) && (b xor c)

    @JvmStatic
    fun chainedComparisons(x: Int): Boolean =
        x > 0 && x < 100 && x != 50 && x % 2 == 0

    // ============ MULTIPLE RETURN PATHS ============

    @JvmStatic
    fun multipleReturns(x: Int): Int =
        if (x > 0) x else if (x < 0) -x else 0

    @JvmStatic
    fun nestedConditionals(x: Int): String =
        if (x > 100) {
            if (x > 1000) "huge" else "large"
        } else {
            if (x > 10) "medium" else "small"
        }

    // ============ EDGE CASES ============

    @JvmStatic
    fun returnConstant(): Int = 42

    @JvmStatic
    fun returnParameter(x: Int): Int = x

    @JvmStatic
    fun returnNull(): String? = null

    @JvmStatic
    fun emptyMethod() {
        // Completely empty
    }

    // ============ NON-TRIVIAL CASES ============

    @JvmStatic
    fun complexConditionWithMultipleOperations(x: Int, y: Int, z: Int): Boolean {
        // Multiple operations without local variables
        return (x + y > z) && (x * y < z * z) && (Math.abs(x - y) != z)
    }

    @JvmStatic
    fun deeplyNestedConditionals(a: Int, b: Int, c: Int): Int =
        if (a > b) {
            if (b > c) {
                if (a > c) a else c
            } else {
                if (a > c) a else b
            }
        } else {
            if (a > c) {
                if (b > c) b else a
            } else {
                c
            }
        }

    @JvmStatic
    fun bitwiseOperationsChain(x: Int): Int =
        ((x shl 2) or 0xFF) and (x xor 0xAA)

    // ============ ADDITIONAL COMPLEX SAFE CASES ============

    @JvmStatic
    fun combinedMathOperations(x: Double, y: Double): Double =
        Math.sqrt(x * x + y * y)

    @JvmStatic
    fun complexFloatingPoint(a: Double, b: Double, c: Double): Boolean =
        Math.abs(a - b) < c && Math.floor(a) == Math.ceil(b)

    @JvmStatic
    fun longOperations(x: Long, y: Long): Long =
        Math.max(x, y) + Math.abs(x - y)

    @JvmStatic
    fun characterOperations(c: Char): Boolean =
        c.code > 65 && c.code < 90

    @JvmStatic
    fun nullCheckOnly(s: String?): Boolean =
        s != null && s.length > 3

    @JvmStatic
    fun doubleComparisons(x: Double, y: Double): Boolean =
        x > y && x < 100.0 && y >= 0.0

    @JvmStatic
    fun whenExpressionSafe(x: Int): String =
        when {
            x < 0 -> "negative"
            x == 0 -> "zero"
            x > 0 && x < 10 -> "small positive"
            else -> "large positive"
        }

    @JvmStatic
    fun complexBooleanLogic(a: Boolean, b: Boolean, c: Boolean, d: Boolean): Boolean =
        (a || b) && (c || d) && !(a && b && c && d)

    @JvmStatic
    fun cascadingComparisons(x: Int, y: Int, z: Int): Boolean =
        x < y && y < z && z < 100

    @JvmStatic
    fun multipleReturnPaths(x: Int): Int {
        if (x < 0) return -1
        if (x == 0) return 0
        if (x < 10) return 1
        if (x < 100) return 2
        return 3
    }

    @JvmStatic
    fun shortCircuitEvaluation(x: Int, y: Int): Boolean =
        (x > 0 && y / x > 2) || (x == 0)

    @JvmStatic
    fun rangeChecks(value: Int): String =
        if (value in 0..10) "low" else if (value in 11..100) "medium" else "high"

    @JvmStatic
    fun multipleNestedIfs(a: Int, b: Int, c: Int, d: Int): Int {
        if (a > b) {
            if (c > d) {
                if (a > c) {
                    return a
                }
            }
            return c
        }
        return b
    }

    @JvmStatic
    fun expressionWithMultipleMathCalls(x: Double): Double =
        Math.abs(Math.sqrt(Math.max(x, 0.0)))

    @JvmStatic
    fun bitwiseShiftOperations(x: Int): Int =
        (x shl 1) + (x shr 2) - (x ushr 1)

    // ============ STRING CONCATENATION (INVOKEDYNAMIC) ============

    @JvmStatic
    fun stringConcatenation(a: Int, b: Int): String =
        "Result: $a + $b = ${a + b}"

    @JvmStatic
    fun multipleStringConcat(x: Int, y: Int, z: Int): String =
        "x=$x, y=$y, z=$z, sum=${x + y + z}"

    @JvmStatic
    fun stringConcatWithCondition(value: Int): String =
        "Value is " + (if (value > 0) "positive" else "non-positive") + ": $value"

    // Note: Java records would also be safe (if used), as they use invokedynamic
    // for toString/equals/hashCode via java/lang/runtime/ObjectMethods.bootstrap

    // ============ LOCAL VARIABLE WRITES (SAFE - NO SIDE EFFECTS) ============

    @JvmStatic
    fun writeLocalInt(): Int {
        val x = 5  // Local variable write - safe
        return x
    }

    @JvmStatic
    fun writeLocalString(): String {
        val s = "test"  // Local variable write - safe
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
        x = 20  // Reassignment - safe
        return x
    }

    @JvmStatic
    fun writeLocalWithMath(): Int {
        val x = Math.abs(-5)  // Local variable write - safe
        return x
    }

    @JvmStatic
    fun complexWithMultipleLocalWrites(): Int {
        val a = 10
        val b = 20
        val c = a + b
        return c
    }

    @JvmStatic
    fun conditionalLocalWrite(flag: Boolean): Int {
        val result = if (flag) {
            val temp = 10
            temp
        } else {
            val temp = 20
            temp
        }
        return result
    }

    @JvmStatic
    fun loopWithLocalVariable(): Int {
        var sum = 0
        for (i in 1..10) {
            sum += i  // Local variable write - safe
        }
        return sum
    }

    @JvmStatic
    fun localVariableInLoop(): Int {
        var total = 0
        for (i in 1..10) {
            total += i
        }
        return total
    }

    @JvmStatic
    fun localVariableWithWhen(x: Int): String {
        val result = when (x) {
            1 -> "one"
            2 -> "two"
            else -> "other"
        }
        return result
    }

    @JvmStatic
    fun exceptionWithLocalVariable(): Int {
        return try {
            val x = 10
            x
        } catch (e: Exception) {
            0
        }
    }
}
