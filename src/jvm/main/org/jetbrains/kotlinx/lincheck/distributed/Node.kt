package org.jetbrains.kotlinx.lincheck.distributed

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

interface Node {
    fun onMessage(message : Message)
}

abstract class NodeWithReceiveImp : Node {
    private val messageQueue = LinkedBlockingQueue<Message>()
    override fun onMessage(message: Message) {
        messageQueue.add(message)
    }

    fun receive(timeout : Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) : Message? {
        return messageQueue.poll(timeout, timeUnit)
    }

    fun receive() : Message? {
        return messageQueue.poll()
    }
}