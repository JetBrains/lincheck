package org.jetbrains.lincheck_test.project.loops

class BreakedForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..5) {
            val a: Any = i
            escape = a.toString()
            if (i > 3) {
                break
            }
            escape = "${a} is saved"
        }
        escape = "END"
    }
}
