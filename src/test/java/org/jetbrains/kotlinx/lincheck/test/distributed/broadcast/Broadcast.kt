package org.jetbrains.kotlinx.lincheck.test.distributed.broadcast

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Message
import org.jetbrains.kotlinx.lincheck.distributed.Process
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class Peer(private val env : Environment) : Process {
    private val receivedMessages = Array<HashSet<Int>>(env.nProcesses) { HashSet() }
    private var messageId = 0
    private val userMessages = ConcurrentLinkedQueue<Message>()
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.nProcesses) {PriorityQueue(
            kotlin.Comparator { x, y -> x.headers["id"]!!.toInt() - y.headers["id"]!!.toInt()}
    )}
    //private val lastSendMessage =

    override fun onMessage(srcId: Int, message: Message) {
        val msgId = message.headers["id"]!!.toInt()
        val sender = message.headers["from"]!!.toInt()
        if (!receivedMessages[sender].contains(msgId)) {
            env.broadcast(message)
            receivedMessages[sender].add(msgId)
            env.sendLocal(message)
        }
    }

    @Operation
    fun send(msg : String) {
        val message = Message(body=msg, headers = hashMapOf("id" to messageId++.toString(), "from" to env.processId.toString()))
        env.broadcast(message)
    }
}

class BroadcastTest : AbstractLincheckTest() {

}

