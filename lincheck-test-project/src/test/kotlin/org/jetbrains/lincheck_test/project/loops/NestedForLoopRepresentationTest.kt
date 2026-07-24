package org.jetbrains.lincheck_test.project.loops

class NestedForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..2) {
            val a: Any = i
            for (j in 1 .. 3) {
                escape = "$a.$j"
            }
        }
        escape = "END"
    }
}
