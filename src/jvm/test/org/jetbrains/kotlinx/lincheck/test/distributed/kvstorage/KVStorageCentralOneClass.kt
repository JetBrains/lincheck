package org.jetbrains.kotlinx.lincheck.test.distributed.kvstorage


import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import org.junit.Test
import java.util.*


class KVStorageCentralSimple(private val environment: Environment<Command, Unit>) : Node<Command> {
    private val semaphore = Signal()
    private var commandId = 0
    private val commandResults = Array(environment.numberOfNodes) { HashMap<Int, Command>() }
    private val storage = HashMap<Int, Int>()

    private val queue = LinkedList<Command>()

    @StateRepresentation
    fun stateRepresentation(): String {
        return commandResults.toString()
    }

    override suspend fun onMessage(message: Command, sender: Int) {
        if (environment.nodeId != 0) {
            queue.add(message)
            semaphore.signal()
            return
        }
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            environment.send(
                commandResults[sender][id]!!,
                sender
            )
            return
        }
        val result: Command = try {
            when (message) {
                is ContainsCommand -> ContainsResult(storage.containsKey(message.key), id)
                is GetCommand -> GetResult(storage[message.key], id)
                is PutCommand -> PutResult(storage.put(message.key, message.value), id)
                is RemoveCommand -> RemoveResult(storage.remove(message.key), id)
                else -> throw RuntimeException("Unexpected command")
            }
        } catch (e: Throwable) {
            ErrorResult(e, id)
        }
        commandResults[sender][id] = result
        environment.send(result, receiver = sender)
    }

    private suspend fun sendOnce(command: Command): Command {
        while (true) {
            environment.send(command, 0)
            environment.withTimeout(1) {
                semaphore.await()
            }
            val response = queue.poll()
            if (response != null) {
                commandResults[0][response.id] = response
            }
            if (commandResults[0].containsKey(command.id)) {
                val res = commandResults[0][command.id]!!
                if (res is ErrorResult) {
                    throw res.error
                }
                return res
            }
        }
    }

    @Operation
    suspend fun contains(key: Int): Boolean {
        if (environment.nodeId == 0) {
            return storage.containsKey(key)
        }
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation
    suspend fun put(key: Int, value: Int): Int? {
        if (environment.nodeId == 0) {
            return storage.put(key, value)
        }
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation
    suspend fun remove(key: Int): Int? {
        if (environment.nodeId == 0) {
            return storage.remove(key)
        }
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }


    @Operation
    suspend fun get(key: Int): Int? {
        if (environment.nodeId == 0) {
            return storage[key]
        }
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }
}

class SingleNode {
    private val storage = HashMap<Int, Int>()

    @Operation
    suspend fun contains(key: Int): Boolean {
        return storage.contains(key)
    }

    @Operation
    suspend fun put(key: Int, value: Int): Int? {
        return storage.put(key, value)
    }

    @Operation
    suspend fun get(key: Int): Int? {
        return storage[key]
    }

    @Operation
    suspend fun remove(key: Int): Int? {
        return storage.remove(key)
    }
}

class KVStorageCentralTestClass {
    @Test
    fun testSimple() {
        LinChecker.check(
            KVStorageCentralSimple::class
                .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).threads
                (2).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(500)
        )
    }

    @Test
    fun testFull() {
        LinChecker.check(
            KVStorageCentralSimple::class
                .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).threads
                (2).messageDuplications(true).messageOrder(MessageOrder.ASYNCHRONOUS).networkReliable(false)
                .invocationsPerIteration(300).iterations(100)
        )
    }

    @Test
    fun testNetworkReliability() {
        LinChecker.check(
            KVStorageCentralSimple::class
                .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).threads
                (2).messageOrder(MessageOrder.FIFO).networkReliable(false)
                .invocationsPerIteration(300).iterations(100)
        )
    }

    @Test
    fun testNetworkReliabilitySync() {
        LinChecker.check(
            KVStorageCentralSimple::class
                .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).threads
                (2).messageOrder(MessageOrder.SYNCHRONOUS).networkReliable(false)
                .invocationsPerIteration(300).iterations(100)
        )
    }

    /* @Test
     fun testIncorrectShouldPass() {
         LinChecker.check(KVStorageIncorrect::class
             .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
             (false).sequentialSpecification(SingleNode::class.java).messageOrder(MessageOrder.SYNCHRONOUS).threads
             (2).invocationsPerIteration(100).iterations(1000))
         println("Get $cntGet, null $cntNullGet")
     }

     @Test(expected = LincheckAssertionError::class)
     fun testIncorrect() {
         LinChecker.check(KVStorageIncorrect::class
                 .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
         (false).sequentialSpecification(SingleNode::class.java).threads
         (2).messageDuplications(true).networkReliable(false)
                 .invocationsPerIteration(100).iterations(1000))
     }

     @Test(expected = LincheckAssertionError::class)
     fun testIncorrectAsynchronous() {
         LinChecker.check(KVStorageIncorrect::class
                 .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
         (false).sequentialSpecification(SingleNode::class.java).threads
         (2).messageOrder(MessageOrder.ASYNCHRONOUS)
                 .invocationsPerIteration(100).iterations(1000))
     }

     @Test(expected = LincheckAssertionError::class)
     fun testIncorrectOnlyDropMessages() {
         LinChecker.check(KVStorageIncorrect::class
                 .java, DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck
         (false).sequentialSpecification(SingleNode::class.java).threads
         (2).networkReliable(false)
                 .invocationsPerIteration(100).iterations(1000))
     }*/
}