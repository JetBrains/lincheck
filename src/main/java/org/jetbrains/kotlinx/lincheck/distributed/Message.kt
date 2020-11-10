package org.jetbrains.kotlinx.lincheck.distributed

data class Message(val body : String, val sender : Int, val headers : HashMap<String, String> = HashMap())