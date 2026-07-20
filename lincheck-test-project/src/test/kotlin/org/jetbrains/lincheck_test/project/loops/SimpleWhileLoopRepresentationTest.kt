package org.jetbrains.lincheck_test.project.loops

class SimpleWhileLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        var i = 1
        while (i < 4) {
            val a: Any = i
            escape = a.toString()
            i++
        }
        escape = "END"
    }
}
