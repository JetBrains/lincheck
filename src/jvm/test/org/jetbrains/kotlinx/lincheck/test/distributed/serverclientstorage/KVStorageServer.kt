package org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class KVStorageServer(private val env: Environment<Command, Unit>) : Node<Command> {
    private val storage = HashMap<Int, Int>()
    private val commandResults = Array<HashMap<Int, Command>>(env.numberOfNodes) {
        HashMap()
    }

    override suspend fun onMessage(message: Command, sender: Int) {
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            env.send(commandResults[sender][id]!!, sender)
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
        env.send(result, receiver = sender)
    }
}

class KVStorageClient(private val environment: Environment<Command, Unit>) : Node<Command> {
    private var commandId = 0
    private val commandResults = HashMap<Int, Command>()
    private val serverAddr = environment.getAddressesForClass(KVStorageServer::class.java)!![0]
    private val signal = Signal()
    private val queue = LinkedList<Command>()


    private suspend fun sendOnce(command: Command): Command {
        while (true) {
            environment.send(command, serverAddr)
            environment.withTimeout(1) {
                signal.await()
            }
            val response = queue.poll()
            if (response != null) {
                commandResults[response.id] = response
            }
            if (commandResults.containsKey(command.id)) {
                val res = commandResults[command.id]!!
                if (res is ErrorResult) {
                    throw res.error
                }
                return res
            }
        }
    }

    @Operation
    suspend fun contains(key: Int): Boolean {
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation
    suspend fun put(key: Int, value: Int): Int? {
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation
    suspend fun remove(key: Int): Int? {
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }


    @Operation
    suspend fun get(key: Int): Int? {
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }

    override suspend fun onMessage(message: Command, sender: Int) {
        queue.add(message)
        signal.signal()
    }
}

class KVStorageServerTestClass {
    @Test
    fun testSimple() {
        LinChecker.check(
            KVStorageClient::class.java,
            DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .nodeType(KVStorageServer::class.java, 1)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000)
        )
    }

    @Test
    fun test() {
        LinChecker.check(
            KVStorageClient::class.java,
            DistributedOptions<Command, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .nodeType(KVStorageServer::class.java, 1)
                .messageDuplications(true)
                .networkReliable(false)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(300)
                .iterations(100)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrect() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            DistributedOptions<Command, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .nodeType(KVStorageServerIncorrect::class.java, 1)
                .messageDuplications(true)
                .networkReliable(false)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(300)
                .iterations(100)
        )
    }

    @Test
    fun testIncorrectShouldPass() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            DistributedOptions<Command, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .nodeType(KVStorageServerIncorrect::class.java, 1)
                .invocationsPerIteration(300)
                .iterations(100)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrectAsync() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            DistributedOptions<Command, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .nodeType(KVStorageServerIncorrect::class.java, 1)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(300)
                .iterations(100)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrectNetworkUnreliable() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            DistributedOptions<Command, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .nodeType(KVStorageServerIncorrect::class.java, 1)
                .networkReliable(false)
                .invocationsPerIteration(300)
                .iterations(100)
        )
    }
}
