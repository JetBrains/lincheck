/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.V1_8
import java.lang.ref.WeakReference

/**
 * Tests for [SourceFileClassIndex].
 *
 * The classes under test are generated with ASM and defined in throwaway class loaders,
 * so each test controls the class's `SourceFile` attribute, uses source file names unique
 * to itself (the index is a global singleton), and can drop the class for the GC test.
 */
class SourceFileClassIndexTest {

    private companion object {
        const val CYCLES = 128
    }

    /**
     * [SourceFileClassIndex.indexClasses] falls back to re-transformation,
     * which needs an attached agent.
     */
    @Before
    fun attachAgent() {
        if (!LincheckInstrumentation.isInitialized) {
            LincheckInstrumentation.attachJavaAgentDynamically()
        }
    }

    private class ByteArrayClassLoader : ClassLoader() {
        fun define(name: String, bytes: ByteArray): Class<*> =
            defineClass(name, bytes, 0, bytes.size)
    }

    private fun classBytes(internalClassName: String, sourceFileName: String?): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC or ACC_SUPER, internalClassName, null, "java/lang/Object", null)
        if (sourceFileName != null) {
            writer.visitSource(sourceFileName, null)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun redefinedClassIsRetrievableByItsSourceFileName() {
        val loader = ByteArrayClassLoader()
        val bytes = classBytes("org/example/indextest/Retrievable", "Retrievable.example.kt")
        val clazz = loader.define("org.example.indextest.Retrievable", bytes)

        SourceFileClassIndex.registerClass(loader, clazz.name, bytes)

        assertTrue(SourceFileClassIndex.sourceFileContains("Retrievable.example.kt", clazz))
        assertFalse(SourceFileClassIndex.sourceFileContains("SomeOtherFile.example.kt", clazz))
        assertEquals(
            listOf(clazz.name),
            SourceFileClassIndex.classNamesFromSourceFile("Retrievable.example.kt", loader),
        )
    }

    @Test
    fun repeatedRegistrationIndexesTheClassOnce() {
        val loader = ByteArrayClassLoader()
        val bytes = classBytes("org/example/indextest/Repeated", "Repeated.example.kt")
        val clazz = loader.define("org.example.indextest.Repeated", bytes)

        repeat(3) {
            SourceFileClassIndex.registerClass(loader, clazz.name, bytes)
        }

        // A single name, not three: the entry must not be duplicated per re-transformation.
        assertEquals(
            listOf(clazz.name),
            SourceFileClassIndex.classNamesFromSourceFile("Repeated.example.kt", loader),
        )
    }

    @Test
    fun classesLiveDebuggingNeverInstrumentsAreNotIndexed() {
        // The rule both of the index's population paths filter by, so that neither records what
        // live debugging will not instrument, nor what has no class file of its own to read.
        assertFalse(LincheckInstrumentation.isIndexedClassName("java.util.ArrayList"))
        assertFalse(LincheckInstrumentation.isIndexedClassName("kotlin.collections.ArraysKt"))
        assertFalse(
            LincheckInstrumentation.isIndexedClassName("org.jetbrains.lincheck.jvm.agent.SourceFileClassIndex")
        )
        // A Java lambda is re-transformed through its enclosing class rather than itself,
        // and a hidden class ('/' in the name) serves no class-file resource.
        assertFalse(LincheckInstrumentation.isIndexedClassName("com.example.Foo\$\$Lambda\$17"))
        assertFalse(LincheckInstrumentation.isIndexedClassName("com.example.Foo/0x00007f00"))
        assertTrue(LincheckInstrumentation.isIndexedClassName("com.example.Foo"))

        // Arrays and primitives turn up in `getAllLoadedClasses` and have no class file either.
        assertFalse(LincheckInstrumentation.isIndexedClass(Array<String>::class.java))
        assertFalse(LincheckInstrumentation.isIndexedClass(Integer.TYPE))
        val loader = ByteArrayClassLoader()
        val bytes = classBytes("org/example/indextest/Indexable", "Indexable.example.kt")
        assertTrue(
            LincheckInstrumentation.isIndexedClass(loader.define("org.example.indextest.Indexable", bytes))
        )
    }

    @Test
    fun classRecordedAtLoadTimeIsMatchedOnceDefined() {
        val loader = ByteArrayClassLoader()
        val className = "org.example.indextest.Pending"
        val bytes = classBytes("org/example/indextest/Pending", "Pending.example.kt")

        // The transformer's first-load callback: the name and the loader are known
        // before the Class object exists, and that is all the index records.
        SourceFileClassIndex.registerClass(loader, className, bytes)
        assertEquals(
            listOf(className),
            SourceFileClassIndex.classNamesFromSourceFile("Pending.example.kt", loader),
        )

        // The record matches the class itself once it is defined.
        val clazz = loader.define(className, bytes)
        assertTrue(SourceFileClassIndex.sourceFileContains("Pending.example.kt", clazz))
    }

    @Test
    fun sameClassNameInTwoLoadersIsSelectedByItsOwnSourceFile() {
        // The index stores (class name, defining loader), so two definitions sharing a name
        // must still be told apart by their source files.
        val className = "org.example.indextest.TwoLoaders"
        val internalClassName = "org/example/indextest/TwoLoaders"
        val firstBytes = classBytes(internalClassName, "TwoLoadersFirst.example.kt")
        val secondBytes = classBytes(internalClassName, "TwoLoadersSecond.example.kt")
        val firstClass = ByteArrayClassLoader().define(className, firstBytes)
        val secondClass = ByteArrayClassLoader().define(className, secondBytes)

        SourceFileClassIndex.registerClass(firstClass.classLoader, className, firstBytes)
        SourceFileClassIndex.registerClass(secondClass.classLoader, className, secondBytes)

        assertTrue(SourceFileClassIndex.sourceFileContains("TwoLoadersFirst.example.kt", firstClass))
        assertFalse(SourceFileClassIndex.sourceFileContains("TwoLoadersSecond.example.kt", firstClass))
        assertTrue(SourceFileClassIndex.sourceFileContains("TwoLoadersSecond.example.kt", secondClass))
        assertFalse(SourceFileClassIndex.sourceFileContains("TwoLoadersFirst.example.kt", secondClass))
        // Each file's entry answers only for the loader that defined it.
        assertEquals(
            emptyList<String>(),
            SourceFileClassIndex.classNamesFromSourceFile("TwoLoadersFirst.example.kt", secondClass.classLoader),
        )
    }

    @Test
    fun classNamesFromSourceFileWithoutALoaderSpansThemAll() {
        // One source file, two loaders: the loader-scoped overload answers for one definition,
        // the loader-agnostic one for both.
        val firstLoader = ByteArrayClassLoader()
        val secondLoader = ByteArrayClassLoader()
        val firstBytes = classBytes("org/example/indextest/SharedFileA", "SharedFile.example.kt")
        val secondBytes = classBytes("org/example/indextest/SharedFileB", "SharedFile.example.kt")
        val firstClass = firstLoader.define("org.example.indextest.SharedFileA", firstBytes)
        val secondClass = secondLoader.define("org.example.indextest.SharedFileB", secondBytes)

        SourceFileClassIndex.registerClass(firstLoader, firstClass.name, firstBytes)
        SourceFileClassIndex.registerClass(secondLoader, secondClass.name, secondBytes)

        assertEquals(
            listOf(firstClass.name),
            SourceFileClassIndex.classNamesFromSourceFile("SharedFile.example.kt", firstLoader),
        )
        assertEquals(
            listOf(secondClass.name),
            SourceFileClassIndex.classNamesFromSourceFile("SharedFile.example.kt", secondLoader),
        )
        assertEquals(
            listOf(firstClass.name, secondClass.name),
            SourceFileClassIndex.classNamesFromSourceFile("SharedFile.example.kt").sorted(),
        )
    }

    @Test
    fun oneClassNameDefinedByTwoLoadersIsListedOnce() {
        // The loader-agnostic overload identifies classes by name, so the two definitions
        // of this one collapse to a single entry.
        val className = "org.example.indextest.SharedName"
        val bytes = classBytes("org/example/indextest/SharedName", "SharedName.example.kt")
        val firstLoader = ByteArrayClassLoader().apply { define(className, bytes) }
        val secondLoader = ByteArrayClassLoader().apply { define(className, bytes) }

        SourceFileClassIndex.registerClass(firstLoader, className, bytes)
        SourceFileClassIndex.registerClass(secondLoader, className, bytes)

        assertEquals(
            listOf(className),
            SourceFileClassIndex.classNamesFromSourceFile("SharedName.example.kt"),
        )
        // Each loader still answers separately for its own definition.
        assertEquals(
            listOf(className),
            SourceFileClassIndex.classNamesFromSourceFile("SharedName.example.kt", firstLoader),
        )
        assertEquals(
            listOf(className),
            SourceFileClassIndex.classNamesFromSourceFile("SharedName.example.kt", secondLoader),
        )
    }

    @Test
    fun clearDropsTheIndex() {
        // An uninstall detaches the transformer, so the index must not outlive it.
        val loader = ByteArrayClassLoader()
        val bytes = classBytes("org/example/indextest/Cleared", "Cleared.example.kt")
        val clazz = loader.define("org.example.indextest.Cleared", bytes)
        SourceFileClassIndex.registerClass(loader, clazz.name, bytes)
        assertTrue(SourceFileClassIndex.sourceFileContains("Cleared.example.kt", clazz))

        SourceFileClassIndex.clear()

        assertFalse(SourceFileClassIndex.sourceFileContains("Cleared.example.kt", clazz))
    }

    @Test
    fun classLoadedBeforeTheAgentAttachedIsResolvedFromItsClassResource() {
        // Loaded normally from the test classpath, never reported to the index by the transformer —
        // like a class loaded before a dynamic agent attach. The installation-time sweep must
        // resolve its source file from the class-file resource of its class loader.
        val clazz = Class.forName("org.jetbrains.kotlinx.lincheck_test.fixtures.LambdaFixture")

        SourceFileClassIndex.indexClasses(listOf(clazz))

        assertTrue(SourceFileClassIndex.sourceFileContains("LambdaFixture.kt", clazz))
    }

    @Test
    fun resourcelessClassIsIndexedThroughRetransformation() {
        // Defined from bytes only — the loader serves no `.class` resource — and defined
        // before any transformer could report it: the pre-attach in-memory-loader case.
        // The sweep must fall back to re-transformation, which hands the class's bytes
        // to the registered transformer, whose callback indexes it.
        val loader = ByteArrayClassLoader()
        val bytes = classBytes("org/example/indextest/Resourceless", "Resourceless.example.kt")
        val clazz = loader.define("org.example.indextest.Resourceless", bytes)

        val transformer = registeringTransformer()
        LincheckInstrumentation.instrumentation.addTransformer(transformer, true)
        try {
            SourceFileClassIndex.indexClasses(listOf(clazz))
            assertTrue(SourceFileClassIndex.sourceFileContains("Resourceless.example.kt", clazz))
        } finally {
            LincheckInstrumentation.instrumentation.removeTransformer(transformer)
        }
    }

    @Test
    fun delegatedResourceOfADifferentDefinitionIsNotTrusted() {
        // A loader that defines its own class but serves a same-named resource describing a
        // DIFFERENT definition (the child-first delegation hazard). The resource URL does not
        // compose from the class's code source (the class has none), so the index must ignore
        // the resource and index the class from its re-transformation bytes instead.
        val definedBytes = classBytes("org/example/indextest/ChildFirst", "ChildFirst.example.kt")
        val mismatchedResource = classBytes("org/example/indextest/ChildFirst", "WrongFile.example.kt")
        val loader = object : ClassLoader() {
            fun define(): Class<*> =
                defineClass("org.example.indextest.ChildFirst", definedBytes, 0, definedBytes.size)

            override fun getResourceAsStream(name: String): java.io.InputStream? =
                if (name == "org/example/indextest/ChildFirst.class") mismatchedResource.inputStream()
                else super.getResourceAsStream(name)

            override fun getResource(name: String): java.net.URL? =
                if (name == "org/example/indextest/ChildFirst.class") {
                    javaClass.getResource("/${javaClass.name.replace('.', '/')}.class")
                } else super.getResource(name)
        }
        val clazz = loader.define()

        val transformer = registeringTransformer()
        LincheckInstrumentation.instrumentation.addTransformer(transformer, true)
        try {
            SourceFileClassIndex.indexClasses(listOf(clazz))
            assertFalse(SourceFileClassIndex.sourceFileContains("WrongFile.example.kt", clazz))
            assertTrue(SourceFileClassIndex.sourceFileContains("ChildFirst.example.kt", clazz))
        } finally {
            LincheckInstrumentation.instrumentation.removeTransformer(transformer)
        }
    }

    @Test
    fun collectedClassIsExpungedFromTheIndex() {
        // The class and its loader are confined to the helper's frame,
        // so after it returns the weak reference is the only path to them.
        val classRef = registerCollectableTestClass("Collected")
        awaitCollection(classRef)

        SourceFileClassIndex.evictStaleEntries()

        assertFalse(SourceFileClassIndex.indexesSourceFile("Collected.example.kt"))
    }

    @Test
    fun recordingEnoughClassesEvictsWithoutAnExplicitSweep() {
        // The sweep above is only reachable in production through the recording path, so pin that
        // trigger too: without it a stale entry would sit in the index forever.
        val classRef = registerCollectableTestClass("Amortized")
        awaitCollection(classRef)

        val fillerLoader = ByteArrayClassLoader()
        repeat(SourceFileClassIndex.EVICTION_INTERVAL.toInt()) { i ->
            SourceFileClassIndex.registerClass(
                fillerLoader,
                "org.example.indextest.Filler$i",
                classBytes("org/example/indextest/Filler$i", "Filler$i.example.kt"),
            )
        }

        assertFalse(SourceFileClassIndex.indexesSourceFile("Amortized.example.kt"))
    }

    @Test
    fun indexDoesNotGrowAcrossClassLoadUnloadCycles() {
        val collectables = mutableListOf<WeakReference<*>>()
        repeat(CYCLES) { i ->
            collectables += runLoadUnloadCycle(i)
        }

        // Every cycle's loader and class must become collectable — nothing may pin them.
        val deadline = System.currentTimeMillis() + 60_000
        while (collectables.any { it.get() != null } && System.currentTimeMillis() < deadline) {
            System.gc()
            Thread.sleep(10)
        }
        assertEquals(
            "Cycle classes/loaders were not garbage collected within the timeout",
            emptyList<Any>(), collectables.mapNotNull { it.get() },
        )

        // Count the cycles' own file names rather than the index size: the environment keeps
        // loading classes that are legitimately indexed mid-test, which no total-size assertion
        // can tell apart from a leak.
        SourceFileClassIndex.evictStaleEntries()
        val leaked = (0 until CYCLES).count {
            SourceFileClassIndex.indexesSourceFile("LeakDefined$it.example.kt") ||
            SourceFileClassIndex.indexesSourceFile("LeakUndefined$it.example.kt")
        }
        assertEquals("Entries of the collected loaders survived in the index", 0, leaked)
    }

    /**
     * One load/unload cycle: a defined class recorded and queried back,
     * plus a class recorded at load time that is never defined.
     * Returns weak references to everything the index must not pin.
     */
    private fun runLoadUnloadCycle(i: Int): List<WeakReference<*>> {
        val definedLoader = ByteArrayClassLoader()
        val definedBytes = classBytes("org/example/indextest/LeakDefined$i", "LeakDefined$i.example.kt")
        val definedClass = definedLoader.define("org.example.indextest.LeakDefined$i", definedBytes)
        SourceFileClassIndex.registerClass(definedLoader, definedClass.name, definedBytes)
        assertTrue(SourceFileClassIndex.sourceFileContains("LeakDefined$i.example.kt", definedClass))

        val undefinedLoader = ByteArrayClassLoader()
        val undefinedBytes = classBytes("org/example/indextest/LeakUndefined$i", "LeakUndefined$i.example.kt")
        SourceFileClassIndex.registerClass(
            undefinedLoader, "org.example.indextest.LeakUndefined$i", undefinedBytes,
        )

        return listOf(WeakReference(definedClass), WeakReference(definedLoader), WeakReference(undefinedLoader))
    }

    /** The live-debugging hook of `LincheckClassFileTransformer.transform`, minimized. */
    private fun registeringTransformer() = object : java.lang.instrument.ClassFileTransformer {
        override fun transform(
            loader: ClassLoader?,
            internalClassName: String?,
            classBeingRedefined: Class<*>?,
            protectionDomain: java.security.ProtectionDomain?,
            classBytes: ByteArray,
        ): ByteArray? {
            if (internalClassName != null) {
                SourceFileClassIndex.registerClass(
                    loader, internalClassName.toCanonicalClassName(), classBytes,
                )
            }
            return null
        }
    }

    /**
     * Indexes a class in a loader confined to this frame, so that on return the returned weak
     * reference is the only path to either.
     */
    private fun registerCollectableTestClass(name: String): WeakReference<Class<*>> {
        val loader = ByteArrayClassLoader()
        val bytes = classBytes("org/example/indextest/$name", "$name.example.kt")
        val clazz = loader.define("org.example.indextest.$name", bytes)

        SourceFileClassIndex.registerClass(loader, clazz.name, bytes)
        assertTrue(SourceFileClassIndex.sourceFileContains("$name.example.kt", clazz))

        return WeakReference(clazz)
    }

    /** Class unloading needs a full GC cycle; retry instead of trusting a single `System.gc()`. */
    private fun awaitCollection(ref: WeakReference<*>) {
        val deadline = System.currentTimeMillis() + 30_000
        while (ref.get() != null && System.currentTimeMillis() < deadline) {
            System.gc()
            Thread.sleep(10)
        }
        assertNull("The test class was not garbage collected within the timeout", ref.get())
    }
}
