package org.jetbrains.lincheck_test.project.loops

class ForLoopWithMethodCallRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..2) {
            val a: Any = i
            method(a)
        }
        escape = "END"
    }

    private fun method(a : Any) {
        escape = a.toString()
    }
}
