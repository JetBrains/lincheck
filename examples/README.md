# Lincheck Demo Project

This is a dedicated demo project for Lincheck. The examples in this project are directly used in the [Lincheck
documentation](https://kotlinlang.org/docs/lincheck-guide.html). The documentation also provides detailed explanations
for every example.

If you want to explore this project locally, open it separately from the main `lincheck` project.
The build includes Lincheck from the parent checkout, so examples always use the local framework sources.
To see the visualizations for data structures and executions traces,
install the [Lincheck plugin for IntelliJ IDEA](https://plugins.jetbrains.com/plugin/24171-lincheck).

## Verify the documentation examples

Several examples deliberately contain bugs and fail when run directly in the IDE or with `test`.
The `verifyExamples` task checks that these examples fail for the documented reason,
and that all other examples pass.
It also fails if an expected failure unexpectedly passes.
The expectations and their JUnit extension live in `src/verification`,
so the snippets shown in the guide retain their ordinary test bodies.

From the Lincheck repository root, run:

```bash
./gradlew -p examples verifyExamples
```

The examples build substitutes its Lincheck dependency with the parent checkout.
The HTML test report is written to `examples/build/reports/tests/verifyExamples`.

New examples are discovered automatically and must pass unless an expected failure is declared in
`src/verification/kotlin/org/lincheck/docs/verification/ExpectedFailures.kt`.
Expectations use method references, so renaming or deleting an expected-failure example also requires updating its entry.
Each example class runs in a separate JVM, with up to four JVMs running concurrently.
Method ordering within a class is preserved.
The verification task sets `lincheck.defaultRandomScenarios=10`,
`lincheck.defaultInvocationsPerScenario=1000`, and `lincheck.defaultInvocations=1000` (for lambda-based tests)
to bound exploration without editing the examples.
The queue linearizability example uses an explicit, larger budget to expose its intentional ordering bug.
Explicit options in individual examples take precedence over these defaults.
