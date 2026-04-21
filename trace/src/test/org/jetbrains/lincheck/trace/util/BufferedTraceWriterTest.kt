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

import org.jetbrains.lincheck.descriptors.AccessCodeLocation
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.LocalVariableAccessLocation
import org.jetbrains.lincheck.descriptors.MethodCallCodeLocation
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.trace.printing.printRecorderTrace
import org.jetbrains.lincheck.trace.serialization.*
import org.jetbrains.lincheck.util.Logger
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * Tests the buffered trace writer's behavior when multiple threads write trace data concurrently.
 *
 * This test class validates two critical scenarios in the trace collection system:
 *
 * 1. **Duplicate Descriptor Storage** (`testDataSavedTwiceWhenNotInFileYet`):
 *    When two threads write trace data concurrently and both threads create trace points that reference
 *    the same descriptors (class descriptors, method descriptors, variable descriptors, etc.) before either
 *    thread's data is flushed to the file, each thread should save its own copy of these shared descriptors
 *    in their respective data blocks. This ensures data integrity even when threads write concurrently.
 *
 *    The test uses a CountDownLatch and CyclicBarrier to coordinate threads so that:
 *    - Thread 1 creates its trace points and waits
 *    - Thread 2 creates its trace points referencing the same descriptors
 *    - Both threads flush their data blocks simultaneously
 *
 *    Verification: Both thread blocks contain complete copies of shared descriptors (e.g., "SomeClass",
 *    "sharedMethod", "sharedVar") along with their unique descriptors.
 *
 * 2. **Descriptor Deduplication** (`testDataDeduplicatedWhenSavedToFile`):
 *    When one thread completes writing and flushing its trace data to the file, and then a second thread
 *    starts writing trace data that references some of the same descriptors, the second thread should NOT
 *    duplicate descriptors that are already present in the file. Instead, it should only write new descriptors
 *    that weren't already saved by the first thread.
 *
 *    The test uses a CountDownLatch and Thread.sleep() to ensure:
 *    - Thread 1 completes and flushes all its data to the file
 *    - A brief delay allows the I/O thread to finish writing to disk
 *    - Thread 2 then starts and should reuse already-saved descriptors
 *
 *    Verification: Thread 1's block contains all shared descriptors, while Thread 2's block only contains
 *    its unique descriptors (e.g., only "methodName2"), without duplicating shared ones already in the file.
 *
 * Both tests verify the correctness by:
 * - Creating trace points with shared and unique descriptors across multiple threads
 * - Loading the saved trace file and parsing it block-by-block
 * - Analyzing which descriptor IDs were saved in each thread's data block
 * - Asserting that the expected descriptor IDs match the actual saved IDs
 */
class BufferedTraceWriterTest {

    @Test
    fun testDataSavedTwiceWhenNotInFileYet() {
        val context = TraceContext()
        val tr1 = createBasicMethodCallTracePoint(context, 0, "com.example.SomeClass", "methodName1")
        val tr2 = createBasicMethodCallTracePoint(context, 1, "com.example.SomeClass", "methodName2")
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

        latch.await() // we want to ensure correct assignment of ids for saved strings/descriptors/etc., so we wait
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
        printRecorderTrace(System.out, loadedTrace.context, loadedTrace.roots, verbose = true)
        val blocks = collectSavedBlocks(loadedTrace.context, traceFile)
        check(blocks.size == 2 && blocks.containsKey(tr1.threadId) && blocks.containsKey(tr2.threadId)) { "Expected 2 blocks for both threads, got thread ids: ${blocks.keys}" }

        val expectedClassDescriptorIds = setOf(0 /* SomeClass */)
        val expectedMethodDescriptorIds1 = setOf(0 /* methodName1 */, 2 /* sharedMethod */)
        val expectedMethodDescriptorIds2 = setOf(1 /* methodName2 */, 2 /* sharedMethod */)
        val expectedVariableDescriptorIds = setOf(0 /* sharedVar */)
        val expectedCodeLocationIds1 = setOf(0 /* methodName1 at Example.java:10 */, 2 /* sharedMethod at Example.java:10 */, 3 /* sharedVar in someMethod at Example.java:20 */)
        val expectedCodeLocationIds2 = setOf(1 /* methodName2 at Example.java:10 */, 2 /* sharedMethod at Example.java:10 */, 3 /* sharedVar in someMethod at Example.java:20 */)
        val expectedAccessPathIds = setOf(0 /* AccessPath to sharedVar */)
        val expectedStringIds1 = setOf(0 /* Example.java */, 1 /* com.example.SomeClass */, 2 /* methodName1 */, 3 /* someMethod */, 4 /* sharedMethod */)
        val expectedStringIds2 = setOf(0 /* Example.java */, 1 /* com.example.SomeClass */, 5 /* methodName2 */, 3 /* someMethod */, 4 /* sharedMethod */)

        blocks[tr1.threadId]!!.run {
            checkClassDescriptors(expectedClassDescriptorIds)
            checkMethodDescriptors(expectedMethodDescriptorIds1)
            checkVariableDescriptors(expectedVariableDescriptorIds)
            checkCodeLocations(expectedCodeLocationIds1)
            checkAccessPaths(expectedAccessPathIds)
            checkStrings(expectedStringIds1)
        }
        blocks[tr2.threadId]!!.run {
            checkClassDescriptors(expectedClassDescriptorIds)
            checkMethodDescriptors(expectedMethodDescriptorIds2)
            checkVariableDescriptors(expectedVariableDescriptorIds)
            checkCodeLocations(expectedCodeLocationIds2)
            checkAccessPaths(expectedAccessPathIds)
            checkStrings(expectedStringIds2)
        }
    }

    @Test
    fun testDataDeduplicatedWhenSavedToFile() {
        val context = TraceContext()
        val tr1 = createBasicMethodCallTracePoint(context, 0, "com.example.SomeClass", "methodName1")
        val tr2 = createBasicMethodCallTracePoint(context, 1, "com.example.SomeClass", "methodName2")
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
        printRecorderTrace(System.out, loadedTrace.context, loadedTrace.roots, verbose = true)
        val blocks = collectSavedBlocks(loadedTrace.context, traceFile)
        check(blocks.size == 2 && blocks.containsKey(tr1.threadId) && blocks.containsKey(tr2.threadId)) { "Expected 2 blocks for both threads, got thread ids: ${blocks.keys}" }

        val expectedClassDescriptorIds1 = setOf(0 /* SomeClass */)
        val expectedMethodDescriptorIds1 = setOf(0 /* methodName1 */, 2 /* sharedMethod */)
        val expectedMethodDescriptorIds2 = setOf(1 /* methodName2 */)
        val expectedVariableDescriptorIds1 = setOf(0 /* sharedVar */)
        val expectedCodeLocationIds1 = setOf(0 /* methodName1 at Example.java:10 */, 2 /* sharedMethod at Example.java:10 */, 3 /* sharedVar in someMethod at Example.java:20 */)
        val expectedCodeLocationIds2 = setOf(1 /* methodName2 at Example.java:10 */)
        val expectedAccessPathIds1 = setOf(0 /* AccessPath to sharedVar */)
        val expectedStringIds1 = setOf(0 /* Example.java */, 1 /* com.example.SomeClass */, 2 /* methodName1 */, 3 /* someMethod */, 4 /* sharedMethod */)
        val expectedStringIds2 = setOf(5 /* methodName2 */)

        blocks[tr1.threadId]!!.run {
            checkClassDescriptors(expectedClassDescriptorIds1)
            checkMethodDescriptors(expectedMethodDescriptorIds1)
            checkVariableDescriptors(expectedVariableDescriptorIds1)
            checkCodeLocations(expectedCodeLocationIds1)
            checkAccessPaths(expectedAccessPathIds1)
            checkStrings(expectedStringIds1)
        }
        blocks[tr2.threadId]!!.run {
            checkClassDescriptors(emptySet())
            checkMethodDescriptors(expectedMethodDescriptorIds2)
            checkVariableDescriptors(emptySet())
            checkCodeLocations(expectedCodeLocationIds2)
            checkAccessPaths(emptySet())
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

    private fun BlockAnalysis.checkCodeLocations(expected: Set<Int>) {
        check(codeLocations == expected) {
            "Thread must have saved the following code locations: $expected, got: $codeLocations"
        }
    }

    private fun BlockAnalysis.checkAccessPaths(expected: Set<Int>) {
        check(accessPaths == expected) {
            "Thread must have saved the following access paths: $expected, got: $accessPaths"
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
        val codeLocationId = context.codeLocationsPool.register(
            MethodCallCodeLocation(StackTraceElement(md.className, md.methodName, "Example.java", 10), accessPath = null, argumentNames = null)
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

        // Create an access path for the variable
        val accessPath = AccessPath(listOf(LocalVariableAccessLocation(vd)))

        val codeLocationId = context.codeLocationsPool.register(
            AccessCodeLocation(StackTraceElement("com.example.SomeClass", "someMethod", "Example.java", 20), accessPath = accessPath)
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
        val indexFile = File("${traceFile.absolutePath}.${INDEX_FILENAME_EXT}")

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
                                prevBlock.accessPaths.addAll(currentBlock.accessPaths)
                            }

                            Logger.info { "Block ended for thread ${currentBlock!!.threadId}" }
                            Logger.info { "  - ClassDescriptors: ${currentBlock!!.classDescriptors}" }
                            Logger.info { "  - MethodDescriptors: ${currentBlock!!.methodDescriptors}" }
                            Logger.info { "  - VariableDescriptors: ${currentBlock!!.variableDescriptors}" }
                            Logger.info { "  - Strings: ${currentBlock!!.strings}" }
                            Logger.info { "  - CodeLocations: ${currentBlock!!.codeLocations}" }
                            Logger.info { "  - AccessPaths: ${currentBlock!!.accessPaths}\n" }
                            currentBlock = null
                        }
                    }

                    ObjectKind.CLASS_DESCRIPTOR -> {
                        val id = loadClassDescriptor(dataInput, loadedContext, restore = false)
                        currentBlock?.classDescriptors?.add(id)
                        Logger.info { "  ClassDescriptor(id=$id, name=${loadedContext.classPool[id]})" }
                    }

                    ObjectKind.METHOD_DESCRIPTOR -> {
                        val id = loadMethodDescriptor(dataInput, loadedContext, restore = false)
                        currentBlock?.methodDescriptors?.add(id)
                        val md = loadedContext.methodPool[id]
                        Logger.info { "  MethodDescriptor(id=$id, classId=${md.classId}, sign=${md.methodSignature})" }
                    }

                    ObjectKind.VARIABLE_DESCRIPTOR -> {
                        val id = loadVariableDescriptor(dataInput, loadedContext, restore = false)
                        currentBlock?.variableDescriptors?.add(id)
                        Logger.info { "  VariableDescriptor(id=$id, name=${loadedContext.variablePool[id]})" }
                    }

                    ObjectKind.STRING -> {
                        val id = loadString(dataInput, loadedContext, restore = false)
                        currentBlock?.strings?.add(id)
                        Logger.info { "  String(id=$id, string=\"${loadedContext.stringPool[id]}\")" }
                    }

                    ObjectKind.CODE_LOCATION -> {
                        val id = loadCodeLocation(dataInput, loadedContext, restore = false)
                        currentBlock?.codeLocations?.add(id)
                        Logger.info { "  CodeLocation(id=$id): ${loadedContext.codeLocationsPool[id].stackTraceElement}" }
                    }

                    ObjectKind.ACCESS_PATH -> {
                        val id = loadAccessPath(dataInput, loadedContext, restore = true)
                        currentBlock?.accessPaths?.add(id)
                        Logger.info { "  AccessPath(id=$id): ${loadedContext.accessPathPool[id]}" }
                    }

                    ObjectKind.TRACEPOINT -> {
                        val tr = loadTRTracePoint(loadedContext, dataInput)
                        Logger.info { "  Tracepoint: ${tr.toText(verbose = true)}" }

                        if (tr is TRContainerTracePoint) {
                            tracePointsStack.add(tr)
                        }
                    }

                    ObjectKind.THREAD_NAME -> {
                        val id = loadThreadName(dataInput, loadedContext, restore = false)
                        Logger.info { "  ThreadName(id=$id, name=\"${loadedContext.getThreadName(id)}\")" }
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
        val accessPaths: MutableSet<Int> = mutableSetOf()
    )
}

