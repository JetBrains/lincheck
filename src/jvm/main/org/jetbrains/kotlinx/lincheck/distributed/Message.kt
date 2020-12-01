package org.jetbrains.kotlinx.lincheck.distributed

/**
 * Represents a message from one process to another.
 * Message contains [body] and [headers] (any additional meta information).
 * [sender] is a process which sent the message
 * [receiver] is a process to whom message was sent
 * [id] is a unique parameter to identify messages
 */
class Message(val body : String, val headers : HashMap<String, String> = HashMap()) {
    var receiver : Int? = null
    var sender : Int? = null
    internal var id : Int? = null
}