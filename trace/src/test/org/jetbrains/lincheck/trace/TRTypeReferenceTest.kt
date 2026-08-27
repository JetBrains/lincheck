package org.jetbrains.lincheck.trace

import org.junit.Assert
import org.junit.Test

/**
 * The capture shape of class references — how `Class` and `KClass` values reach a trace.
 */
class TRTypeReferenceTest {

    @Test
    fun `TRValue routes a Kotlin KClass to a KOTLIN_CLASS type reference`() {
        val value = TRValue(TraceContext(), TRTypeReferenceTest::class)

        Assert.assertTrue(
            "TRValue(KClass) should produce a KOTLIN_CLASS TRTypeReference, got $value",
            value is TRTypeReference && value.flavor == TypeFlavor.KOTLIN_CLASS,
        )
    }

    @Test
    fun `TRValue routes a Java Class to a JAVA_CLASS type reference`() {
        val value = TRValue(TraceContext(), TRTypeReferenceTest::class.java)

        Assert.assertTrue(
            "TRValue(Class) should produce a JAVA_CLASS TRTypeReference, got $value",
            value is TRTypeReference && value.flavor == TypeFlavor.JAVA_CLASS,
        )
    }

    @Test
    fun `a KOTLIN_CLASS reference captures the JVM binary name, not the Kotlin qualified name`() {
        // `kotlin.String::class` is a mapped type: its Kotlin name is `kotlin.String`,
        // but traces have always recorded the underlying `java.lang.String`.
        val value = TRValue(TraceContext(), String::class) as TRTypeReference

        Assert.assertEquals("java.lang.String", value.referencedClassName)
        Assert.assertEquals("java.lang.String.kclass", value.toString())
    }

    @Test
    fun `JAVA_CLASS and KOTLIN_CLASS references to the same class stay distinguishable`() {
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
            value !is TRTypeReference,
        )
    }
}
