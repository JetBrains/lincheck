package org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.ASYNCHRONOUS
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.junit.Test
import java.util.*

class KVStorageServer(private val env: NodeEnvironment<Command>) : Node<Command> {
    private val storage = mutableMapOf<Int, Int>()
    private val commandResults = Array<MutableMap<Int, Command>>(env.nodes) {
        mutableMapOf()
    }

    override fun onMessage(message: Command, sender: Int) {
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
                is AddCommand -> AddResult(
                    storage.put(
                        message.key,
                        storage.getOrDefault(message.key, 0) + message.value
                    ), id
                )
                else -> throw RuntimeException("Unexpected command")
            }
        } catch (e: Throwable) {
            ErrorResult(e, id)
        }
        commandResults[sender][id] = result
        env.send(result, receiver = sender)
    }
}

class KVStorageClient(private val nodeEnvironment: NodeEnvironment<Command>) : Node<Command> {
    private var commandId = 0
    private val commandResults = mutableMapOf<Int, Command>()
    private val serverAddr = nodeEnvironment.getAddresses<KVStorageServer>()[0]
    private val signal = Signal()
    private val queue = LinkedList<Command>()

    private suspend fun sendOnce(command: Command): Command {
        while (true) {
            nodeEnvironment.send(command, serverAddr)
            nodeEnvironment.recordInternalEvent("Before await")
            nodeEnvironment.withTimeout(6) {
                nodeEnvironment.recordInternalEvent("Before suspend")
                signal.await()
            }
            nodeEnvironment.recordInternalEvent("After await")
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

    @Operation(cancellableOnSuspension = false)
    suspend fun contains(key: Int): Boolean {
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: Int, value: Int): Int? {
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun remove(key: Int): Int? {
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: Int): Int? {
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun add(key: Int, value: Int): Int? {
        val response = sendOnce(AddCommand(key, value, commandId++)) as AddResult
        return response.res
    }

    override fun onMessage(message: Command, sender: Int) {
        queue.add(message)
        nodeEnvironment.recordInternalEvent("Before resume")
        signal.signal()
    }
}

class KVStorageServerTestClass {
    private inline fun <reified S : Node<Command>, reified C : Node<Command>> commonOptions() =
        DistributedOptions<Command>()
            .sequentialSpecification(SingleNode::class.java)
            .invocationsPerIteration(30_000)
            .iterations(10)
            .minimizeFailedScenario(false)
            .actorsPerThread(3)
            .addNodes<S>(nodes = 1)
            .addNodes<C>(nodes = 3, minNodes = 1)

    @Test
    fun `correct algorithm`() = commonOptions<KVStorageServer, KVStorageClient>()
        .messageDuplications(true)
        .messageLoss(false)
        .messageOrder(ASYNCHRONOUS)
        .check(KVStorageClient::class.java)

    @Test
    fun `incorrect algorithm`() {
        val failure = commonOptions<KVStorageServerIncorrect, KVStorageClientIncorrect>()
            .messageDuplications(true)
            .messageLoss(false)
            .messageOrder(ASYNCHRONOUS)
            .checkImpl(KVStorageClientIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun `incorrect algorithm without FIFO`() {
        val failure = commonOptions<KVStorageServerIncorrect, KVStorageClientIncorrect>()
            .messageOrder(ASYNCHRONOUS)
            .checkImpl(KVStorageClientIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun `incorrect algorithm with message loss`() {
        val failure = commonOptions<KVStorageServerIncorrect, KVStorageClientIncorrect>()
            .messageLoss(true)
            .checkImpl(KVStorageClientIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun `incorrect algorithm with message duplications`() {
        val failure = commonOptions<KVStorageServerIncorrect, KVStorageClientIncorrect>()
            .messageDuplications(true)
            .checkImpl(KVStorageClientIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}
