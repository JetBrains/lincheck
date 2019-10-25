package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options

fun linCheckAnalyze(clazz: Class<*>, options: Options<*, *>?) = LinChecker.analyze(clazz, options)