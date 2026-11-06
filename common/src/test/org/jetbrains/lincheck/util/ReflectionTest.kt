/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import org.junit.Assert.*
import org.junit.Test

class ReflectionTest {
    open class Parent {
        @JvmField val inherited = 1
    }

    class Child : Parent(), InterfaceWithField {
        private val own = 2
        @JvmField var mutable = 3

        companion object {
            @JvmField val staticFinal = 4
        }
    }

    interface InterfaceWithField {
        companion object {
            @JvmField val constant = Any()
        }
    }

    @Test
    fun resolvesClassNamesAndSuperclassSuffixes() {
        val child = Child::class.java
        assertSame(child, resolveClass(child))
        assertSame(child, resolveClass(className = child.name))
        for (name in listOf(Parent::class.java.name, Parent::class.java.canonicalName, "Parent")) {
            repeat(2) { assertSame(Parent::class.java, resolveClass(child, name)) }
        }
        assertThrows(IllegalStateException::class.java) { resolveClass() }
        assertThrows(IllegalStateException::class.java) { resolveClass(child, "InterfaceWithField") }
        assertThrows(ClassNotFoundException::class.java) { resolveClass(className = "missing.Class") }
    }

    @Test
    fun resolvesPrivateInheritedAndInterfaceFields() {
        val child = Child::class.java
        assertEquals(child, resolveField(child, child.name, "own").declaringClass)
        assertEquals(Parent::class.java, resolveField(child, child.name, "inherited").declaringClass)
        assertEquals(InterfaceWithField::class.java, resolveField(child, child.name, "constant").declaringClass)
        assertThrows(IllegalStateException::class.java) { resolveField(child, child.name, "missing") }
    }

    @Test
    fun finalInstanceFieldsExcludeInheritedMutableAndStaticFields() {
        val child = Child::class.java
        assertEquals(listOf(child.getDeclaredField("own")), child.declaredFinalInstanceFields)
        val parent = Parent::class.java
        assertEquals(listOf(parent.getDeclaredField("inherited")), parent.declaredFinalInstanceFields)
        assertTrue(InterfaceWithField::class.java.declaredFinalInstanceFields.isEmpty())
    }

    @Test
    fun declaredFieldsPrecedeHiddenPublicAndInterfaceFields() {
        val child = ReflectionFieldFixture.Child::class.java
        val parent = ReflectionFieldFixture.Parent::class.java
        assertEquals(parent, resolveField(child, child.name, "inherited").declaringClass)
        assertEquals(child, resolveField(child, child.name, "value").declaringClass)
        val owner = resolveClass(child, parent.name)
        assertEquals(parent, resolveField(owner, parent.name, "value").declaringClass)
    }

    @Test
    fun cachesFieldsByClassIdentity() {
        val type = LoaderFixture::class.java
        val bytes = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        fun loadCopy(): Class<*> = object : ClassLoader(null) {
            fun define(): Class<*> = defineClass(type.name, bytes, 0, bytes.size)
        }.define()
        val first = loadCopy()
        val second = loadCopy()
        val field = resolveField(first, first.name, "value")
        assertSame(field, resolveField(first, first.name, "value"))
        assertSame(first, field.declaringClass)
        assertSame(second, resolveField(second, second.name, "value").declaringClass)
        assertNotSame(field, resolveField(second, second.name, "value"))
    }
}

class LoaderFixture {
    @JvmField val value = 1
}
