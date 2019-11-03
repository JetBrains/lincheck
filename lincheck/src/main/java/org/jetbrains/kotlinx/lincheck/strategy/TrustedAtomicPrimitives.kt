package org.jetbrains.kotlinx.lincheck.strategy

/**
 * Some atomic primitives are common and can be analyzed from a higher level of abstraction
 * or can not be transformed (i.e, Unsafe or AFU).
 * We don't transform them and improve the output of lincheck for them.
 * For example, in the execution instead of a codelocation in AtomicLong.get() method we could just print the codelocation
 * where the method is called with an increase of understandability.
 */
internal object TrustedAtomicPrimitives {
    private val trustedAtomicPrimitives = listOf<(className: String)->Boolean>(
            { it == "java/lang/invoke/VarHandle" },
            { it == "sun/misc/Unsafe" },
            { it.startsWith("java/util/concurrent/atomic/Atomic")}, // AFUs and Atomic[Integer/Long/...]
            { it.startsWith("kotlinx/atomicfu/Atomic")}
    )

    private val writeKeyWords = listOf("write", "set", "swap", "put", "mark", "update")
    private val readKeyWords = listOf("read", "get", "is")

    fun isTrustedPrimitive(className: String) = trustedAtomicPrimitives.any { it(className) }

    fun classifyTrustedMethod(className: String, methodName: String): AtomicPrimitiveMethodType {
        check(isTrustedPrimitive(className))
        // can add different logic for all primitives, but it is hard to maintain, so a method is judged only by its name
        val loweredMethodName = methodName.toLowerCase()
        if (writeKeyWords.any { loweredMethodName.contains(it) })
            return AtomicPrimitiveMethodType.WRITE
        if (readKeyWords.any { loweredMethodName.contains(it) })
            return AtomicPrimitiveMethodType.READ

        return AtomicPrimitiveMethodType.NONE
    }
}

internal enum class AtomicPrimitiveMethodType {
    READ,
    WRITE,
    NONE
}