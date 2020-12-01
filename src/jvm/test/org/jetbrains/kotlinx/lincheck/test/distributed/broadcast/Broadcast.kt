package org.jetbrains.kotlinx.lincheck.test.distributed.broadcast

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Message
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.*
import kotlin.collections.HashSet

/**
 * Implementation of the correct broadcast algorithm.
 * Assume
 */
class Peer(private val env: Environment) : Node {
    private val receivedMessages = Array<HashSet<Int>>(env.numberOfNodes) { HashSet() }
    private var messageId = 0
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.numberOfNodes) {
        PriorityQueue(
                kotlin.Comparator { x, y -> x.headers["id"]!!.toInt() - y.headers["id"]!!.toInt() }
        )
    }

    @Validate
    fun validateResults() {
        val p = env.processExecution!!
        env.checkLocalMessages(atMostOnce = true, preserveOrder = true)
        if (p.isAlive) {
            p.sentMessages.forEach { m -> check(p.localMessages.contains(m)) }
        }
        p.localMessages.forEach {
            check(env.processes[it.headers["from"]!!.toInt()].sentMessages.contains(it))
        }
        env.processes.filter { it.isAlive }.forEach {
            it.sentMessages.forEach { m ->
                p.localMessages.contains(m)
            }
        }
    }

    override fun onMessage(message: Message) {
        val msgId = message.headers["id"]!!.toInt()
        val sender = message.headers["from"]!!.toInt()
        if (!receivedMessages[sender].contains(msgId)) {
            env.broadcast(message)
            receivedMessages[sender].add(msgId)
            env.sendLocal(message)
        }
    }

    @Operation
    fun send(msg: String) {
        val message = Message(body = msg, headers = hashMapOf("id" to messageId++.toString(), "from" to env.nodeId.toString()))
        env.broadcast(message)
    }
}

class BroadcastTest {
    // Just an API example, doesn't work
    @Test(expected = InvocationTargetException::class)
    fun test() {
        LinChecker.check(Peer::class
                .java, DistributedOptions().requireStateEquivalenceImplCheck
        (false).threads
        (5).maxNumberOfFailedNodes(2).supportRecovery(false)
                .invocationsPerIteration(100).iterations(1000))
    }
}

