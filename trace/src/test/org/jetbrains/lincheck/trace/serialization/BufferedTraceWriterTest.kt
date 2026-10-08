package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.AccessCodeLocation
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.LocalVariableAccessLocation
import org.jetbrains.lincheck.descriptors.MethodCallCodeLocation
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.ContainerFooterTracePoint
import org.jetbrains.lincheck.trace.ContainerHeaderTracePoint
import org.jetbrains.lincheck.trace.MethodCallTracePoint
import org.jetbrains.lincheck.trace.TraceNull
import org.jetbrains.lincheck.trace.TraceScalar
import org.jetbrains.lincheck.trace.TracePoint
import org.jetbrains.lincheck.trace.WriteLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.attachFooterTracePoint
import org.jetbrains.lincheck.trace.createAndRegisterMethodDescriptor
import org.jetbrains.lincheck.trace.createAndRegisterVariableDescriptor
import org.jetbrains.lincheck.trace.printing.printTraceTree
import org.jetbrains.lincheck.trace.tree.readTraceTrees
import org.jetbrains.lincheck.trace.tree.structure
import org.jetbrains.lincheck.util.Logger
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * How the buffered trace writer places shared descriptors when two threads write concurrently.
 *
 * One invariant per test:
 * - `testDataSavedTwiceWhenNotInFileYet` — while neither thread's data block has reached the file,
 *   each thread writes its own copy of every descriptor it references, shared ones included,
 *   so a block stays self-contained and readable on its own.
 *   The `CountDownLatch` and `CyclicBarrier` hold both blocks open until both have been appended.
 * - `testDataDeduplicatedWhenSavedToFile` — once a block is on disk,
 *   a thread that starts afterwards writes only the descriptors that block does not already carry.
 *   The `CountDownLatch` plus a short sleep let the I/O thread reach the disk before the second thread starts.
 * - `testBlockRolloverInsideClosingTracePoint` — a record that does not fit into the current block
 *   is rolled back and rewritten into the next one, closing trace points included.
 *
 * The first two tests read the file back block-by-block and compare the descriptor ids each block saved.
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

            collector.tracePointCreated(parent = tr1, varAssignment1)

            collector.tracePointCreated(parent = tr1, sharedCall1)
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall1, sharedCall1.completeTracePoint())

            collector.completeContainerTracePoint(Thread.currentThread(), tr1, tr1.completeTracePoint())

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
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall2, sharedCall2.completeTracePoint())

            collector.completeContainerTracePoint(Thread.currentThread(), tr2, tr2.completeTracePoint())

            barrier.await()

            collector.completeThread(Thread.currentThread())
        }

        // both threads have flushed their buffered writers only when
        // both of them have appended their data blocks for saving
        t1.join()
        t2.join()

        collector.traceEnded()

        val loadedContext = LazyTraceReader(traceFile.absolutePath).use { reader ->
            printTraceTree(System.out, reader, verbose = true)
            reader.context
        }
        val blocks = collectSavedBlocks(loadedContext, traceFile)
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

            collector.tracePointCreated(parent = tr1, varAssignment1)

            collector.tracePointCreated(parent = tr1, sharedCall1)
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall1, sharedCall1.completeTracePoint())

            collector.completeContainerTracePoint(Thread.currentThread(), tr1, tr1.completeTracePoint())
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
            collector.completeContainerTracePoint(Thread.currentThread(), sharedCall2, sharedCall2.completeTracePoint())

            collector.completeContainerTracePoint(Thread.currentThread(), tr2, tr2.completeTracePoint())
            collector.completeThread(Thread.currentThread())
        }

        t1.join()
        Thread.sleep(200) // wait for IO thread to dump the blocks to file
        latch.countDown()
        t2.join()

        collector.traceEnded()

        val loadedContext = LazyTraceReader(traceFile.absolutePath).use { reader ->
            printTraceTree(System.out, reader, verbose = true)
            reader.context
        }
        val blocks = collectSavedBlocks(loadedContext, traceFile)
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

    /**
     * A record that overflows the per-thread buffer must be rolled back and rewritten into the next block,
     * whether it opens, fills, or closes a container.
     *
     * The buffer size at which the overflow lands inside a *closing* record depends on the exact record
     * layout, so the whole range of sizes around the trace size is swept;
     * every resulting trace must read back with the structure it was written with.
     */
    @Test
    fun testBlockRolloverInsideClosingTracePoint() {
        for (bufferSize in MIN_SWEPT_BUFFER_SIZE..MAX_SWEPT_BUFFER_SIZE) {
            val context = TraceContext()
            val root = createBasicMethodCallTracePoint(context, 0, "com.example.SomeClass", "root")
            val call = createBasicMethodCallTracePoint(context, 0, "com.example.SomeClass", "call")
            val varAssignment = createVariableWriteTracePoint(context, 0, "someVar")

            val traceFile = File.createTempFile("trace_test", ".trace").apply { deleteOnExit() }
            File("${traceFile.absolutePath}.$INDEX_FILENAME_EXT").deleteOnExit()
            val collector = FileStreamingTraceCollecting(traceFile.absolutePath, context, bufferSize)

            val thread = Thread.currentThread()
            collector.registerCurrentThread(root.threadId)
            collector.tracePointCreated(parent = null, root)
            collector.tracePointCreated(parent = root, call)
            collector.completeContainerTracePoint(thread, call, call.completeTracePoint())
            collector.tracePointCreated(parent = root, varAssignment)
            collector.completeContainerTracePoint(thread, root, root.completeTracePoint())
            collector.completeThread(thread)
            collector.traceEnded()

            val structure = LazyTraceReader(traceFile.absolutePath).use { it.readTraceTrees().single().structure() }
            check(structure == "root(call,writeVar(someVar))") {
                "Trace written with a $bufferSize-byte buffer read back as $structure"
            }
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
    ): MethodCallTracePoint {
        val methodType = Types.MethodType(Types.OBJECT_TYPE)
        val md = context.createAndRegisterMethodDescriptor(className, methodName, methodType)
        val codeLocationId = context.codeLocationsPool.register(
            MethodCallCodeLocation(
                StackTraceElement(md.className, md.methodName, "Example.java", 10),
                accessPath = null,
                argumentNames = null
            )
        )
        val tracepoint = MethodCallTracePoint(
            context,
            threadId,
            codeLocationId,
            methodId = md.id,
            obj = TraceNull,
            parameters = listOf()
        )
        return tracepoint
    }

    private fun createVariableWriteTracePoint(
        context: TraceContext,
        threadId: Int,
        variableName: String
    ): WriteLocalVariableTracePoint {
        val vd = context.createAndRegisterVariableDescriptor(variableName, Types.INT_TYPE)

        // Create an access path for the variable
        val accessPath = AccessPath(listOf(LocalVariableAccessLocation(vd)))

        val codeLocationId = context.codeLocationsPool.register(
            AccessCodeLocation(
                StackTraceElement("com.example.SomeClass", "someMethod", "Example.java", 20),
                accessPath = accessPath
            )
        )
        return WriteLocalVariableTracePoint(
            context,
            threadId,
            codeLocationId,
            localVariableId = vd.id,
            value = TraceScalar(42)
        )
    }

    private fun collectSavedBlocks(loadedContext: TraceContext, traceFile: File): Map<Int, BlockAnalysis> {
        val dataFile = File(traceFile.absolutePath)
        val indexFile = File("${traceFile.absolutePath}.${INDEX_FILENAME_EXT}")

        check(dataFile.exists() && indexFile.exists()) { "Trace files not found!" }
        val dataInput = DataInputStream(dataFile.inputStream().buffered())

        val runtime = dataInput.checkTraceHeader()

        Logger.info { "Runtime: $runtime" }

        val blocks = mutableMapOf<Int, BlockAnalysis>()
        var currentBlock: BlockAnalysis? = null

        dataInput.use { dataInput ->
            val tracePointsStack = mutableListOf<TracePoint>()
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
                        val tr = dataInput.readTracePointData(loadedContext)
                        Logger.info { "  Tracepoint: ${tr.toText(verbose = true)}" }

                        if (tr is ContainerFooterTracePoint) {
                            check(tracePointsStack.isNotEmpty()) { "Closing tracepoint without container trace point" }
                            (tracePointsStack.removeLast() as ContainerHeaderTracePoint).attachFooterTracePoint(tr)
                        } else if (tr is ContainerHeaderTracePoint) {
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

                    else -> {
                        // For simplicity, skip other kinds
                        Logger.info { "  $kind (skipping detailed parsing)" }
                    }
                }
            }
        }

        return blocks
    }

    private companion object {
        /**
         * The swept per-thread buffer sizes.
         *
         * The lower bound must still fit one trace point with all its prerequisite descriptors:
         * a retry writes into an empty buffer and is not attempted twice.
         */
        const val MIN_SWEPT_BUFFER_SIZE = 256
        const val MAX_SWEPT_BUFFER_SIZE = 640
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