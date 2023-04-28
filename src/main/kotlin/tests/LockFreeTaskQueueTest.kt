package tests

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.verifier.quiescent.QuiescentConsistencyVerifier
import org.jetbrains.kotlinx.lincheck.verifier.quiescent.QuiescentConsistent

// TODO: needs older version coroutines.core 1.4.0

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "SubscriberImplementation")
@OpGroupConfig(name = "consumer", nonParallel = true)
@Param(name = "value", gen = IntGen::class, conf = "1:3")
class LockFreeTaskQueueTest : AbstractLincheckTest() {
//    private val q = LockFreeTaskQueue<Int>(true)
//
//    @Operation
//    fun addLast(@Param(name = "value") value: Int) = q.addLast(value)
//
//    @QuiescentConsistent
//    @Operation(group = "consumer")
//    fun removeFirstOrNull() = q.removeFirstOrNull()
//
//    @Operation
//    fun close() = q.close()
//
//    override fun <O : Options<O, *>> O.customize() {
//        verifier(QuiescentConsistencyVerifier::class.java)
//    }
}