package org.jetbrains.kotlinx.lincheck_test.representation

import kotlin.random.*

class KotlinRandomRepresentationTest : BaseTraceRepresentationTest("kotlin_random_representation") {
    private var a = 0

    override fun operation() {
        a = Random.nextInt()
    }
}