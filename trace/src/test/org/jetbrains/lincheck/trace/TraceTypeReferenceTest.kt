package org.jetbrains.lincheck.trace

import org.junit.Assert
import org.junit.Test

/**
 * The capture shape of class references — how `Class` and `KClass` values reach a trace.
 */
class TraceTypeReferenceTest {

    @Test
    fun `TraceValue routes a Kotlin KClass to a KOTLIN_CLASS type reference`() {
        val value = TraceValue(TraceContext(), TraceTypeReferenceTest::class)

        Assert.assertTrue(
            "TraceValue(KClass) should produce a KOTLIN_CLASS TraceTypeReference, got $value",
            value is TraceTypeReference && value.flavor == TypeFlavor.KOTLIN_CLASS,
        )
    }

    @Test
    fun `TraceValue routes a Java Class to a JAVA_CLASS type reference`() {
        val value = TraceValue(TraceContext(), TraceTypeReferenceTest::class.java)

        Assert.assertTrue(
            "TraceValue(Class) should produce a JAVA_CLASS TraceTypeReference, got $value",
            value is TraceTypeReference && value.flavor == TypeFlavor.JAVA_CLASS,
        )
    }

    @Test
    fun `a KOTLIN_CLASS reference captures the JVM binary name, not the Kotlin qualified name`() {
        // `kotlin.String::class` is a mapped type: its Kotlin name is `kotlin.String`,
        // but traces have always recorded the underlying `java.lang.String`.
        val value = TraceValue(TraceContext(), String::class) as TraceTypeReference

        Assert.assertEquals("java.lang.String", value.referencedClassName)
        Assert.assertEquals("java.lang.String.kclass", value.toString())
    }

    @Test
    fun `JAVA_CLASS and KOTLIN_CLASS references to the same class stay distinguishable`() {
        val context = TraceContext()

        val javaClass = TraceValue(context, String::class.java)
        val kotlinClass = TraceValue(context, String::class)

        Assert.assertEquals("java.lang.String.class", javaClass.toString())
        Assert.assertEquals("java.lang.String.kclass", kotlinClass.toString())
        Assert.assertNotEquals(javaClass, kotlinClass)
    }

    @Test
    fun `a plain object is not mistaken for a KClass`() {
        val value = TraceValue(TraceContext(), Any())

        Assert.assertTrue(
            "A plain object should not be captured as a class reference, got ${value::class.simpleName}",
            value !is TraceTypeReference,
        )
    }
}
