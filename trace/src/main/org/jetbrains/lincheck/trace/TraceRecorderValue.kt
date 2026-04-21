package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.ClassDescriptor
import java.io.DataInput
import java.io.DataOutput
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

sealed class TRValue {
    internal abstract val classNameId: Int
    abstract val identityHashCode: Int
    abstract val className: String

    open val isSpecial: Boolean get() = classNameId < 0
}

@ConsistentCopyVisibility
data class TRObject internal constructor(
    override val classNameId: Int,
    override val identityHashCode: Int,
    override val className: String,
    val fields: Map<String, TRValue?> = emptyMap(),
) : TRValue() {
    internal constructor(classNameId: Int, identityHashCode: Int, cd: ClassDescriptor, fields: Map<String, TRValue?> = emptyMap()):
            this(classNameId, identityHashCode, cd.name, fields)

    override fun toString(): String {
        // already set to either 'null' or 'void'
        if (classNameId == TR_OBJECT_NULL_CLASSNAME || classNameId == TR_OBJECT_VOID_CLASSNAME) return className
        return className.adornedClassNameRepresentation() + "@" + identityHashCode
    }
}

@ConsistentCopyVisibility
data class TRPrimitive private constructor(
    override val classNameId: Int,
    override val identityHashCode: Int,
    override val className: String,
    val primitiveValue: Any
) : TRValue() {
    internal constructor(classNameId: Int, identityHashCode: Int, primitiveValue: Any):
            this(classNameId, identityHashCode, primitiveValue.javaClass.name, primitiveValue)

    override fun toString(): String = when (primitiveValue) {
        is String -> {
            when (classNameId) {
                TR_OBJECT_P_RAW_STRING, TR_OBJECT_P_JAVA_CLASS, TR_OBJECT_P_KOTLIN_CLASS -> primitiveValue
                TR_OBJECT_P_STRING_BUILDER -> "StringBuilder@$identityHashCode(\"${primitiveValue.escape()}\")"
                else -> "\"${primitiveValue.escape()}\""
            }
        }
        is Char -> "'$primitiveValue'"
        is Unit -> "Unit"
        else -> primitiveValue.toString()
    }
}

@ConsistentCopyVisibility
data class TRArray private constructor(
    override val classNameId: Int,
    override val identityHashCode: Int,
    override val className: String,
    val totalSize: Int,
    val capturedElements: List<TRValue?>,
) : TRValue() {
    override val isSpecial = false

    internal constructor(classNameId: Int, identityHashCode: Int, cd: ClassDescriptor, totalSize: Int, elements: List<TRValue?> = emptyList()):
            this(classNameId, identityHashCode, cd.name, totalSize, elements)

    override fun toString(): String {
        return className.adornedClassNameRepresentation() + "@" + identityHashCode
    }
}

const val UNFINISHED_METHOD_RESULT_SYMBOL = "<unfinished method>"
const val UNTRACKED_METHOD_RESULT_SYMBOL = "<untracked result>"
val TR_OBJECT_UNFINISHED_METHOD_RESULT = TRPrimitive(TR_OBJECT_P_STRING, 0, UNFINISHED_METHOD_RESULT_SYMBOL)
val TR_OBJECT_UNTRACKED_METHOD_RESULT = TRPrimitive(TR_OBJECT_P_STRING, 0, UNTRACKED_METHOD_RESULT_SYMBOL)

const val TR_OBJECT_NULL_CLASSNAME = -1
const val TR_OBJECT_VOID_CLASSNAME = -2
const val TR_OBJECT_P_BYTE = -3
const val TR_OBJECT_P_SHORT = -4
const val TR_OBJECT_P_INT = -5
const val TR_OBJECT_P_LONG = -6
const val TR_OBJECT_P_FLOAT = -7
const val TR_OBJECT_P_DOUBLE = -8
const val TR_OBJECT_P_CHAR = -9
const val TR_OBJECT_P_STRING = -10
const val TR_OBJECT_P_UNIT = -11
const val TR_OBJECT_P_RAW_STRING = -12
const val TR_OBJECT_P_BOOLEAN = -13
const val TR_OBJECT_P_JAVA_CLASS = -14
const val TR_OBJECT_P_KOTLIN_CLASS = -15
const val TR_OBJECT_P_STRING_BUILDER = -16

val TR_OBJECT_NULL = TRObject(TR_OBJECT_NULL_CLASSNAME, 0, "null")
val TR_OBJECT_VOID = TRObject(TR_OBJECT_VOID_CLASSNAME, 0, "void")

fun TRObjectOrNull(context: TraceContext, obj: Any?): TRValue? =
    obj?.let { TRValue(context, it) }

fun TRObjectOrVoid(context: TraceContext, obj: Any?): TRValue? =
    if (obj === INJECTIONS_VOID_OBJECT) TR_OBJECT_VOID
    else TRObjectOrNull(context, obj)


const val MAX_TROBJECT_STRING_LENGTH = 50

private fun CharSequence.trimToString(): String {
    val string = toString()
    return if (string.length > MAX_TROBJECT_STRING_LENGTH) "${string.take(MAX_TROBJECT_STRING_LENGTH)}..." else string
}

private val WHITELIST_PACKAGES_FOR_TO_STRING = listOf(
    // StringBuffer, StringBuilder
    "java.lang.",
    // CharBuffer
    "java.nio.",
    // Segment
    "javax.swing.",
    // Kotlin "wrappers"
    "kotlin.text.",
    "kotlin."
)

private fun classNameWhitelisted(obj: Any): Boolean {
    val fullClassName = obj.javaClass.name
    return WHITELIST_PACKAGES_FOR_TO_STRING.any { fullClassName.startsWith(it) }
}

fun TRObjectWithFields(context: TraceContext, obj: Any, fields: Map<String, Any?>): TRObject {
    val cd = context.createAndRegisterClassDescriptor(obj.javaClass.name)
    val trObjectMap = fields.mapValues { (_, value) -> TRObjectOrNull(context, value) }
    return TRObject(cd.id, System.identityHashCode(obj), cd, trObjectMap)
}

fun TRArrayWithElements(context: TraceContext, arr: Any, size: Int, elements: List<Any?>): TRArray {
    val cd = context.createAndRegisterClassDescriptor(arr.javaClass.name)
    val elementsAsTRValues = elements.map { value -> TRObjectOrNull(context, value) }
    return TRArray(cd.id, System.identityHashCode(arr), cd, size, elementsAsTRValues)
}

fun TRValue(context: TraceContext, obj: Any): TRValue {
    val defaultTRObject = {
        val cd = context.createAndRegisterClassDescriptor(obj.javaClass.name)
        TRObject(cd.id, System.identityHashCode(obj), cd)
    }

    val defaultTRArray = { size: Int ->
        val cd = context.createAndRegisterClassDescriptor(obj.javaClass.name)
        TRArray(cd.id, System.identityHashCode(obj), cd, size)
    }

    return when (obj) {
        is Byte -> TRPrimitive(TR_OBJECT_P_BYTE, 0, obj)
        is Short -> TRPrimitive(TR_OBJECT_P_SHORT, 0, obj)
        is Int -> TRPrimitive(TR_OBJECT_P_INT, 0, obj)
        is Long -> TRPrimitive(TR_OBJECT_P_LONG, 0, obj)
        is Float -> TRPrimitive(TR_OBJECT_P_FLOAT, 0, obj)
        is Double -> TRPrimitive(TR_OBJECT_P_DOUBLE, 0, obj)
        is Char -> TRPrimitive(TR_OBJECT_P_CHAR, 0, obj)
        is String -> TRPrimitive(TR_OBJECT_P_STRING, 0, obj.trimToString())
        is StringBuilder -> TRPrimitive(
            TR_OBJECT_P_STRING_BUILDER, System.identityHashCode(obj), obj.trimToString()
        )

        // If user (traced) code contains CharSequence implementation, it is a bad idea to call it
        // as there is no guarantee that this implementation doesn't have side effects.
        // Guard by Java and Kotlin std lib packages
        is CharSequence if (classNameWhitelisted(obj)) -> runCatching { obj.trimToString() }.let {
            // Some implementations of CharSequence might throw when `subSequence` is invoked at some unexpected moment,
            // like when this sequence is considered "destroyed" at this point
            if (it.isSuccess) TRPrimitive(TR_OBJECT_P_STRING, 0, it.getOrThrow())
            else defaultTRObject()
        }

        is Unit -> TRPrimitive(TR_OBJECT_P_UNIT, 0, obj)
        is Boolean -> TRPrimitive(TR_OBJECT_P_BOOLEAN, 0, obj)

        // Render these types to strings for simplicity
        is Enum<*> -> TRPrimitive(TR_OBJECT_P_RAW_STRING, 0, "${obj.javaClass.simpleName}.${obj.name}")
        is BigInteger -> TRPrimitive(TR_OBJECT_P_RAW_STRING, 0, obj.toString())
        is BigDecimal -> TRPrimitive(TR_OBJECT_P_RAW_STRING, 0, obj.toString())
        is Class<*> -> TRPrimitive(TR_OBJECT_P_JAVA_CLASS, 0, "${obj.simpleName}.class")
        is KClass<*> -> TRPrimitive(TR_OBJECT_P_KOTLIN_CLASS, 0, "${obj.simpleName}.kclass")

        // Arrays
        is Array<*> -> defaultTRArray(obj.size)
        is IntArray -> defaultTRArray(obj.size)
        is LongArray -> defaultTRArray(obj.size)
        is ByteArray -> defaultTRArray(obj.size)
        is ShortArray -> defaultTRArray(obj.size)
        is CharArray -> defaultTRArray(obj.size)
        is FloatArray -> defaultTRArray(obj.size)
        is DoubleArray -> defaultTRArray(obj.size)
        is BooleanArray -> defaultTRArray(obj.size)

        // Generic case
        // TODO Make parametrized
        else -> defaultTRObject()
    }
}

internal fun DataOutput.writeTRValue(value: TRValue?) {
    when (value) {
        null -> {
            writeInt(TR_OBJECT_NULL_CLASSNAME)
        }

        is TRObject -> {
            // Negatives are special markers
            writeInt(value.classNameId)
            if (value.classNameId >= 0) {
                writeInt(value.identityHashCode)

                // Positive for objects
                writeInt(value.fields.size)
                value.fields.forEach { (fieldName, fieldValue) ->
                    writeUTF(fieldName)
                    this@writeTRValue.writeTRValue(fieldValue)
                }
            }
        }

        is TRPrimitive -> {
            writeInt(value.classNameId)
            when (value.primitiveValue) {
                is Byte -> writeByte(value.primitiveValue.toInt())
                is Short -> writeShort(value.primitiveValue.toInt())
                is Int -> writeInt(value.primitiveValue)
                is Long -> writeLong(value.primitiveValue)
                is Float -> writeFloat(value.primitiveValue)
                is Double -> writeDouble(value.primitiveValue)
                is Char -> writeChar(value.primitiveValue.code)
                is String if value.classNameId == TR_OBJECT_P_STRING_BUILDER -> {
                    writeInt(value.identityHashCode)
                    writeUTF(value.primitiveValue)
                }

                is String -> writeUTF(value.primitiveValue) // Both STRING and RAW_STRING
                is Boolean -> writeBoolean(value.primitiveValue)
                is Unit -> {}
                else -> error("Unknow primitive value ${value.primitiveValue}")
            }
        }

        is TRArray -> {
            writeInt(value.classNameId)
            if (value.classNameId >= 0) {
                writeInt(value.identityHashCode)

                // Negative for arrays where -1 is empty array
                val encodedElementsSize = (value.capturedElements.size + 1) * -1
                writeInt(encodedElementsSize)
                value.capturedElements.forEach { element -> this@writeTRValue.writeTRValue(element) }
                writeInt(value.totalSize)
            }
        }
    }
}

internal fun DataInput.readTRObject(context: TraceContext): TRValue? {
    return when (val classNameId = readInt()) {
        TR_OBJECT_NULL_CLASSNAME -> null
        TR_OBJECT_VOID_CLASSNAME -> TR_OBJECT_VOID
        TR_OBJECT_P_BYTE -> TRPrimitive(classNameId, 0, readByte())
        TR_OBJECT_P_SHORT -> TRPrimitive(classNameId, 0, readShort())
        TR_OBJECT_P_INT -> TRPrimitive(classNameId, 0, readInt())
        TR_OBJECT_P_LONG -> TRPrimitive(classNameId, 0, readLong())
        TR_OBJECT_P_FLOAT -> TRPrimitive(classNameId, 0, readFloat())
        TR_OBJECT_P_DOUBLE -> TRPrimitive(classNameId, 0, readDouble())
        TR_OBJECT_P_CHAR -> TRPrimitive(classNameId, 0, readChar())
        TR_OBJECT_P_STRING -> TRPrimitive(classNameId, 0, readUTF())
        TR_OBJECT_P_UNIT -> TRPrimitive(classNameId, 0, Unit)
        TR_OBJECT_P_RAW_STRING -> TRPrimitive(classNameId, 0, readUTF())
        TR_OBJECT_P_BOOLEAN -> TRPrimitive(classNameId, 0, readBoolean())
        TR_OBJECT_P_JAVA_CLASS -> TRPrimitive(classNameId, 0, readUTF())
        TR_OBJECT_P_KOTLIN_CLASS -> TRPrimitive(classNameId, 0, readUTF())
        TR_OBJECT_P_STRING_BUILDER -> TRPrimitive(classNameId, readInt(), readUTF())
        else if (classNameId >= 0) -> {
            val identityHashCode = readInt()
            val childrenSize = readInt()
            if (childrenSize < 0) {
                val decodedElementsSize = childrenSize * -1 - 1
                val capturedElements = buildList {
                    repeat(decodedElementsSize) {
                        add(readTRObject(context))
                    }
                }
                val totalSize = readInt()
                TRArray(classNameId, identityHashCode, context.classPool[classNameId], totalSize, capturedElements)
            } else {
                val fields = buildMap {
                    repeat(childrenSize) {
                        val fieldName = readUTF()
                        val fieldValue = readTRObject(context)
                        put(fieldName, fieldValue)
                    }
                }
                TRObject(classNameId, identityHashCode, context.classPool[classNameId], fields)
            }
        }
        else -> error("TRObject: Unknown Class Id $classNameId")
    }
}

private fun String.escape() = this
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")