package org.jetbrains.lincheck_test.project.loops

class OneIterForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..1) {
            val a: Any = i
            escape = a.toString()
        }
        escape = "END"
    }
}
