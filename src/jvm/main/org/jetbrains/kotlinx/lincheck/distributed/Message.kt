package org.jetbrains.kotlinx.lincheck.distributed

class Message(val body : String, val headers : HashMap<String, String> = HashMap()) {
    var receiver : Int? = null
    var sender : Int? = null
    internal var id : Int? = null
}