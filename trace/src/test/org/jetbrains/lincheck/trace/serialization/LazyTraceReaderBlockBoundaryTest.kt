package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.AccessCodeLocation
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.LocalVariableAccessLocation
import org.jetbrains.lincheck.descriptors.MethodCallCodeLocation
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.TraceMethodCallTracePoint
import org.jetbrains.lincheck.trace.TraceNull
import org.jetbrains.lincheck.trace.TraceScalar
import org.jetbrains.lincheck.trace.TraceWriteLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.createAndRegisterMethodDescriptor
import org.jetbrains.lincheck.trace.createAndRegisterVariableDescriptor
import org.jetbrains.lincheck.trace.tree.readTraceTrees
import org.jetbrains.lincheck.trace.tree.structure
import org.junit.Test
import java.io.File

/**
 * Reading a container whose opening record ends exactly at the end of its data block.
 *
 * The children of such a container start in the *next* block of the same thread.
 * The writer, which knows nothing about blocks, indexes that as one logical offset,
 * while the reader stands on the previous block's last data byte —
 * a different physical offset for the same logical one.
 * The two are comparable only as logical offsets.
 */
class LazyTraceReaderBlockBoundaryTest {

    /**
     * The trace is `root(someVar)`, written with a per-thread buffer too small to hold both records.
     * The variable write then overflows the buffer right after `root`'s opening record,
     * so the block is flushed exactly there and `root`'s children land in the next block.
     *
     * Which buffer sizes produce that layout depends on the exact record sizes,
     * so a small range around it is swept; every trace must read back with the structure it was written with,
     * both from the index and by rescanning the data when the index is gone.
     */
    @Test
    fun testContainerWhoseOpeningRecordEndsItsBlock() {
        for (bufferSize in MIN_SWEPT_BUFFER_SIZE..MAX_SWEPT_BUFFER_SIZE) {
            val traceFileName = writeTrace(bufferSize)

            val fromIndex = LazyTraceReader(traceFileName).use { it.readTraceTrees().single().structure() }
            check(fromIndex == EXPECTED_STRUCTURE) {
                "Trace written with a $bufferSize-byte buffer read back as $fromIndex"
            }

            val indexFile = File("$traceFileName.$INDEX_FILENAME_EXT")
            check(indexFile.delete()) { "Cannot delete the index of $traceFileName" }
            val withoutIndex = LazyTraceReader(traceFileName).use { it.readTraceTrees().single().structure() }
            check(withoutIndex == EXPECTED_STRUCTURE) {
                "Trace written with a $bufferSize-byte buffer read back as $withoutIndex without an index"
            }
        }
    }

    /** Writes `root(someVar)` into a fresh trace file with the given per-thread buffer size. */
    private fun writeTrace(bufferSize: Int): String {
        val context = TraceContext()
        val root = createMethodCallTracePoint(context, "root")
        val varAssignment = createVariableWriteTracePoint(context, "someVar")

        val traceFile = File.createTempFile("trace_test", ".trace").apply { deleteOnExit() }
        File("${traceFile.absolutePath}.$INDEX_FILENAME_EXT").deleteOnExit()
        val collector = FileStreamingTraceCollecting(traceFile.absolutePath, context, bufferSize)

        val thread = Thread.currentThread()
        collector.registerCurrentThread(THREAD_ID)
        collector.tracePointCreated(parent = null, root)
        collector.tracePointCreated(parent = root, varAssignment)
        collector.completeContainerTracePoint(thread, root, root.completeTracePoint())
        collector.completeThread(thread)
        collector.traceEnded()

        return traceFile.absolutePath
    }

    private fun createMethodCallTracePoint(context: TraceContext, methodName: String): TraceMethodCallTracePoint {
        val methodType = Types.MethodType(Types.OBJECT_TYPE)
        val md = context.createAndRegisterMethodDescriptor("com.example.SomeClass", methodName, methodType)
        val codeLocationId = context.codeLocationsPool.register(
            MethodCallCodeLocation(
                StackTraceElement(md.className, md.methodName, "Example.java", 10),
                accessPath = null,
                argumentNames = null
            )
        )
        return TraceMethodCallTracePoint(
            context,
            THREAD_ID,
            codeLocationId,
            methodId = md.id,
            obj = TraceNull,
            parameters = listOf()
        )
    }

    private fun createVariableWriteTracePoint(
        context: TraceContext,
        variableName: String
    ): TraceWriteLocalVariableTracePoint {
        val vd = context.createAndRegisterVariableDescriptor(variableName, Types.INT_TYPE)
        val codeLocationId = context.codeLocationsPool.register(
            AccessCodeLocation(
                StackTraceElement("com.example.SomeClass", "someMethod", "Example.java", 20),
                accessPath = AccessPath(listOf(LocalVariableAccessLocation(vd)))
            )
        )
        return TraceWriteLocalVariableTracePoint(
            context,
            THREAD_ID,
            codeLocationId,
            localVariableId = vd.id,
            value = TraceScalar(42)
        )
    }

    private companion object {
        const val THREAD_ID = 0
        const val EXPECTED_STRUCTURE = "root(writeVar(someVar))"

        /** The swept per-thread buffer sizes: the range in which the variable write lands in the next block. */
        const val MIN_SWEPT_BUFFER_SIZE = 189
        const val MAX_SWEPT_BUFFER_SIZE = 205
    }
}
