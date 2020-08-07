package org.jetbrains.kotlinx.lincheck.verifier.quasi

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import kotlin.reflect.KClass


/**
 * This annotation with quasi-linearizability verifier
 * parameters should be presented on a test class
 * if the [QuasiRelaxedLinearizabilityVerifier] is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
annotation class QuasiLinearizabilityVerifierConf(
        /**
         * Relaxation factor.
         */
        val factor: Int,
        /**
         * The sequential implementation of the
         * testing data structure  with the same
         * methods as operations in the test class
         * (same name, signature), but with a
         * correct sequential implementations.
         * It also should have an empty constructor
         * which creates the initial state
         * (similar to the test class).
         */
        val sequentialImplementation: KClass<*>)
