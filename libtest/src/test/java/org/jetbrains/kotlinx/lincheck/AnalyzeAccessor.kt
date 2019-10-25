package org.jetbrains.kotlinx.lincheck

internal fun linCheckAnalyze(clazz: Class<*>, options: Options<*, *>?): TestReport = LinChecker.analyze(clazz, options)