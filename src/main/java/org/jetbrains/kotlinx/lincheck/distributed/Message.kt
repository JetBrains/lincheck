package org.jetbrains.kotlinx.lincheck.distributed

class Message(val body : String, val sender : Int, val headers : HashMap<String, String> = HashMap(), var receiver : Int)