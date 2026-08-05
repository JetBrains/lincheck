package org.jetbrains.lincheck_test.project.loops

class SimpleForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..2) {
            val a: Any = i
            escape = a.toString()
        }
        escape = "END"
    }
}
