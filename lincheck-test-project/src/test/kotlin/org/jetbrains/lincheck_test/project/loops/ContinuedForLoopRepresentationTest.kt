package org.jetbrains.lincheck_test.project.loops

class ContinuedForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..5) {
            val a: Any = i
            escape = a.toString()
            if (i % 2 == 0) {
                continue
            }
            escape = "${a} is odd"
        }
        escape = "END"
    }
}
