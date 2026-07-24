@file:Suppress("unused")

package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import kotlin.test.Test

/**
 * Checks that if the test class contains only one reference field the diagram will omit the test class
 * instance and use that reference as a root.
 */
class OmitTestClassInstanceInDiagramTest {
    private val counter = IncorrectCounter()

    @Operation
    fun increment() = counter.increment()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

/**
 * Checks that if the test class contains more then one reference field it won't be omitted in the diagram.
 */
class DoNotOmitTestClassInstanceInDiagramWhenManyRefFieldsPresentTest {
    private val counter = IncorrectCounter()
    private val counter2 = IncorrectCounter()

    @Operation
    fun increment() = counter.increment()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

/**
 * Checks that if the test class contains at least one primitive field it won't be omitted in the diagram.
 */
class DoNotOmitTestClassInstanceInDiagramWhenRefAndPrimitivePresentTest {
    private val counter = IncorrectCounter()
    private var primitive: Int = 1

    @Operation
    fun increment() = counter.increment()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

/**
 * Checks that if the test class contains at least one primitive field it won't be omitted in the diagram.
 */
class DoNotOmitTestClassInstanceInDiagramWhenAnyPrimitiveFieldsPresentTest {
    private var primitive: Int = 1

    @Operation
    fun increment() = primitive++

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

/**
 * Checks that if the test class contains at least one primitive field wrapper it won't be omitted in the diagram.
 */
class DoNotOmitTestClassInstanceInDiagramWhenPrimitiveWrapperPresentTest {
    private var primitiveWrapper: Int? = 1

    @Operation
    fun increment(): Int {
        primitiveWrapper = primitiveWrapper!! + 1
        return primitiveWrapper!!
    }

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

class IncorrectCounter {
    @Volatile
    var value = 0

    // just to make diagram bigger
    private val box = Box(1)

    fun increment() = value++
}

private class Box(val value: Int)