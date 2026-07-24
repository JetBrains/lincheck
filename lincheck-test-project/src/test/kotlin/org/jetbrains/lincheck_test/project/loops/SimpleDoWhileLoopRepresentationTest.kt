package org.jetbrains.lincheck_test.project.loops

class SimpleDoWhileLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        var i = 2
        do {
            val a: Any = i
            escape = a.toString()
            i++
        } while(i < 4)
        escape = "END"
    }
}
