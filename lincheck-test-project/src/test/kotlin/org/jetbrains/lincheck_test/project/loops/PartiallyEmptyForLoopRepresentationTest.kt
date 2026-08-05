package org.jetbrains.lincheck_test.project.loops

class PartiallyEmptyForLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        val builder = StringBuilder()
        for (i in 1..5) {
            builder.append(i)
            if (i % 2 == 0) {
                escape = builder.toString()
                builder.clear()
            }
        }
        escape = builder.toString()
        escape = "END"
    }
}
