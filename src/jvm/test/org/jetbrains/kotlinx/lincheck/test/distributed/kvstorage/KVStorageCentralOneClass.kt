package org.jetbrains.kotlinx.lincheck.test.distributed.kvstorage


import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.HashMap
import kotlin.concurrent.withLock


class KVStorageCentralSimple(private val environment: Environment<Command>) : Node<Command> {
    private val lock = ReentrantLock()
    private var commandId = 0
    private val commandResults = Array(environment.numberOfNodes) { HashMap<Int, Command>()}
    private val storage = Collections.synchronizedMap(
            HashMap<Int, Int>())

    private val queue = LinkedBlockingQueue<Command>()

    @StateRepresentation
    fun stateRepresentation(): String {
        return commandResults.toString()
    }

    override fun onMessage(message: Command, sender : Int) {
        if (environment.nodeId != 0) {
            queue.add(message)
            return
        }
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            environment.send(
                    commandResults[sender][id]!!,
                    sender)
            return
        }
        val result : Command = lock.withLock {
            try {
                when (message) {
                    is ContainsCommand -> ContainsResult(storage.containsKey(message.key), id)
                    is GetCommand -> GetResult(storage[message.key], id)
                    is PutCommand -> PutResult(storage.put(message.key, message.value), id)
                    is RemoveCommand -> RemoveResult(storage.remove(message.key), id)
                    else -> throw RuntimeException("Unexpected command")
                }
            } catch(e : Throwable) {
                ErrorResult(e, id)
            }
        }
        commandResults[sender][id] = result
        environment.send(result, receiver = sender)
    }

    fun sendOnce(command : Command): Command {
        while (true) {
            environment.send(command, 0)
            val response = queue.poll(10, TimeUnit.MILLISECONDS)
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
    fun contains(key: Int): Boolean {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage.containsKey(key)
            }
        }
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation
    fun put(key: Int, value: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage.put(key, value)
            }
        }
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation
    fun remove(key: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage.remove(key)
            }
        }
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }


    @Operation
    fun get(key: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage[key]
            }
        }
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }
}

class KVStorageIncorrect(private val environment: Environment<Command>) : Node<Command> {
    private val lock = ReentrantLock()
    private var commandId = 0
    private val commandResults = Array(environment.numberOfNodes) { HashMap<Int, Command>()}
    private val storage = Collections.synchronizedMap(
            HashMap<Int, Int>())

    private val queue = LinkedBlockingQueue<Command>()

    @StateRepresentation
    fun stateRepresentation(): String {
        return commandResults.toString()
    }

    override fun onMessage(message: Command, sender : Int) {
        if (environment.nodeId != 0) {
            queue.add(message)
            return
        }
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            environment.send(
                    commandResults[sender][id]!!,
                    sender)
            return
        }
        val result : Command = lock.withLock {
            try {
                when (message) {
                    is ContainsCommand -> ContainsResult(storage.containsKey(message.key), id)
                    is GetCommand -> GetResult(storage[message.key], id)
                    is PutCommand -> PutResult(storage.put(message.key, message.value), id)
                    is RemoveCommand -> RemoveResult(storage.remove(message.key), id)
                    else -> throw RuntimeException("Unexpected command")
                }
            } catch(e : Throwable) {
                ErrorResult(e, id)
            }
        }
        environment.send(result, receiver = sender)
    }

    fun sendOnce(command : Command): Command {
        while (true) {
            environment.send(command, 0)
            val response = queue.poll(10, TimeUnit.MILLISECONDS)
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
    fun contains(key: Int): Boolean {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage.containsKey(key)
            }
        }
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation
    fun put(key: Int, value: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage.put(key, value)
            }
        }
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation
    fun remove(key: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage.remove(key)
            }
        }
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }


    @Operation
    fun get(key: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
                return storage[key]
            }
        }
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }
}

class SingleNode {
    private val storage = HashMap<Int, Int>()

    @Operation
    fun contains(key: Int): Boolean {
        return storage.contains(key)
    }

    @Operation
    fun put(key: Int, value: Int): Int? {
        return storage.put(key, value)
    }

    @Operation
    fun get(key: Int): Int? {
        return storage[key]
    }

    @Operation
    fun remove(key: Int): Int? {
        return storage.remove(key)
    }
}

class KVStorageCentralTestClass  {
    @Test
    fun testSimple() {
        LinChecker.check(KVStorageCentralSimple::class
                .java, DistributedOptions<Command>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }

    @Test
    fun testFull() {
        LinChecker.check(KVStorageCentralSimple::class
                .java, DistributedOptions<Command>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).duplicationRate(2).networkReliability(0.7)
                .invocationsPerIteration(100).iterations(100))
    }

    @Test
    fun testNetworkReliability() {
        LinChecker.check(KVStorageCentralSimple::class
                .java, DistributedOptions<Command>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).messageOrder(MessageOrder.ASYNCHRONOUS).networkReliability(0.7)
                .invocationsPerIteration(1).iterations(1))
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrect() {
        LinChecker.check(KVStorageIncorrect::class
                .java, DistributedOptions<Command>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).duplicationRate(2).networkReliability(0.7)
                .invocationsPerIteration(100).iterations(1000))
    }
}