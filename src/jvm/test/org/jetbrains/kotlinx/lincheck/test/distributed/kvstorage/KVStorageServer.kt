package org.jetbrains.kotlinx.lincheck.test.distributed.kvstorage

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test
import java.lang.RuntimeException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class KVStorageServer(private val env: Environment<Command>) : Node<Command> {
    private val storage = HashMap<Int, Int>()
    private val commandResults = Array<HashMap<Int, Command>>(env.numberOfNodes) {
        HashMap()
    }
    private val lock = ReentrantLock()

    override fun onMessage(message: Command, sender: Int) {
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            env.send(commandResults[sender][id]!!, sender)
            return
        }
        val result: Command = lock.withLock {
            try {
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
        }
        commandResults[sender][id] = result
        env.send(result, receiver = sender)
    }
}

class KVStorageClient(private val environment: Environment<Command>) : BlockingReceiveNodeImp<Command>() {
    private var commandId = 0
    private val commandResults = HashMap<Int, Command>()
    private val serverAddr = environment.getAddress(KVStorageServer::class.java, 0)

    fun sendOnce(command : Command): Command {
        while (true) {
            environment.send(command, serverAddr)
            val response = receive(10, TimeUnit.MILLISECONDS)
            if (response != null) {
                commandResults[response.first.id] = response.first
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
    fun contains(key: Int): Boolean {
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation
    fun put(key: Int, value: Int): Int? {
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation
    fun remove(key: Int): Int? {
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }


    @Operation
    fun get(key: Int): Int? {
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }
}

class KVStorageServerTestClass {
    @Test(expected = IllegalArgumentException::class)
    fun testSimple() {
        LinChecker.check(KVStorageServer::class.java,
                DistributedOptions<Command, Unit>().requireStateEquivalenceImplCheck(false)
                        .sequentialSpecification(SingleNode::class.java)
                        .nodeType(KVStorageServer::class.java, 1)
                        .nodeType(KVStorageClient::class.java, 1)
                        .messageOrder(MessageOrder.ASYNCHRONOUS)
                        .invocationsPerIteration(100).iterations(1000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFull() {
        LinChecker.check(KVStorageServer::class.java,
                DistributedOptions<Command, Unit>()
                        .requireStateEquivalenceImplCheck(false)
                        .sequentialSpecification(SingleNode::class.java)
                        .nodeType(KVStorageServer::class.java, 1)
                        .nodeType(KVStorageClient::class.java, 5)
                        .messageDuplications(true)
                        .networkReliable(false)
                        .invocationsPerIteration(100)
                        .iterations(100))
    }
}
