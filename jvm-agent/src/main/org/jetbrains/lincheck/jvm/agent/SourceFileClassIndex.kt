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

import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent-side index from a source file name (the class-file `SourceFile` attribute, e.g. `Foo.kt`)
 * to the class definitions compiled from that file.
 *
 * The index stores *class definition identities* — a class name plus its defining class loader —
 * rather than [Class] objects. This allows us to maintain index during class transformation time,
 * since at that point the [Class] object might not be available yet.
 * The class loader is part of the stored identity, not just a liveness marker:
 * the same class name can denote different definitions in different loaders,
 * with different source files.
 *
 * The index is re-built on instrumentation installation and then updated incrementally on each new loaded class.
 *  - [indexClasses] indexes the classes already loaded before instrumentation was installed
 *    (e.g., via dynamic javaagent attachment).
 *    Their source file is resolved from the class-file resource of their defining class loader —
 *    accepted only when the resource URL provably composes from the class's own code source;
 *    classes without such a proven resource (in-memory, generating, or child-first delegating loaders)
 *    are re-transformed instead, which makes the JVM hand their bytes to the transformer.
 *  - [registerClass] updates the index on every class loaded afterward
 *    (called from the bytecode transformer). It reads the source file name from the class bytes via ASM.
 *
 * The invariant holds because the index keeps being maintained for as long as
 * the bytecode transformer stays registered, which itself is registered for as long as
 * the instrumentation is installed. The index does not survive an instrumentation uninstallation —
 * [clear] drops the index, and the next installation rebuilds it.
 *
 * Index is garbage-collection-friendly: entries are dropped once their defining class loader is garbage collected —
 * the class can never be loaded again, so nothing can match the entry.
 * The eviction runs on the registration paths, amortized - once every [EVICTION_INTERVAL] additions.
 * The index may only grow there, so that bounds it at its live size plus one interval,
 * for as long as classes keep loading.
 * This is no coarser than tracking each class individually:
 * an indexed class can only be unloaded together with its loader, and the class kinds that can be unloaded
 * on their own (lambdas and hidden classes) are excluded by [LincheckInstrumentation.isIndexedClassName].
 * Entries of the bootstrap loader are never dropped, which is correct — its classes never unload.
 *
 * What classes should be indexed is decided by the caller.
 * Usages of the index in [LincheckInstrumentation] filter through [LincheckInstrumentation.isIndexedClassName]:
 * standard-library and Lincheck-internal classes are left out, since live debugging never instruments them
 * (see [LiveDebuggerTransformationProfile.shouldTransform]) and indexing them would only add
 * memory and sweep cost.
 */
object SourceFileClassIndex {

    /**
     * A class definition: its name plus the identity of the class loader that defined it.
     *
     * @property classLoaderRef the defining class loader; `null` iff it is the bootstrap class loader.
     */
    private class ClassEntry(val className: String, loader: ClassLoader?) {
        private val classLoaderRef: WeakReference<ClassLoader>? = loader?.let { WeakReference(it) }

        /**
         * True once the defining loader has been collected: no class can match this entry again.
         */
        val isStale: Boolean get() = classLoaderRef != null && classLoaderRef.get() == null

        /**
         * True when [classLoader] is the loader that defined this entry's class — `null` meaning
         * the bootstrap loader. A stale entry matches nothing, not even a bootstrap class:
         * its cleared reference reads as `null` too.
         */
        fun isLoadedBy(classLoader: ClassLoader?): Boolean =
            !isStale && classLoader === classLoaderRef?.get()

        /**
         * True when [className] and [classLoader] match this entry.
         */
        fun describes(className: String, classLoader: ClassLoader?): Boolean =
            className == this.className && isLoadedBy(classLoader)
    }

    /**
     * A map from the source file name to the class definitions compiled from it.
     *
     * The transformer records from whichever thread is loading a class using two locks:
     *  - **a list's contents are guarded by that list's own monitor**,
     *    taken by anyone reading or writing it, so a query iterating one never observes a half-finished append;
     *  - **this map's own monitor spans a whole addition or removal**, so the two cannot interleave.
     *    Eviction drops a file whose list has emptied, and without it that would race registration:
     *    a writer has to take the list out of the map before it can lock the list,
     *    so the entry could be unmapped in between and the append would land on a list nothing points to.
     *    Mutators take it first and the list's monitor second, never the other way.
     *
     * A query only takes per-list locks, never map-wide lock:
     * it finds the file in the concurrent map and locks only that one list.
     * It may still find a file that eviction is about to drop,
     * but the entries there are the stale ones every query filters out anyway.
     *
     * No list is leaked outside: callers get a copy of what they read.
     */
    private val entriesByFileName = ConcurrentHashMap<String, MutableList<ClassEntry>>()

    /**
     * Counter of added entries; drives the amortized eviction sweep.
     */
    private val evictionCounter = AtomicLong()

    /**
     * How many added entries one eviction sweep is amortized over.
     *
     * A sweep costs one pass over the index, so this trades that against how long a collected
     * loader's entries linger. Both sides are small next to the `SourceFile` parse each addition
     * already pays, so the constant is not delicate.
     */
    internal const val EVICTION_INTERVAL = 1024L

    /**
     * Registers the class definition under its source file.
     * The source file name is read from the class bytes.
     *
     * Intended to be called from the bytecode transformer for every indexable class the transformer sees,
     * including classes that are not going to be instrumented.
     *
     * @param className canonical class name (dot-separated).
     */
    fun registerClass(loader: ClassLoader?, className: String, classBytes: ByteArray) {
        val fileName = readSourceFileName(classBytes) ?: return
        registerClass(loader, className, fileName)
    }

    /**
     * Registers the class definition under its source file.
     *
     * Idempotent, which the transformer relies on: it reports a class again on every re-transformation,
     * and the index must not grow a duplicate entry each time.
     */
    fun registerClass(loader: ClassLoader?, className: String, fileName: String) {
        registerClass(loader, className, fileName, withEviction = true)
    }

    private fun registerClass(loader: ClassLoader?, className: String, fileName: String, withEviction: Boolean) {
        // The check and the append are one critical section,
        // so concurrent records of a single definition cannot both get through,
        // and eviction cannot unmap the file in between.
        val added = synchronized(entriesByFileName) {
            val entries = entriesByFileName.computeIfAbsent(fileName) { mutableListOf() }
            synchronized(entries) {
                if (entries.any { it.describes(className, loader) }) return@synchronized false
                entries.add(ClassEntry(className, loader))
            }
        }
        // Only a real addition counts, and the sweep runs outside the locks it would retake.
        if (withEviction && added && evictionCounter.incrementAndGet() % EVICTION_INTERVAL == 0L) {
            evictStaleEntries()
        }
    }

    /**
     * Returns the names of the indexed classes compiled from [fileName] by any class loader.
     */
    fun classNamesFromSourceFile(fileName: String): List<String> =
        classNamesMatching(fileName) { !it.isStale }.distinct()

    /**
     * Returns the names of the indexed classes compiled from [fileName] and defined by [classLoader],
     * `null` meaning the bootstrap loader.
     */
    fun classNamesFromSourceFile(fileName: String, classLoader: ClassLoader?): List<String> =
        classNamesMatching(fileName) { it.isLoadedBy(classLoader) }

    /**
     * Returns the names of indexed classes compiled from [fileName] and matching given [predicate].
     */
    private fun classNamesMatching(fileName: String, predicate: (ClassEntry) -> Boolean): List<String> {
        val entries = entriesByFileName[fileName] ?: return emptyList()
        return synchronized(entries) {
            entries.filter(predicate).map { it.className }
        }
    }

    /**
     * Returns whether [clazz] was compiled from [fileName].
     *
     * Answers from the index, so `false` means "not recorded as such" rather than a proven negative:
     * a class the index never saw — one the transformer missed and [indexClasses] could not resolve —
     * answers `false` too.
     */
    fun sourceFileContains(fileName: String, clazz: Class<*>): Boolean =
        clazz.name in classNamesFromSourceFile(fileName, clazz.classLoader)

    /**
     * Whether the index still holds an entry for [fileName].
     * Tells an unmapped file apart from one whose list merely emptied, which no query can.
     */
    internal fun indexesSourceFile(fileName: String): Boolean =
        // Explicitly `containsKey`: on a `ConcurrentHashMap`, `in` resolves to `containsValue`.
        entriesByFileName.containsKey(fileName)

    /**
     * Indexes provided [classes].
     *
     * Intended to be called at instrumentation installation time (i.e., from [LincheckInstrumentation.install])
     * with the classes already loaded by then, after it registers the transformer:
     * the re-transformation fallback below relies on it being there to receive the bytes.
     * [clear] is this method counterpart on [LincheckInstrumentation.uninstall] path.
     *
     * Registration is idempotent, so passing the same class twice is harmless.
     * Resolution may call into user-defined class loaders, so it is left to the calling thread.
     *
     * @param classes the classes to index; the unresolved ones among them may be re-transformed.
     */
    internal fun indexClasses(classes: Collection<Class<*>>) {
        if (classes.isEmpty()) return
        val resolved = mutableListOf<Pair<Class<*>, String>>()
        val unresolved = mutableListOf<Class<*>>()

        fun addUnresolvedClass(clazz: Class<*>) {
            // A class the JVM will not re-transform has no other byte source once its class-file
            // resource is out, so there is nothing left to try for it.
            if (LincheckInstrumentation.canRetransformClass(clazz)) {
                unresolved.add(clazz)
            }
        }

        for (clazz in classes) {
            try {
                val resource = resolveDefiningResource(clazz)
                if (resource == null) {
                    addUnresolvedClass(clazz)
                    continue
                }
                // Bytes proven to come from the class's own code source are conclusive;
                // a class carrying no SourceFile simply has nothing to record.
                val sourceFileName = resource.openStream().use { readSourceFileName(it.readBytes()) }
                if (sourceFileName != null) {
                    resolved.add(clazz to sourceFileName)
                }
            } catch (t: Throwable) {
                Logger.debug { "Failed to resolve the source file of ${clazz.name}: $t" }
                addUnresolvedClass(clazz)
            }
        }

        resolved.forEach { (clazz, fileName) ->
            registerClass(clazz.classLoader, clazz.name, fileName, withEviction = false)
        }

        // Last-resort byte source for the classes left: re-transformation makes the JVM hand
        // their current bytes to the transformer, whose `registerClass` records them.
        // One that fails, or never reaches the transformer, stays out of the index — it might be picked up again
        // only if something re-transforms it later.
        // TODO: consider moving this to `LincheckInstrumentation` itself.
        LincheckInstrumentation.retransformClasses(unresolved)
    }

    /**
     * Removes the entries whose defining class loader was collected.
     *
     * Scans all entries, which is why it is kept off the per-class query path
     * and amortized over [EVICTION_INTERVAL] additions instead.
     *
     * Exposed so a caller that knows loaders have just been collected
     * can call it manually and sweep without waiting for amortized eviction to kick-in.
     */
    internal fun evictStaleEntries() = synchronized(entriesByFileName) {
        val emptiedSourceFiles = mutableListOf<String>()
        entriesByFileName.forEach { (fileName, entries) ->
            synchronized(entries) {
                entries.removeIf { it.isStale }
                if (entries.isEmpty()) emptiedSourceFiles.add(fileName)
            }
        }
        // Safe to unmap: putting anything back into those lists needs the lock this still holds.
        emptiedSourceFiles.forEach { entriesByFileName.remove(it) }
    }

    /**
     * Clears the index.
     */
    internal fun clear() {
        synchronized(entriesByFileName) {
            entriesByFileName.clear()
        }
    }

    /**
     * Returns the URL of [clazz]'s class-file resource, but only when it provably serves the
     * class's own definition. Resource lookup delegates through parent loaders, so a same-named
     * resource may describe a *different* definition (child-first loaders); the proof is that the
     * resource URL composes from the class's own code-source location. Returns `null` when
     * unproven — the caller then falls back to re-transformation, whose JVM-supplied bytes carry
     * the definition identity.
     */
    private fun resolveDefiningResource(clazz: Class<*>): URL? {
        val resourceName = clazz.name.replace('.', '/') + ".class"
        val loader = clazz.classLoader
        val resource = (if (loader != null) loader.getResource(resourceName)
                        else ClassLoader.getSystemResource(resourceName))
            ?: return null
        val codeSource = clazz.protectionDomain?.codeSource?.location ?: return null
        val resourceForm = resource.toExternalForm()
        val sourceForm = codeSource.toExternalForm()
        val matches =
            // a jar-backed code source: "jar:<source>!/<resourceName>"
            resourceForm == "jar:$sourceForm!/$resourceName" ||
            // a directory-backed code source: "<source>/<resourceName>"
            resourceForm == sourceForm.removeSuffix("/") + "/" + resourceName ||
            // container loaders (e.g. Spring Boot nested jars) pre-compose the source with "!/"
            (sourceForm.endsWith("!/") && resourceForm == sourceForm + resourceName)
        return if (matches) resource else null
    }

    /**
     * Reads the `SourceFile` attribute from class-file bytes; `null` when absent or unreadable.
     */
    private fun readSourceFileName(classBytes: ByteArray): String? =
        try {
            var sourceFileName: String? = null
            ClassReader(classBytes).accept(
                object : ClassVisitor(ASM_API) {
                    override fun visitSource(source: String?, debug: String?) {
                        sourceFileName = source
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
            )
            sourceFileName?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Logger.debug { "Failed to read the SourceFile attribute: $t" }
            null
        }
}
