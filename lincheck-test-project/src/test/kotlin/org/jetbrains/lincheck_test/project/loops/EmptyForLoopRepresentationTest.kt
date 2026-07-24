package org.jetbrains.lincheck_test.project.loops

class EmptyForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        val builder = StringBuilder()
        for (i in 1..2) {
            builder.append(i)
        }
        escape = builder.toString()
        escape = "END"
    }
}
