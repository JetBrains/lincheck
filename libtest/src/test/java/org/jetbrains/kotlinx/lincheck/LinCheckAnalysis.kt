package org.jetbrains.kotlinx.lincheck

fun linCheckAnalysis(testClass: Class<*>, options: Options<*, *>? = null) = LinChecker.analyze(testClass, options)

