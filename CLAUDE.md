# CLAUDE.md

This repository contains three main products:
- **Lincheck** — a framework for testing concurrent code on the JVM. It explores thread interleavings to find concurrency bugs via stress testing and bounded model checking, verifying correctness properties like linearizability and quiescent consistency. See [README.md](README.md) for usage examples and quick start.
- **Trace Recorder** — a JVM agent that records execution traces for later analysis.
- **Live Debugger** — a JVM agent for real-time debugging of concurrent code via WebSocket.

## Build Commands

```bash
# Build everything (includes tests)
./gradlew build

# Run main module tests only
./gradlew test

# Run isolated tests (each in separate JVM, excluded from regular `test`)
./gradlew testIsolated

# Run specific test class
./gradlew test --tests "org.jetbrains.kotlinx.lincheck_test.SomeTest"

# Run a submodule's tests
./gradlew :trace:test
./gradlew :integration-test:lincheck:test

# Auto-repair representation test baselines (verify changes manually!)
./gradlew test -PoverwriteRepresentationTestsOutput=true

# Use another JDK version (default 17)
./gradlew test -PjdkToolchainVersion=21

# Enable debug logging
./gradlew test -Plincheck.logLevel=DEBUG
```

Tests run single-fork (`maxParallelForks = 1`) with 6GB heap. The `check` task includes both `test` and `testIsolated`.

### Integration Tests

Run these after big changes:

```bash
# Lincheck integration tests (JCTools, concurrent data structures)
./gradlew :integration-test:lincheck:integrationTest

# Trace recorder integration tests (basic suite)
./gradlew :integration-test:trace-recorder:traceRecorderIntegrationTest

# Trace recorder with specific test suite
./gradlew :integration-test:trace-recorder:traceRecorderIntegrationTest -PintegrationTestSuite=kotlincompiler
./gradlew :integration-test:trace-recorder:traceRecorderIntegrationTest -PintegrationTestSuite=ktor
./gradlew :integration-test:trace-recorder:traceRecorderIntegrationTest -PintegrationTestSuite=ij
./gradlew :integration-test:trace-recorder:traceRecorderIntegrationTest -PintegrationTestSuite=all
```

The `traceRecorderIntegrationTest` task automatically downloads required test projects from GitHub via the `traceAgentIntegrationTestsPrerequisites` task.

## Architecture

**Execution flow:** `LinChecker` creates an `ExecutionScenario` (parallel threads with `Actor` operations), picks a `Strategy`, and runs via a `Runner`. Results go through a `Verifier`.

**Two strategies:**
- `ModelCheckingStrategy` (`strategy/managed/modelchecking/`) - Systematically explores interleavings via bytecode instrumentation. This is the primary strategy.
- `StressTestingStrategy` (`strategy/stress/`) - Runs threads concurrently relying on OS scheduling randomness.

**Bytecode transformation pipeline:** ASM-based transformers instrument synchronization, volatile accesses, atomic operations, coroutines, and method calls to enable controlled execution under model checking.


## Key Conventions

- Kotlin with 4-space indentation, following [Kotlin Coding Conventions](https://kotlinlang.org/docs/reference/coding-conventions.html)
- Bootstrap classes must live in `sun.nio.ch.lincheck` package for classloader visibility
- Test classes use `lincheck_test` package namespace

## Tooling and Workflow

- **Use skills** from `/skills`.
- **Always check for dead code** after refactorings — ensure no unused imports, functions, classes, or variables are left behind.
- **Always run tests** (`./gradlew test`) after making changes to verify nothing is broken.
- **Representation tests:** Compare actual execution trace output against expected baselines stored in test resources. Use `-PoverwriteRepresentationTestsOutput=true` to update baselines.
- **Run integration tests** after big changes (see [Integration Tests](#integration-tests) above).
- **Performance regressions**: use `/perf-bisect` to incrementally bisect changes and find the minimal change causing degradation.
