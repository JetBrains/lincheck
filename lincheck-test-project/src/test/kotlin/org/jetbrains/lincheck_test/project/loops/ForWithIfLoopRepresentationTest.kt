package org.jetbrains.lincheck_test.project.loops

class ForWithIfLoopRepresentationTest : BaseLoopTest() {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        var i = 0
        var total = 0
        while (true) {
            total++
            if (total > 10) {
                break
            }
            val a: Any = i
            escape = a.toString()
            i = (i + 1) % 3
            if (i == 0) {
                escape = "%3-" + escape
            }
        }
        escape = "END"
    }
}
