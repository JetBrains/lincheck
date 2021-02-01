package org.jetbrains.kotlinx.lincheck.test.distributed.broadcast

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.NodeFailureException
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.*
import kotlin.collections.HashSet


data class Message(val body: String, val id: Int, val from: Int)


class PeerIncorrect(private val env: Environment<Message>) : Node<Message> {
    private val receivedMessages = Array<HashSet<Int>>(env.numberOfNodes) { HashSet() }
    private var messageId = 0
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.numberOfNodes) {
        PriorityQueue { x, y -> x.id - y.id }
    }

    //@Validate
    fun validateResults() {
        // All messages were delivered at most once.
        check(env.localMessages().isDistinct()) { "Process ${env.nodeId} contains repeated messages" }
        // If message m from process s was delivered, it was sent by process s before.
        env.localMessages().forEach { m -> check(env.sentMessages(m.from).map { it.message }.contains(m)) }
        // If the correct process sent message m, it should deliver m.
        if (env.isCorrect()) {
            env.localMessages().forEach { m -> check(env.sentMessages().map { it.message }.contains(m)) }
        }
        // If the message was delivered to one process, it was delivered to all correct processes.
        env.localMessages().forEach { m -> env.correctProcesses().forEach { check(env.localMessages(it).contains(m)) } }
        // If some process sent m1 before m2, every process which delivered m2 delivered m1.
        val localMessagesOrder = Array(env.numberOfNodes) { i ->
            env.localMessages().filter {it.from == i }.map { m -> env.sentMessages(i).map { it.message }.indexOf(m) }
        }
        localMessagesOrder.forEach { check(it.sorted() == it) }
    }

    override fun onMessage(message: Message, sender: Int) {
        val msgId = message.id
        if (!receivedMessages[sender].contains(msgId)) {
            env.broadcast(message)
            receivedMessages[sender].add(msgId)
            env.sendLocal(message)
        }
    }

    @Operation(handleExceptionsAsResult = [NodeFailureException::class])
    fun send(msg: String) {
        val message = Message(body = msg, id = messageId++, from = env.nodeId)
        env.broadcast(message)
    }
}
