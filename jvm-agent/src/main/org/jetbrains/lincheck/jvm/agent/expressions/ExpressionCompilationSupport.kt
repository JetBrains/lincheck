/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions

import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.ClassModel
import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.FieldModel
import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.MethodModel
import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.loadClassModel
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.ArrayDeque

/** A source or expression the agent could not compile; the message is user-facing. */
internal class ExpressionCompilationException(message: String) : Exception(message)

/**
 * The application classes one compilation request works with, read from class resources so nothing is loaded,
 * and the access rule the generated wrapper has to respect.
 *
 * The wrapper is defined in its own loader, hence in its own runtime package: it may name platform classes and
 * public application classes directly, and nothing else. A member of any other class is reached reflectively,
 * through the class that actually declares it.
 *
 * @param known models already at hand, the enclosing class first among them: its bytes may not be on any
 *   classpath while it is still being defined.
 */
internal class ApplicationClasses(
    private val classLoader: ClassLoader?,
    known: Collection<ClassModel> = emptyList(),
) {
    private val models = HashMap<String, ClassModel?>()

    init {
        known.forEach { models[it.binaryName] = it }
    }

    fun model(binaryName: String): ClassModel? {
        if (binaryName !in models) models[binaryName] = loadClassModel(binaryName, classLoader)
        return models[binaryName]
    }

    /** Whether the wrapper may name [type] directly. A class whose bytes cannot be found is taken to be public. */
    fun isAccessible(type: Type): Boolean {
        val className = type.binaryClassName() ?: return true
        return className.isPlatformClass() || model(className)?.isPublic != false
    }

    fun isAccessible(internalName: String): Boolean = isAccessible(Type.getObjectType(internalName))

    /**
     * The field behind a direct access the wrapper cannot perform, or `null` when the access is fine as it is:
     * the owner is accessible. Resolution walks the owner's hierarchy, since the compiler names the receiver's
     * static type as owner, not the declaring class; an owner without a model is taken to declare the field.
     */
    fun reflectiveField(isStatic: Boolean, owner: String, name: String, descriptor: String): FieldModel? {
        if (isAccessible(owner)) return null
        return hierarchy(owner.replace('/', '.')).firstNotNullOfOrNull { model ->
            model.declaredFields[name]?.takeIf { it.type.descriptor == descriptor }
        } ?: FieldModel(
            declaringBinaryName = owner.replace('/', '.'),
            name = name,
            type = Type.getType(descriptor),
            isPublic = true,
            isProtected = false,
            isPrivate = false,
            isStatic = isStatic,
            isSynthetic = false,
        )
    }

    /**
     * The method behind a direct call the wrapper cannot perform, or `null` when the call is fine as it is:
     * the owner and every argument type are accessible. (An inaccessible return type needs no cast, so it is fine.)
     */
    fun reflectiveMethod(isStatic: Boolean, owner: String, name: String, descriptor: String): MethodModel? {
        if (isAccessible(owner) && Type.getArgumentTypes(descriptor).all(::isAccessible)) return null
        return hierarchy(owner.replace('/', '.')).firstNotNullOfOrNull { model ->
            model.declaredMethods.firstOrNull { it.name == name && it.descriptor == descriptor }
        } ?: MethodModel(
            declaringBinaryName = owner.replace('/', '.'),
            name = name,
            descriptor = descriptor,
            isPublic = true,
            isProtected = false,
            isPrivate = false,
            isStatic = isStatic,
            isSynthetic = false,
            isVarArgs = false,
        )
    }

    /** [binaryName] and its supertypes, nearest first, as far as their models can be found. */
    private fun hierarchy(binaryName: String): Sequence<ClassModel> = sequence {
        val pending = ArrayDeque(listOf(binaryName))
        val visited = HashSet<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            val model = model(name) ?: continue
            yield(model)
            model.superclassBinaryName?.let(pending::addLast)
            model.interfaceBinaryNames.forEach(pending::addLast)
        }
    }
}

/**
 * The compile classpath for one expression-compilation request, and the lifetime of what had to be recovered for it.
 *
 * One request compiles twice — the enclosing-class facade, then the wrapper — and both compilations
 * resolve the same application types, so the classpath is built once and reused,
 * which matters because building it can mean copying hundreds of class files out of a class loader.
 * [close] discards those copies; [entries] is meaningless afterwards.
 *
 * @property entries The classpath entries in resolution order; recovered classes, when any, come first.
 * @property recoveredClassesDirectory The temporary directory containing the recovered classes, or `null` if none.
 */
internal class ExpressionClasspath(
    val entries: List<File>,
    private val recoveredClassesDirectory: File?,
) : Closeable {
    override fun close() {
        recoveredClassesDirectory?.deleteRecursively()
    }
}

/**
 * Returns the file-backed classpath visible to an application class loader.
 *
 * Classes required from loaders that expose no file URLs, including Spring Boot nested-jar loaders,
 * are copied by exact resource name into a temporary directory prepended to the classpath;
 * the caller releases it through [ExpressionClasspath.close].
 *
 * @param classLoader the loader of the class an expression is compiled against;
 *   it and its parents contribute their `file:` URLs, on top of the JVM's own `java.class.path`.
 * @param requiredClassNames binary names that must resolve even when no classpath entry carries them;
 *   see [materializeClassResources] for how they are recovered and [expressionClassNames] for how they are collected.
 *   Empty — the usual case of a plain URL loader — needs no temporary directory at all.
 * @throws ExpressionCompilationException if recovering [requiredClassNames] exceeds its size bound.
 */
internal fun expressionClasspath(
    classLoader: ClassLoader?,
    requiredClassNames: Set<String> = emptySet(),
): ExpressionClasspath {
    val classpathEntriesSet = LinkedHashSet<File>()
    System.getProperty("java.class.path")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?.forEach { classpathEntriesSet += File(it) }
    var loader = classLoader
    while (loader != null) {
        if (loader is URLClassLoader) {
            loader.urLs.forEach { url ->
                if (url.protocol == "file") {
                    runCatching { classpathEntriesSet += File(url.toURI()) }
                }
            }
        }
        loader = loader.parent
    }

    var classpathEntries: List<File> = classpathEntriesSet.toList()
    var recoveredClassesDirectory: File? = null
    if (requiredClassNames.isNotEmpty()) {
        val directory = Files.createTempDirectory("lincheck-expr-classpath").toFile()
        recoveredClassesDirectory = directory
        try {
            materializeClassResources(classLoader, requiredClassNames, directory)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
        classpathEntries = listOf(directory) + classpathEntries
    }
    return ExpressionClasspath(classpathEntries.filter { it.exists() }, recoveredClassesDirectory)
}

/**
 * Returns every application type named by the generated expression-compilation sources.
 *
 * The generated sources reproduce the declaration surface of [models] —
 * the enclosing class, its superclass facades, and its nested classes —
 * so every type appearing in that surface has to resolve at compile time:
 * superclasses, field types, and method and constructor signatures.
 * [types] adds the captured locals' own types, which the wrapper declares as parameters.
 * Array types contribute their element type, and platform types are dropped,
 * since the compile classpath already carries the JDK and the Kotlin runtime.
 *
 * For an enclosing class
 * ```kotlin
 * class Counter(private val store: CounterStore, private val label: String) : AbstractCounter() {
 *     fun add(delta: Delta): Total = ...
 * }
 * ```
 * and a single `Limit`-typed capture, the result is
 * `{Counter, AbstractCounter, CounterStore, Delta, Total, Limit}`:
 * `java.lang.String` is dropped as a platform class, and primitive members contribute no name at all.
 *
 * Only the declaration surface is walked here;
 * [materializeClassResources] closes over what those classes in turn reference.
 */
internal fun expressionClassNames(models: Collection<ClassModel>, types: Collection<Type>): Set<String> = buildSet {
    models.forEach { model ->
        add(model.binaryName)
        model.superclassBinaryName?.let(::add)
        model.nestedClasses.mapTo(this) { it.binaryName }
        model.declaredFields.values.mapNotNullTo(this) { it.type.binaryClassName() }
        model.declaredMethods.forEach { method ->
            Type.getArgumentTypes(method.descriptor).mapNotNullTo(this) { it.binaryClassName() }
            Type.getReturnType(method.descriptor).binaryClassName()?.let(::add)
        }
        model.declaredConstructors.forEach { constructor ->
            Type.getArgumentTypes(constructor.descriptor).mapNotNullTo(this) { it.binaryClassName() }
        }
    }
    types.mapNotNullTo(this) { it.binaryClassName() }
    removeIf(String::isPlatformClass)
}

/**
 * Copies [requiredClassNames], and everything their declarations transitively reference,
 * out of [classLoader] into [resourceDirectory], laid out as a class directory (`a/b/C.class`).
 *
 * This is the escape hatch for loaders a compiler cannot see through `-classpath`:
 * a Spring Boot fat jar's nested-jar loader owns the application classes
 * but exposes no `file:` URL for them, while still answering [ClassLoader.getResourceAsStream].
 * Reading each class as a resource and writing it out turns such a loader back into a directory the compiler can use.
 *
 * The walk is transitive because the compiler needs more than the names it was handed.
 * Requesting only `resource.only.Root` for
 * ```java
 * package resource.only;
 * public class Root extends Base { public Leaf leaf; }
 * ```
 * also writes `resource/only/Base.class` and `resource/only/Leaf.class`,
 * since resolving `Root` at all requires its supertype, and reading `root.leaf` requires `Leaf`.
 *
 * Names no loader resolves are skipped rather than failing:
 * a genuinely missing class then surfaces as a compiler diagnostic about the expression,
 * which is the error the user can act on.
 * Bytes that cannot be parsed likewise stop the traversal at that class instead of aborting the compilation,
 * and are reported at debug level — the compiler diagnostic they eventually cause names a type, not a cause.
 *
 * @throws ExpressionCompilationException if the closure exceeds [MAX_MATERIALIZED_CLASSES] classes,
 *   which bounds the work a single expression can impose on a live application.
 */
private fun materializeClassResources(
    classLoader: ClassLoader?,
    requiredClassNames: Set<String>,
    resourceDirectory: File,
) {
    val pending = ArrayDeque(requiredClassNames.filterNot(String::isPlatformClass))
    val visited = HashSet<String>()
    var materializedCount = 0
    while (pending.isNotEmpty()) {
        val binaryName = pending.removeFirst()
        if (!visited.add(binaryName)) continue
        val resourceName = binaryName.replace('.', '/') + ".class"
        val bytes = (classLoader?.getResourceAsStream(resourceName)
            ?: ClassLoader.getSystemResourceAsStream(resourceName))?.use { it.readBytes() }
            ?: continue
        if (++materializedCount > MAX_MATERIALIZED_CLASSES) {
            throw ExpressionCompilationException(
                "Expression classpath requires more than $MAX_MATERIALIZED_CLASSES application classes",
            )
        }
        File(resourceDirectory, resourceName).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
        try {
            enqueueReferencedClasses(bytes, pending)
        } catch (failure: RuntimeException) {
            // The class itself already reached the classpath; only the types it references are lost,
            // which surfaces later as a compiler diagnostic naming one of them.
            Logger.debug { "Expression classpath: cannot scan $binaryName for referenced classes: $failure" }
        }
    }
}

private const val MAX_MATERIALIZED_CLASSES = 1024

/**
 * Adds to [pending] every non-platform application class named by the declaration surface of [bytes]:
 * the superclass and interfaces, field types, method parameter, return and `throws` types,
 * and the class types mentioned inside generic signatures — so a `List<Order>` field contributes `Order`.
 *
 * Method bodies are deliberately not parsed (`SKIP_CODE`).
 * A compiler only needs the types a declaration mentions;
 * the ones an implementation happens to touch would balloon the closure to most of the application.
 */
private fun enqueueReferencedClasses(bytes: ByteArray, pending: ArrayDeque<String>) {
    fun enqueue(type: Type) {
        type.binaryClassName()?.takeUnless(String::isPlatformClass)?.let(pending::add)
    }

    fun enqueueSignature(signature: String?, typeOnly: Boolean = false) {
        if (signature == null) return
        try {
            val visitor = object : SignatureVisitor(Opcodes.ASM9) {
                override fun visitClassType(name: String) = enqueue(Type.getObjectType(name))
            }
            if (typeOnly) SignatureReader(signature).acceptType(visitor) else SignatureReader(signature).accept(visitor)
        } catch (failure: RuntimeException) {
            // Only this member's generic types are lost; the rest of the class is still scanned.
            Logger.debug { "Expression classpath: cannot parse signature '$signature': $failure" }
        }
    }

    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            superName?.let(Type::getObjectType)?.let(::enqueue)
            interfaces.orEmpty().map(Type::getObjectType).forEach(::enqueue)
            enqueueSignature(signature)
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            enqueue(Type.getType(descriptor))
            enqueueSignature(signature, typeOnly = true)
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            Type.getArgumentTypes(descriptor).forEach(::enqueue)
            enqueue(Type.getReturnType(descriptor))
            exceptions.orEmpty().map(Type::getObjectType).forEach(::enqueue)
            enqueueSignature(signature)
            return null
        }
    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
}

/**
 * Returns the binary name of the class this type denotes, or `null` if it denotes no class.
 *
 * Array types unwrap to their element type, so `com.example.Order[][]` yields `com.example.Order`,
 * while primitives, primitive arrays, and `void` — `int`, `int[]`, `V` — yield `null`,
 * having no class file to resolve.
 */
internal fun Type.binaryClassName(): String? {
    var element = this
    while (element.sort == Type.ARRAY) element = Type.getType(element.descriptor.substring(1))
    return element.className.takeIf { element.sort == Type.OBJECT }
}

/** Whether this type, or its array element type, names a class outside the platform. */
internal fun Type.isApplicationType(): Boolean = binaryClassName()?.isPlatformClass() == false

/**
 * Whether this binary name belongs to the JDK or the Kotlin runtime,
 * both of which every expression compile classpath already provides.
 *
 * `java.util.List`, `kotlin.Pair` and `sun.misc.Unsafe` are platform classes and need no recovery;
 * `com.example.Order` is an application class and does.
 */
internal fun String.isPlatformClass(): Boolean =
    startsWith("java.") || startsWith("javax.") || startsWith("jdk.") || startsWith("sun.") ||
        startsWith("kotlin.")
