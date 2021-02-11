/*
package distributed.guarantees

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Message
import org.jetbrains.kotlinx.lincheck.distributed.Process
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class Sender(private val environment: Environment, private val receiverId : Int){
    private var id : AtomicInteger = AtomicInteger(0)

    private fun sendAtMostOnce(msg : Message) {
        
    }

    @Operation
    fun send(message: Message) {
        message.headers["id"] = id.getAndIncrement().toString()
        environment.send(receiverId, message)
    }
}


class Receiver(private val environment: Environment, private val senderId : Int) {
    private val received = Collections.synchronizedMap(HashMap<String, String>())
    fun run() {
        while(true) {
            val message = environment.receive()
            val id = message.headers["id"]
            if (!received.contains(id)) {
                received[id] = message.body
                environment.sendLocal(message)
            }
        }
    }
}
*/