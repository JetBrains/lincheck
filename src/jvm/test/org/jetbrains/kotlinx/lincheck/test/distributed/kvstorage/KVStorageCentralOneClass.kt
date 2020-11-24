package org.jetbrains.kotlinx.lincheck.test.distributed.kvstorage


import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.junit.Test
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.HashMap
import kotlin.concurrent.withLock


class KVStorageCentralSimple(private val environment: Environment) : Node {
    private val lock = ReentrantLock()
    private var commandId = 0
    private val commandResults = HashMap<String, String>()
    private val storage = Collections.synchronizedMap(
            HashMap<Int, Int>())

    private val queue = LinkedBlockingQueue<Message>()

    @StateRepresentation
    fun stateRepresentation() : String {
        return commandResults.toString()
    }

    override fun onMessage(message: Message) {
        if (environment.processId != 0) {
            queue.add(message)
            return
        }
        val id = message.headers["id"]!!
        if (commandResults.containsKey(id)) {
            environment.send(
                    Message(body = commandResults[id]!!,
                            headers = hashMapOf("id" to id, "state" to storage.toString()),
                            sender = environment.processId,
                            receiver = message.sender)
            )
            return
        }
        val tokens = message.body.split(' ')
        val result = lock.withLock {
                when (tokens[0]) {
                "contains" -> storage.containsKey(tokens[1].toInt()).toString()
                "get" -> storage[tokens[1].toInt()]?.toString()
                "put" -> {
                    val present = storage.containsKey(tokens[1].toInt())
                    storage[tokens[1].toInt()] = tokens[2].toInt()
                    present.toString()
                }
                "remove" -> {
                    val present = storage.containsKey(tokens[1].toInt())
                    if (present) {
                        storage.remove(tokens[1].toInt())
                    }
                    present.toString()
                }
                else -> "error"
            }
        }
        commandResults[id] = result.toString()
        environment.send(Message(body = result ?: "null", headers = hashMapOf("id" to id, "state" to storage.toString()),
                sender = environment.processId,
                receiver = message.sender))
    }

    fun sendOnce(body: String): String {
        val id = commandId++.toString()
        val message = Message(body, headers = hashMapOf("id" to id),
                sender = environment.processId, receiver = 0)
        while (true) {
            environment.send(message)
            val response = queue.poll(10, TimeUnit.MILLISECONDS)
            if (response != null) {
                commandResults[response.headers["id"]!!] = response.body
            }
            if (commandResults.containsKey(id)) {
                return commandResults[id]!!
            }
        }
    }

    @Operation
    fun contains(key: Int): Boolean {
        if (environment.processId == 0) {
            lock.withLock {
                return storage.containsKey(key)
            }
        }
        val res = sendOnce("contains $key")
        return res.toBoolean()
    }

    @Operation
    fun put(key: Int, value : Int): Boolean {
        if (environment.processId == 0) {
            lock.withLock {
                val res = storage.containsKey(key)
                storage[key] = value
                return res
            }
        }
        val res = sendOnce("put $key $value")

        return res.toBoolean()
    }

    @Operation
    fun remove(key: Int): Boolean {
        if (environment.processId == 0) {
            lock.withLock {
                val res = storage.containsKey(key)
                if (res) {
                    storage.remove(key)
                }
                return res
            }
        }
        val res = sendOnce("remove $key")

        return res.toBoolean()
    }


    @Operation
    fun get(key: Int): Int? {
        if (environment.processId == 0) {
            lock.withLock {
                return storage[key]
            }
        }
        val res = sendOnce("get $key")
        return res.toIntOrNull()
    }
}

class KVStorageIncorrect(private val environment: Environment) : Node {
    private val lock = ReentrantLock()
    private var commandId = 0
    private val commandResults = HashMap<String, String>()
    private val storage = Collections.synchronizedMap(
            HashMap<Int, Int>())

    private val queue = LinkedBlockingQueue<Message>()

    @StateRepresentation
    fun stateRepresentation() : String {
        return commandResults.toString()
    }

    override fun onMessage(message: Message) {
        if (environment.processId != 0) {
            queue.add(message)
            return
        }
        val id = message.headers["id"]!!
        if (commandResults.containsKey(id)) {
            environment.send(
                    Message(body = commandResults[id]!!,
                            headers = hashMapOf("id" to id, "state" to storage.toString()),
                            sender = environment.processId,
                            receiver = message.sender)
            )
            return
        }
        val tokens = message.body.split(' ')
        val result = lock.withLock {
            when (tokens[0]) {
                "contains" -> storage.containsKey(tokens[1].toInt()).toString()
                "get" -> storage[tokens[1].toInt()]?.toString()
                "put" -> {
                    val present = storage.containsKey(tokens[1].toInt())
                    storage[tokens[1].toInt()] = tokens[2].toInt()
                    present.toString()
                }
                else -> "error"
            }
        }
        environment.send(Message(body = result ?: "null", headers = hashMapOf("id" to id, "state" to storage.toString()),
                sender = environment.processId,
                receiver = message.sender))
    }

    private fun sendOnce(body: String): String {
        val id = commandId++.toString()
        val message = Message(body, headers = hashMapOf("id" to id),
                sender = environment.processId, receiver = 0)
        while (true) {
            environment.send(message)
            val response = queue.poll(10, TimeUnit.MILLISECONDS)
            if (response != null) {
                commandResults[response.headers["id"]!!] = response.body
            }
            if (commandResults.containsKey(id)) {
                return commandResults[id]!!
            }
        }
    }

    @Operation
    fun contains(key: Int): Boolean {
        if (environment.processId == 0) {
            lock.withLock {
                return storage.containsKey(key)
            }
        }
        val res = sendOnce("contains $key")
        return res.toBoolean()
    }

    @Operation
    fun put(key: Int, value : Int): Boolean {
        if (environment.processId == 0) {
            lock.withLock {
                val res = storage.containsKey(key)
                storage[key] = value
                return res
            }
        }
        val res = sendOnce("put $key $value")

        return res.toBoolean()
    }

    @Operation
    fun get(key: Int): Int? {
        if (environment.processId == 0) {
            lock.withLock {
                return storage[key]
            }
        }
        val res = sendOnce("get $key")
        return res.toIntOrNull()
    }
}



class SingleNode {
    private val storage = HashMap<Int, Int>()
    @Operation
    fun contains(key: Int): Boolean {
        return storage.contains(key)
    }

    @Operation
    fun put(key: Int, value: Int) : Boolean {
        val res = storage.containsKey(key)
        storage[key] = value
        return res
    }

    @Operation
    fun get(key: Int): Int? {
        return storage[key]
    }

    @Operation
    fun remove(key : Int) : Boolean {
        val res = storage.containsKey(key)
        if (res) {
            storage.remove(key)
        }
        return res
    }
}

class TestClass : AbstractLincheckTest() {
    @Test
    fun testSimple() {
        LinChecker.check(KVStorageCentralSimple::class
                .java, DistributedOptions().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }

    @Test
    fun testFull() {
        LinChecker.check(KVStorageCentralSimple::class
                .java, DistributedOptions().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).duplicationRate(2).networkReliability(0.7)
                .invocationsPerIteration(100).iterations(100))
    }

    @Test
    fun testNetworkReliability() {
        LinChecker.check(KVStorageCentralSimple::class
                .java, DistributedOptions().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).messageOrder(MessageOrder.ASYNCHRONOUS).networkReliability(0.7)
                .invocationsPerIteration(100).iterations(100))
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrect() {
        LinChecker.check(KVStorageIncorrect::class
                .java, DistributedOptions().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(SingleNode::class.java).threads
        (2).duplicationRate(2).networkReliability(0.7)
                .invocationsPerIteration(100).iterations(1000))
    }
}