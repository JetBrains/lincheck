package org.jetbrains.lincheck_test.project.loops

class TwoForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..5) {
            val a: Any = i
            escape = "A." + a.toString()
        }
        for (i in 1..5) {
            val a: Any = i
            escape = "B." + a.toString()
        }
        escape = "END"
    }
}
