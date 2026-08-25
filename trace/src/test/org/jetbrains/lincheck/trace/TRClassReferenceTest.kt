package org.jetbrains.lincheck.trace

import org.junit.Assert
import org.junit.Test

/**
 * The capture shape of class references — how `Class` and `KClass` values reach a trace.
 */
class TRClassReferenceTest {

    @Test
    fun `TRValue routes a Kotlin KClass to TRKotlinClass`() {
        val value = TRValue(TraceContext(), TRClassReferenceTest::class)

        Assert.assertTrue(
            "TRValue(KClass) should produce a TRKotlinClass, got ${value::class.simpleName}",
            value is TRKotlinClass,
        )
    }

    @Test
    fun `TRValue routes a Java Class to TRJavaClass`() {
        val value = TRValue(TraceContext(), TRClassReferenceTest::class.java)

        Assert.assertTrue(
            "TRValue(Class) should produce a TRJavaClass, got ${value::class.simpleName}",
            value is TRJavaClass,
        )
    }

    @Test
    fun `TRKotlinClass captures the JVM binary name, not the Kotlin qualified name`() {
        // `kotlin.String::class` is a mapped type: its Kotlin name is `kotlin.String`,
        // but traces have always recorded the underlying `java.lang.String`.
        val value = TRValue(TraceContext(), String::class) as TRKotlinClass

        Assert.assertEquals("java.lang.String", value.referencedClassName)
        Assert.assertEquals("java.lang.String.kclass", value.toString())
    }

    @Test
    fun `TRJavaClass and TRKotlinClass of the same class stay distinguishable`() {
        val context = TraceContext()

        val javaClass = TRValue(context, String::class.java)
        val kotlinClass = TRValue(context, String::class)

        Assert.assertEquals("java.lang.String.class", javaClass.toString())
        Assert.assertEquals("java.lang.String.kclass", kotlinClass.toString())
        Assert.assertNotEquals(javaClass, kotlinClass)
    }

    @Test
    fun `a plain object is not mistaken for a KClass`() {
        val value = TRValue(TraceContext(), Any())

        Assert.assertTrue(
            "A plain object should not be captured as a class reference, got ${value::class.simpleName}",
            value !is TRClassReference,
        )
    }
}
