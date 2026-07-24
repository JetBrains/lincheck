package org.jetbrains.lincheck_test.project.loops

class RepeatedForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        loop("Loop-A")
        escape = "MID"
        loop("Loop-B")
        escape = "END"
    }

    private fun loop(prefix: String) {
        escape = "${prefix}-START"
        for (i in 1..2) {
            val a: Any = i
            escape = "${prefix}-${a}"
        }
        escape = "${prefix}-END"
    }
}
