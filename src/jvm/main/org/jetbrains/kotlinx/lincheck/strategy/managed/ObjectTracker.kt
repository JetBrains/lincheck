/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import sun.nio.ch.lincheck.WeakIdentityReference
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.isJavaLambdaClass
import kotlin.coroutines.Continuation
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Tracks objects and changes in object graph topology.
 *
 * The object tracker allows registering objects within the managed strategy,
 * assigning to each object a unique serial number and unique id.
 *
 * The unique serial number is a 32-bit integer number.
 * Assuming the deterministic execution, the serial numbers are guaranteed to be generated in the same order,
 * providing a persistent objects numeration between different re-runs of the same program execution.
 *
 * The unique object id is a 64-bit integer number, constructed from
 * the object's serial number and its identity hash code.
 *
 * The registered objects are associated with the registry entries [ObjectEntry],
 * keeping object's serial number, its identity hash code, a weak reference to the object,
 * and, potentially, other meta-data (defined by the concrete implementations of the interface).
 *
 * The tracker allows retrieving the registry entry either by the object reference itself or by unique object id.
 * In this way, the tracker allows to:
 *   - get an object by its object id: `ObjectId -> Object`;
 *   - get an object id by the object itself: `Object -> ObjectId`.
 */
interface ObjectTracker {

    /**
     * Registers a thread with the given id in the object tracker.
     *
     * @param threadId the id of the thread to register.
     * @param thread the thread object to register.
     */
    fun registerThread(threadId: Int, thread: Thread)

    /**
     * Retrieves the registry entry associated with the given object id.
     *
     * @param id the object id to retrieve the corresponding entry for.
     * @return the corresponding [ObjectEntry],
     *   or null if no entry is associated with the given object id.
     */
    operator fun get(id: ObjectID): ObjectEntry?

    /**
     * Retrieves the registry entry associated with the given object.
     *
     * @param obj the object to retrieve the corresponding entry for.
     *   Null value denotes a phantom static object, which is considered to be an owner of all static fields.
     * @return the corresponding [ObjectEntry],
     *   or null if no entry is associated with the given object.
     */
    operator fun get(obj: Any?): ObjectEntry?

    /**
     * Registers a newly created object in the object tracker.
     *
     * @param obj the object to be registered.
     * @return the registry entry for the new object.
     */
    fun registerNewObject(obj: Any): ObjectEntry

    /**
     * Registers an externally created object in the object tracker.
     * An external object is an object created outside the analyzed code,
     * but that nonetheless leaked into the analyzed code and thus needs to be tracked as well.
     *
     * @param obj the external object to be registered.
     * @return the registered object entry for the external object.
     */
    fun registerExternalObject(obj: Any): ObjectEntry

    /**
     * This method is used to register a link between two objects in the object tracker.
     * The link is established from the object specified by the [fromObject] parameter
     * to the object specified by the [toObject] parameter.
     *
     * @param fromObject the object from which the link originates.
     *   Null value indicates a link from a phantom static object through some static field.
     * @param toObject the object to which the link points.
     */
    fun registerObjectLink(fromObject: Any?, toObject: Any?)

    /**
     * Determines whether accesses to the fields of the given object should be tracked.
     *
     * @param obj the object to check for tracking. Null value indicates access to a phantom static object.
     * @return true if the object's accesses should be tracked, false otherwise.
     */
    fun shouldTrackObjectAccess(obj: Any?): Boolean

    /**
     * Enumerates all tracked objects registered within the object tracker.
     *
     * @return A sequence of [ObjectEntry] instances representing the tracked objects.
     */
    fun enumerateObjectEntries(): Sequence<ObjectEntry>

    /**
     * Retains only the entries in the object tracker that match the given predicate.
     *
     * @param predicate a condition that determines which entries should be retained.
     */
    fun retain(predicate: (ObjectEntry) -> Boolean)

    /**
     * Resets the state of the object tracker, removing all registered object entries.
     */
    fun reset()
}

/**
 * Registers an object in the tracker if it is not already present.
 * If the object is already registered, returns its corresponding entry.
 *
 * @param obj the object to be registered if absent.
 * @return the registered entry associated with the given object.
 */
fun ObjectTracker.registerObjectIfAbsent(obj: Any): ObjectEntry =
    get(obj) ?: registerExternalObject(obj)

/**
 * A typealias for representing an object identifier.
 *
 * The unique object id is a 64-bit integer number, constructed from
 * the object's serial number and its identity hash code.
 *
 * @see ObjectTracker
 */
typealias ObjectID = Long

/**
 * Represents an entry for the tracked object.
 *
 * @property objectNumber A unique serial number for the object.
 * @property objectHashCode The identity hash code of the object.
 * @property objectDisplayNumber The number used in string representation of the object.
 * @property objectReference A weak reference to the associated object.
 */
open class ObjectEntry(
    val objectNumber: Int,
    val objectHashCode: Int,
    val objectDisplayNumber: Int,
    val objectReference: WeakReference<Any>,
)

/**
 * A unique identifier of an object.
 *
 * The identifier is a 64-bit integer number, formed by combining its serial number and hash code.
 * The serial number [ObjectEntry.objectNumber] is stored in the higher 32-bits of the id, while
 * the identity hash code [ObjectEntry.objectHashCode] is stored in the lower 32-bits.
 */
val ObjectEntry.objectId: ObjectID get() =
    (objectNumber.toLong() shl 32) + objectHashCode.toLong()

/**
 * Extracts and returns the object number from the given object id.
 */
fun ObjectID.getObjectNumber(): Int =
    (this ushr 32).toInt()

/**
 * Extracts and returns the identity hash code of the object from the given object id.
 *
 * @return The integer hash code derived from the ObjectID.
 */
fun ObjectID.getObjectHashCode(): Int =
    this.toInt()

/**
 * Retrieves the unique serial object number for the given object.
 *
 * @param obj the object for which the object number is to be retrieved.
 * @return the unique object number if the object is registered in the tracker,
 *   or -1 if no entry is associated with the given object.
 */
fun ObjectTracker.getObjectNumber(obj: Any): Int =
    get(obj)?.objectNumber ?: -1

/**
 * Retrieves the display number of a given object.
 *
 * @param obj the object for which the display number is to be retrieved.
 * @return the display number of the object if it exists; otherwise, -1 if the object is not registered.
 */
fun ObjectTracker.getObjectDisplayNumber(obj: Any): Int =
    get(obj)?.objectDisplayNumber ?: -1

/**
 * Enumerates all objects currently tracked by the object tracker,
 * assigning each object its display number as determined by the object tracker.
 *
 * Enumeration is required for the Plugin as we want to see on the diagram if some object was replaced by a new one.
 * Uses the same a numeration map as the trace reporter via [getObjectDisplayNumber] method, so objects have the
 * same numbers, as they have in the trace.
 *
 * @return a map associating each reachable object with its display number.
 */
internal fun ObjectTracker.enumerateAllObjects(): Map<Any, Int> {
    val objectNumberMap = hashMapOf<Any, Int>()
    for (objectEntry in enumerateObjectEntries()) {
        val obj = objectEntry.objectReference.get() ?: continue
        objectNumberMap[obj] = objectEntry.objectDisplayNumber
    }
    return objectNumberMap
}

/**
 * Enumerates all objects reachable from the given root object,
 * assigning each object its display number as determined by the object tracker.
 *
 * Enumeration is required for the Plugin as we want to see on the diagram if some object was replaced by a new one.
 * Uses the same a numeration map as the trace reporter via [getObjectDisplayNumber] method, so objects have the
 * same numbers, as they have in the trace.
 *
 * @param root the object from which the traversal begins.
 * @return a map associating each reachable object with its display number.
 */
internal fun ObjectTracker.enumerateReachableObjects(root: Any): Map<Any, Int> {
    val objectNumberMap = hashMapOf<Any, Int>()
    val shouldEnumerateRecursively = { obj: Any ->
        obj !is CharSequence &&
        obj !is Continuation<*>
    }
    traverseObjectGraph(root,
        config = ObjectGraphTraversalConfig(
            traverseEnumObjects = false,
            promoteAtomicObjects = true,
        ),
        onObject = { obj ->
            objectNumberMap[obj] = getObjectDisplayNumber(obj)
            shouldEnumerateRecursively(obj)
        },
    )
    return objectNumberMap
}

/**
 * Generates a string representation of an object.
 *
 * Provides an adorned string representation for null values, primitive types, strings, enums,
 * and some other types of objects.
 * All other objects are represented in the format `className#objectNumber`.
 *
 * @param obj the object to generate a textual representation for.
 * @return a string representation of the specified object.
 */
fun ObjectTracker.getObjectRepresentation(obj: Any?): String = when {
    // null is displayed as is
    obj == null -> "null"

    // unit is displayed simply as "Unit"
    obj === Unit -> "Unit"

    // chars and strings are wrapped in quotes.
    obj is Char   -> "\'$obj\'"
    obj is String -> "\"$obj\""

    // immutable types (including primitive types) have trivial `toString` implementation
    obj.isImmutable -> obj.toString()

    // for enum types, we display their name
    obj is Enum<*> -> obj.name

    obj is Pair<*, *> ->
        "(${getObjectRepresentation(obj.first)}, ${getObjectRepresentation(obj.second)})"

    obj is Triple<*, *, *> ->
        "(${getObjectRepresentation(obj.first)}, ${getObjectRepresentation(obj.second)}, ${getObjectRepresentation(obj.third)})"

    else -> {
        // special representation for anonymous classes
        runCatching {
            if (obj.javaClass.isAnonymousClass && !obj.hasSpecialClassNameRepresentation()) {
                return obj.javaClass.anonymousClassSimpleName
            }
        }

        // all other objects are represented as `className#objectNumber`
        runCatching {
            val className = objectClassNameRepresentation(obj)
            val objectDisplayNumber = registerObjectIfAbsent(obj).objectDisplayNumber
            "$className#$objectDisplayNumber"
        }
        // There is a Kotlin compiler bug that leads to exception
        // `java.lang.InternalError: Malformed class name`
        // when trying to query for a class name of an anonymous class on JDK 8:
        // - https://youtrack.jetbrains.com/issue/KT-16727/
        // in such a case we fall back to returning `<unknown>` class name.
        .getOrElse { "<unknown>" }
    }
}

private val Class<*>.anonymousClassSimpleName: String get() {
    // Split by the package separator and return the result if this is not an inner class.
    val withoutPackage = name.substringAfterLast('.')
    if (!withoutPackage.contains("$")) return withoutPackage
    // Extract the last named inner class followed by any "$<number>" patterns using regex.
    val regex = """(.*\$)?([^\$.\d]+(\$\d+)*)""".toRegex()
    val matchResult = regex.matchEntire(withoutPackage)
    return matchResult?.groups?.get(2)?.value ?: withoutPackage
}

private fun objectClassNameRepresentation(obj: Any): String = when (obj) {
    is IntArray     -> "IntArray"
    is ShortArray   -> "ShortArray"
    is CharArray    -> "CharArray"
    is ByteArray    -> "ByteArray"
    is BooleanArray -> "BooleanArray"
    is DoubleArray  -> "DoubleArray"
    is FloatArray   -> "FloatArray"
    is LongArray    -> "LongArray"
    is Array<*>     -> "Array<${obj.javaClass.componentType.simpleName}>"

    else -> {
        val specialClassNameRepresentation = obj.getSpecialClassNameRepresentation()
        specialClassNameRepresentation?.name ?: obj.javaClass.simpleName
    }
}

private data class ClassNameRepresentation(
    val name: String,
    val classKey: Class<*>,
)

private fun Any.getSpecialClassNameRepresentation(): ClassNameRepresentation? = when {
    this is Thread                                -> ClassNameRepresentation("Thread", Thread::class.java)
    this is Continuation<*>                       -> ClassNameRepresentation("Continuation", Continuation::class.java)
    isJavaLambdaClass(javaClass.name) -> ClassNameRepresentation("Lambda", Lambda::class.java)
    else                                          -> null
}

private fun Any.hasSpecialClassNameRepresentation(): Boolean =
    getSpecialClassNameRepresentation() != null

/**
 * Since lambdas do not have a specific type, this class is used as a class key in [ClassNameRepresentation].
 */
private class Lambda

/**
 * Abstract base class for tracking objects.
 *
 * It provides an implementation for registering, retrieving, updating,
 * and managing objects and their entries in the registry.
 */
open class BaseObjectTracker(
    val executionMode: ExecutionMode
) : ObjectTracker {

    // counter of all registered objects
    private var objectCounter = 0

    // index of all registered objects
    private val objectIndex = HashMap<IdentityHashCode, MutableList<ObjectEntry>>()

    // reference queue keeping track of garbage-collected objects
    private val referenceQueue = ReferenceQueue<Any>()

    // per-class numeration map, used to assign display numbers of objects
    private val perClassObjectNumeration = WeakHashMap<Class<*>, Int>()

    /**
     * Represents the kind of object being tracked.
     *
     * Can be either:
     *   - [NEW] - Newly created object registered in the tracker.
     *   - [EXTERNAL] - External object registered in the tracker.
     */
    protected enum class ObjectKind { NEW, EXTERNAL }

    /**
     * Method responsible for creating an instance of [ObjectEntry] for tracking an object in the system.
     * Derived classes may override this method to create their own customized instances of [ObjectEntry]
     * with additional meta-data.
     */
    protected open fun createObjectEntry(
        objNumber: Int,
        objHashCode: Int,
        objDisplayNumber: Int,
        objReference: WeakReference<Any>,
        kind: ObjectKind = ObjectKind.NEW,
    ): ObjectEntry {
        return ObjectEntry(objNumber, objHashCode, objDisplayNumber, objReference)
    }

    protected fun computeObjectDisplayNumber(obj: Any): Int {
        // In the case of general-purpose model checking mode, the thread numeration starts from 0.
        val offset = if (obj is Thread && executionMode == ExecutionMode.GENERAL_PURPOSE_MODEL_CHECKER) -1 else 0
        val objClassKey = obj.getSpecialClassNameRepresentation()?.classKey ?: obj.javaClass
        return perClassObjectNumeration.update(objClassKey, default = offset) { it + 1 }
    }

    override fun registerThread(threadId: Int, thread: Thread) {
        registerObjectIfAbsent(thread)
    }

    override fun registerNewObject(obj: Any): ObjectEntry =
        registerObject(ObjectKind.NEW, obj)

    override fun registerExternalObject(obj: Any): ObjectEntry =
        registerObject(ObjectKind.EXTERNAL, obj)

    private fun registerObject(kind: ObjectKind, obj: Any): ObjectEntry {
        check(!obj.isImmutable)
        cleanup()
        val entry = createObjectEntry(
            objNumber = ++objectCounter,
            objHashCode = System.identityHashCode(obj),
            objDisplayNumber = computeObjectDisplayNumber(obj),
            objReference = WeakIdentityReference(obj, referenceQueue),
            kind = kind,
        )
        objectIndex.updateInplace(entry.objectHashCode, default = mutableListOf()) {
            cleanup()
            add(entry)
        }
        return entry
    }

    override fun registerObjectLink(fromObject: Any?, toObject: Any?) {}

    override fun shouldTrackObjectAccess(obj: Any?): Boolean =
        true // track all accesses by default

    private fun getEntries(objHashCode: IdentityHashCode): List<ObjectEntry>? {
        val entries = objectIndex[objHashCode] ?: return null
        entries.cleanup()
        if (entries.isEmpty()) {
            objectIndex.remove(objHashCode)
            return null
        }
        return entries
    }

    override operator fun get(id: ObjectID): ObjectEntry? {
        val objNumber = id.getObjectNumber()
        val objHashCode = id.getObjectHashCode()
        val entries = getEntries(objHashCode) ?: return null
        return entries.find { it.objectNumber == objNumber }
    }

    override operator fun get(obj: Any?): ObjectEntry? {
        val objHashCode = System.identityHashCode(obj)
        val entries = getEntries(objHashCode) ?: return null
        return entries.find { it.objectReference.get() === obj }
    }

    override fun enumerateObjectEntries(): Sequence<ObjectEntry> =
        objectIndex.values.asSequence().flatten()

    override fun retain(predicate: (ObjectEntry) -> Boolean) {
        objectIndex.values.retainAll { entries ->
            entries.cleanup()
            entries.retainAll(predicate)
            entries.isNotEmpty()
        }
    }

    override fun reset() {
        objectCounter = 0
        objectIndex.clear()
        referenceQueue.clear()
        perClassObjectNumeration.clear()
    }

    /**
     * Removes garbage-collected objects from the object tracker.
     */
    private fun cleanup() {
        while (true) {
            val objReference = referenceQueue.poll() ?: break
            val objHashCode = (objReference as WeakIdentityReference).hashCode()
            val entries = objectIndex[objHashCode] ?: continue
            entries.cleanup()
            if (entries.isEmpty()) {
                objectIndex.remove(objHashCode)
            }
        }
    }

    /**
     * Removes entries from the list where the associated object has been garbage collected.
     */
    private fun MutableList<ObjectEntry>.cleanup() {
        retainAll { it.objectReference.get() != null }
    }
}

private fun ReferenceQueue<Any>.clear() {
    while (true) {
        poll() ?: break
    }
}

private typealias IdentityHashCode = Int