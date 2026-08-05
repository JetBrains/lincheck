package org.jetbrains.lincheck_test.project.loops

class ZeroIterForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        val start = 2
        val end = 1
        for (i in start..end) {
            val a: Any = i
            escape = a.toString()
        }
        escape = "END"
    }
}
