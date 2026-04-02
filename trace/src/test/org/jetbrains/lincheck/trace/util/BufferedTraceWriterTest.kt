/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.util

import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.Logger
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread


class BufferedTraceWriterTest {

    @Test
    fun testDataSavedTwiceWhenNotInFileYet() {
        val context = TraceContext()
        val tr1 = createBasicMethodCallTracePoint(context, 1, "com.example.SomeClass", "methodName1")
        val tr2 = createBasicMethodCallTracePoint(context, 2, "com.example.SomeClass", "methodName2")
        val sharedCall1 = createBasicMethodCallTracePoint(context, tr1.threadId, "com.example.SomeClass", "sharedMethod")
        val sharedCall2 = createBasicMethodCallTracePoint(context, tr2.threadId, "com.example.SomeClass", "sharedMethod")
        val varAssignment1 = createVariableWriteTracePoint(context, tr1.threadId, "sharedVar")
        val varAssignment2 = createVariableWriteTracePoint(context, tr2.threadId, "sharedVar")

        val traceFile = File.createTempFile("trace_test", ".trace").apply { deleteOnExit() }
        val collector = FileStreamingTraceCollecting(traceFile.absolutePath, context)

        val latch = CountDownLatch(1)
        val barrier = CyclicBarrier(2)

        val t1 = thread(name = "TestThread-1") {
            collector.registerCurrentThread(tr1.threadId)
            collector.tracePointCreated(parent = null, tr1)

            // Add a variable assignment tracepoint
            collector.tracePointCreated(parent = tr1, varAssignment1)

            // Add nested shared method call
            collector.tracePointCreated(parent = tr1, sharedCall1)
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall1)

            collector.completeContainerTracePoint(Thread.currentThread(), tr1)

            latch.countDown()
            barrier.await() // wait for the second thread to register a new trace point

            collector.completeThread(Thread.currentThread())
        }

        latch.await() // we want to ensure correct assignment of ids for saved strings, so we wait
                      // for the first thread to write data to its buffered writer before starting the second one
        val t2 = thread(name = "TestThread-2") {
            collector.registerCurrentThread(tr2.threadId)
            collector.tracePointCreated(parent = null, tr2)

            // Add a variable assignment tracepoint (same variable as in thread 1)
            collector.tracePointCreated(parent = tr2, varAssignment2)

            // Add nested shared method call (same method as in thread 1)
            collector.tracePointCreated(parent = tr2, sharedCall2)
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall2)

            collector.completeContainerTracePoint(Thread.currentThread(), tr2)

            barrier.await()

            collector.completeThread(Thread.currentThread())
        }

        // both threads have flushed their buffered writers only when
        // both of them have appended their data blocks for saving
        t1.join()
        t2.join()

        collector.traceEnded()

        val loadedTrace = loadRecordedTrace(traceFile.absolutePath)
        printRecorderTrace(System.out, loadedTrace.context, loadedTrace.roots, true)
        val blocks = collectSavedBlocks(loadedTrace.context, traceFile)
        check(blocks.size == 2 && blocks.containsKey(1) && blocks.containsKey(2)) { "Expected 2 blocks for both threads, got thread ids: ${blocks.keys}" }

        val expectedClassDescriptorIds = setOf(0 /* SomeClass */)
        val expectedMethodDescriptorIds1 = setOf(0 /* methodName1 */, 2 /* sharedMethod */)
        val expectedMethodDescriptorIds2 = setOf(1 /* methodName2 */, 2 /* sharedMethod */)
        val expectedVariableDescriptorIds = setOf(0 /* sharedVar */)
        val expectedStringIds1 = setOf(0 /* Example.java */, 1 /* com.example.SomeClass */, 2 /* methodName1 */, 3 /* someMethod */, 4 /* sharedMethod */)
        val expectedStringIds2 = setOf(0 /* Example.java */, 1 /* com.example.SomeClass */, 5 /* methodName2 */, 3 /* someMethod */, 4 /* sharedMethod */)

        blocks[1]!!.run {
            checkClassDescriptors(expectedClassDescriptorIds)
            checkMethodDescriptors(expectedMethodDescriptorIds1)
            checkVariableDescriptors(expectedVariableDescriptorIds)
            checkStrings(expectedStringIds1)
        }
        blocks[2]!!.run {
            checkClassDescriptors(expectedClassDescriptorIds)
            checkMethodDescriptors(expectedMethodDescriptorIds2)
            checkVariableDescriptors(expectedVariableDescriptorIds)
            checkStrings(expectedStringIds2)
        }
    }

    @Test
    fun testDataDeduplicatedWhenSavedToFile() {
        val context = TraceContext()
        val tr1 = createBasicMethodCallTracePoint(context, 1, "com.example.SomeClass", "methodName1")
        val tr2 = createBasicMethodCallTracePoint(context, 2, "com.example.SomeClass", "methodName2")
        val sharedCall1 = createBasicMethodCallTracePoint(context, tr1.threadId, "com.example.SomeClass", "sharedMethod")
        val sharedCall2 = createBasicMethodCallTracePoint(context, tr2.threadId, "com.example.SomeClass", "sharedMethod")
        val varAssignment1 = createVariableWriteTracePoint(context, tr1.threadId, "sharedVar")
        val varAssignment2 = createVariableWriteTracePoint(context, tr2.threadId, "sharedVar")

        val traceFile = File.createTempFile("trace_test", ".trace").apply { deleteOnExit() }
        val collector = FileStreamingTraceCollecting(traceFile.absolutePath, context)

        val latch = CountDownLatch(1)

        val t1 = thread(name = "TestThread-1") {
            collector.registerCurrentThread(tr1.threadId)
            collector.tracePointCreated(parent = null, tr1)

            // Add a variable assignment tracepoint
            collector.tracePointCreated(parent = tr1, varAssignment1)

            // Add nested shared method call
            collector.tracePointCreated(parent = tr1, sharedCall1)
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall1)

            collector.completeContainerTracePoint(Thread.currentThread(), tr1)
            collector.completeThread(Thread.currentThread())
        }

        val t2 = thread(name = "TestThread-2") {
            collector.registerCurrentThread(tr2.threadId)

            // Thread 2 fully awaits Thread 1 completion in order to see that it saved common descriptors
            latch.await()

            collector.tracePointCreated(parent = null, tr2)

            // Add a variable assignment tracepoint (same variable as in thread 1)
            collector.tracePointCreated(parent = tr2, varAssignment2)

            // Add nested shared method call (same method as in thread 1)
            collector.tracePointCreated(parent = tr2, sharedCall2)
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall2)

            collector.completeContainerTracePoint(Thread.currentThread(), tr2)
            collector.completeThread(Thread.currentThread())
        }

        t1.join()
        Thread.sleep(200) // wait for IO thread to dump the blocks to file
        latch.countDown()
        t2.join()

        collector.traceEnded()

        val loadedTrace = loadRecordedTrace(traceFile.absolutePath)
        val blocks = collectSavedBlocks(loadedTrace.context, traceFile)
        check(blocks.size == 2 && blocks.containsKey(1) && blocks.containsKey(2)) { "Expected 2 blocks for both threads, got thread ids: ${blocks.keys}" }

        val expectedClassDescriptorIds1 = setOf(0 /* SomeClass */)
        val expectedMethodDescriptorIds1 = setOf(0 /* methodName1 */, 2 /* sharedMethod */)
        val expectedMethodDescriptorIds2 = setOf(1 /* methodName2 */)
        val expectedVariableDescriptorIds1 = setOf(0 /* sharedVar */)
        val expectedStringIds1 = setOf(0 /* Example.java */, 1 /* com.example.SomeClass */, 2 /* methodName1 */, 3 /* someMethod */, 4 /* sharedMethod */)
        val expectedStringIds2 = setOf(5 /* methodName2 */)

        blocks[1]!!.run {
            checkClassDescriptors(expectedClassDescriptorIds1)
            checkMethodDescriptors(expectedMethodDescriptorIds1)
            checkVariableDescriptors(expectedVariableDescriptorIds1)
            checkStrings(expectedStringIds1)
        }
        blocks[2]!!.run {
            checkClassDescriptors(emptySet())
            checkMethodDescriptors(expectedMethodDescriptorIds2)
            checkVariableDescriptors(emptySet())
            checkStrings(expectedStringIds2)
        }
    }

    private fun BlockAnalysis.checkStrings(expected: Set<Int>) {
        check(strings == expected) {
            "Thread must have saved the following strings: $expected, got: $strings"
        }
    }

    private fun BlockAnalysis.checkClassDescriptors(expected: Set<Int>) {
        check(classDescriptors == expected) {
            "Thread must have saved the following class descriptors: $expected, got: $classDescriptors"
        }
    }

    private fun BlockAnalysis.checkMethodDescriptors(expected: Set<Int>) {
        check(methodDescriptors == expected) {
            "Thread must have saved the following method descriptors: $expected, got: $methodDescriptors"
        }
    }

    private fun BlockAnalysis.checkVariableDescriptors(expected: Set<Int>) {
        check(variableDescriptors == expected) {
            "Thread must have saved the following variable descriptors: $expected, got: $variableDescriptors"
        }
    }

    private fun createBasicMethodCallTracePoint(
        context: TraceContext,
        threadId: Int,
        className: String,
        methodName: String
    ): TRMethodCallTracePoint {
        val methodType = Types.MethodType(Types.OBJECT_TYPE)
        val md = context.createAndRegisterMethodDescriptor(className, methodName, methodType)
        val codeLocationId = context.newCodeLocation(
            StackTraceElement(md.className, md.methodName, "Example.java", 10)
        )
        val tracepoint = TRMethodCallTracePoint(
            context,
            threadId,
            codeLocationId,
            methodId = md.id,
            obj = null,
            parameters = listOf()
        )
        tracepoint.result = null
        return tracepoint
    }

    private fun createVariableWriteTracePoint(
        context: TraceContext,
        threadId: Int,
        variableName: String
    ): TRWriteLocalVariableTracePoint {
        val vd = context.createAndRegisterVariableDescriptor(variableName, Types.INT_TYPE)
        val codeLocationId = context.newCodeLocation(
            StackTraceElement("com.example.SomeClass", "someMethod", "Example.java", 20)
        )
        return TRWriteLocalVariableTracePoint(
            context,
            threadId,
            codeLocationId,
            localVariableId = vd.id,
            value = TRPrimitive(TR_OBJECT_P_INT, 0, 42)
        )
    }

    private fun collectSavedBlocks(loadedContext: TraceContext, traceFile: File): Map<Int, BlockAnalysis> {
        val dataFile = File(traceFile.absolutePath)
        val indexFile = File("${traceFile.absolutePath}.$INDEX_FILENAME_EXT")

        check(dataFile.exists() && indexFile.exists()) { "Trace files not found!" }
        val dataInput = DataInputStream(dataFile.inputStream().buffered())

        val magic = dataInput.readLong()
        val version = dataInput.readLong()

        Logger.info { "Magic: 0x${magic.toString(16)}" }
        Logger.info { "Version: $version" }

        // Track what descriptors we found in each block
        val blocks = mutableMapOf<Int, BlockAnalysis>()
        var currentBlock: BlockAnalysis? = null

        dataInput.use { dataInput ->
            val tracePointsStack = mutableListOf<TRTracePoint>()
            while (true) {
                when (val kind = dataInput.readKind()) {
                    ObjectKind.BLOCK_START -> {
                        val threadId = dataInput.readInt()
                        currentBlock = BlockAnalysis(threadId)
                        Logger.info { "lock started for thread $threadId" }
                    }

                    ObjectKind.BLOCK_END -> {
                        if (currentBlock != null) {
                            val prevBlock = blocks.putIfAbsent(currentBlock.threadId, currentBlock)
                            if (prevBlock != null) {
                                prevBlock.methodDescriptors.addAll(currentBlock.methodDescriptors)
                                prevBlock.classDescriptors.addAll(currentBlock.classDescriptors)
                                prevBlock.variableDescriptors.addAll(currentBlock.variableDescriptors)
                                prevBlock.strings.addAll(currentBlock.strings)
                                prevBlock.codeLocations.addAll(currentBlock.codeLocations)
                            }

                            Logger.info { "Block ended for thread ${currentBlock!!.threadId}" }
                            Logger.info { "  - ClassDescriptors: ${currentBlock!!.classDescriptors}" }
                            Logger.info { "  - MethodDescriptors: ${currentBlock!!.methodDescriptors}" }
                            Logger.info { "  - VariableDescriptors: ${currentBlock!!.variableDescriptors}" }
                            Logger.info { "  - Strings: ${currentBlock!!.strings}" }
                            Logger.info { "  - CodeLocations: ${currentBlock!!.codeLocations}\n" }
                            currentBlock = null
                        }
                    }

                    ObjectKind.CLASS_DESCRIPTOR -> {
                        val id = dataInput.readInt()
                        val name = dataInput.readUTF()
                        currentBlock?.classDescriptors?.add(id)
                        Logger.info { "  ClassDescriptor(id=$id, name=$name)" }
                    }

                    ObjectKind.METHOD_DESCRIPTOR -> {
                        val id = dataInput.readInt()
                        val classId = dataInput.readInt()
                        val sign = dataInput.readMethodSignature()
                        currentBlock?.methodDescriptors?.add(id)
                        Logger.info { "  MethodDescriptor(id=$id, classId=$classId, sign=$sign)" }
                    }

                    ObjectKind.VARIABLE_DESCRIPTOR -> {
                        val id = dataInput.readInt()
                        val name = dataInput.readUTF()
                        currentBlock?.variableDescriptors?.add(id)
                        Logger.info { "  VariableDescriptor(id=$id, name=$name)" }
                    }

                    ObjectKind.STRING -> {
                        val id = dataInput.readInt()
                        val string = dataInput.readUTF()
                        currentBlock?.strings?.add(id)
                        Logger.info { "  String(id=$id, string=\"$string\")" }
                    }

                    ObjectKind.CODE_LOCATION -> {
                        val id = loadCodeLocation(dataInput, loadedContext, false)
                        Logger.info { "  CodeLocation(id=$id)" }
                        currentBlock?.codeLocations?.add(id)
                    }

                    ObjectKind.TRACEPOINT -> {
                        val tr = loadTRTracePoint(loadedContext, dataInput)
                        Logger.info { "  Tracepoint: ${tr.toText(true)}" }

                        if (tr is TRContainerTracePoint) {
                            tracePointsStack.add(tr)
                        }
                    }

                    ObjectKind.THREAD_NAME -> {
                        val id = dataInput.readInt()
                        val name = dataInput.readUTF()
                        Logger.info { "  ThreadName(id=$id, name=\"$name\")" }
                    }

                    ObjectKind.EOF -> {
                        Logger.info { "End of file" }
                        break
                    }

                    ObjectKind.TRACEPOINT_FOOTER -> {
                        check(tracePointsStack.isNotEmpty()) { "Tracepoint footer without container trace point" }
                        val back = tracePointsStack.removeLast() as TRContainerTracePoint
                        back.loadFooter(dataInput)
                    }

                    else -> {
                        // For simplicity, skip other kinds
                        Logger.info { "  $kind (skipping detailed parsing)" }
                    }
                }
            }
        }

        return blocks
    }

    private data class BlockAnalysis(
        val threadId: Int,
        val classDescriptors: MutableSet<Int> = mutableSetOf(),
        val methodDescriptors: MutableSet<Int> = mutableSetOf(),
        val variableDescriptors: MutableSet<Int> = mutableSetOf(),
        val strings: MutableSet<Int> = mutableSetOf(),
        val codeLocations: MutableSet<Int> = mutableSetOf(),
    )
}

