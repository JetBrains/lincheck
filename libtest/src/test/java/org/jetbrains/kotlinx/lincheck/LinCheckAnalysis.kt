package org.jetbrains.kotlinx.lincheck

/**
 * Runs all concurrent tests described with {@code @<XXX>CTest} annotations on the specified test class.
 *
 * @return AnalysisReport with information about concurrent test run. Holds AssertionError is data structure is incorrect.
 */
internal fun linCheckAnalysis(testClass: Class<*>, options: Options<*, *>? = null) = LinChecker.analyze(testClass, options)

