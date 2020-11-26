package org.jetbrains.kotlinx.lincheck.test.distributed.kvstorage

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.junit.Test

class KVStorageServer(private val env: Environment) : Node {
    private val storage = HashMap<Int, Int>()
    private val commandResults = Array<HashMap<String, Message>>(env.nProcesses) {
        HashMap()
    }

    override fun onMessage(message: Message) {
        val id = message.headers["id"]!!
        if (commandResults[message.sender!!].containsKey(id)) {
            env.send(commandResults[message.sender!!][id]!!, message.sender!!)
            return
        }
        val tokens = message.body.split(' ')
        val result = when (tokens[0]) {
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
        val response = Message(body = result.toString(), headers = hashMapOf("id" to id))
        commandResults[message.sender!!][id] = response
        env.send(response, message.sender!!)
    }
}

class KVStorageClient(private val env: Environment) : NodeWithReceiveImp() {
    private var commandId = 0
    private val commandResults = HashMap<String, String>()
    private val serverAddr = env.getAddress(KVStorageServer::class.java, 0)

    private fun sendOnce(body: String): String {
        val id = commandId++.toString()
        val message = Message(body, headers = hashMapOf("id" to id))
        while (true) {
            env.send(message, serverAddr)
            val response = receive(10)
            if (response != null) {
                commandResults[response.headers["id"]!!] = response.body
            }
            if (commandResults.containsKey(id)) {
                return commandResults[id]!!
            }
        }
    }

    @Operation
    fun put(key: Int, value: Int): Boolean {
        val res = sendOnce("put $key $value")
        return res.toBoolean()
    }

    @Operation
    fun get(key: Int): Int? {
        val res = sendOnce("get $key")
        return res.toIntOrNull()
    }

    @Operation
    fun contains(key: Int): Boolean {
        val res = sendOnce("contains $key")
        return res.toBoolean()
    }

    @Operation
    fun remove(key: Int): Boolean {
        val res = sendOnce("remove $key")
        return res.toBoolean()
    }
}

class KVStorageServerTestClass : AbstractLincheckTest() {
    @Test
    fun testSimple() {
        LinChecker.check(KVStorageCentralSimple::class.java,
                DistributedOptions().requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(SingleNode::class.java)
                .testClass(KVStorageServer::class.java, 1)
                .testClass(KVStorageClient::class.java, 1)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }

    @Test
    fun testFull() {
        LinChecker.check(KVStorageCentralSimple::class.java,
                DistributedOptions()
                        .requireStateEquivalenceImplCheck(false)
                        .sequentialSpecification(SingleNode::class.java)
                        .testClass(KVStorageServer::class.java, 1)
                        .testClass(KVStorageClient::class.java, 5)
                        .duplicationRate(2)
                        .networkReliability(0.7)
                        .invocationsPerIteration(100)
                        .iterations(100))
    }
}