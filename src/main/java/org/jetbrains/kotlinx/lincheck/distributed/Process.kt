package org.jetbrains.kotlinx.lincheck.distributed

import java.util.concurrent.ConcurrentLinkedQueue

interface Node {
    fun onMessage(message : Message)
}

abstract class NodeReceive : Node {
    private val messageQueue = ConcurrentLinkedQueue<Message>()
    override fun onMessage(message: Message) {
        messageQueue.add(message)
    }
}