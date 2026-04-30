# AGENTS.md — Lincheck

Guidance for AI coding agents working in the Lincheck repository.
Follows the [agents.md](https://agents.md/) convention; `CLAUDE.md` just imports this file.

## Submodules

| Module | Purpose |
|---|---|
| [`bootstrap/`](bootstrap/) | Java classes loaded into `sun.nio.ch.lincheck` so they're visible from any classloader. JDK 8-compatible. |
| [`common/`](common/) | ASM helpers, `LoggingLevel`, shared utilities. |
| [`trace/`](trace/) | Binary trace format + reader/writer (eager + lazy). |
| [`tracer/`](tracer/) | Instrumentation engine on ByteBuddy + ASM. |
| [`jvm-agent/`](jvm-agent/) | `premain`/`agentmain`; installs the class-file transformer. |
| [`lincheck/`](lincheck/) | Lincheck framework: `LinChecker`, strategies, runners, verifiers. |
| [`trace-recorder/`](trace-recorder/) | JVM agent that records execution traces (stream or dump). |
| [`live-debugger/`](live-debugger/) | JVM agent for dynamic, non-suspending breakpoints. |
| [`integration-test/`](integration-test/) | `lincheck/` (JCTools etc.), `trace-recorder/` (real-world project recordings), `live-debugger/` (end-to-end coverage). |

## Build

```bash
./gradlew build                                 # full build + tests
./gradlew test                                  # unit tests
./gradlew testIsolated                          # tests that need a fresh JVM each
./gradlew test --tests "<FQN>"                  # targeted
./gradlew :<submodule>:test                     # one submodule
./gradlew test -PjdkToolchainVersion=21         # JDK 8/11/17/21; default 17
./gradlew test -PoverwriteRepresentationTestsOutput=true  # refresh baselines; diff before commit
./gradlew test -Plincheck.logLevel=DEBUG        # debug logging
```

`check` aggregates `test` + `testIsolated`.

### Integration tests

```bash
./gradlew :integration-test:lincheck:integrationTest

./gradlew :integration-test:trace-recorder:traceRecorderIntegrationTest
./gradlew :integration-test:trace-recorder:traceRecorderIntegrationTest \
  -PintegrationTestSuite=kotlinCompiler|ktor|ij|all
```

`traceRecorderIntegrationTest` downloads the target external projects
via `traceAgentIntegrationTestsPrerequisites`.

## Testing

- `test` is single-fork (`maxParallelForks = 1`, 6 GB heap) — concurrency
  tests need JVM isolation. Don't run after every edit; prefer
  compile-check + targeted `--tests`.
- **Representation tests** compare execution traces against baselines in
  `src/.../resources/`. Refresh with
  `-PoverwriteRepresentationTestsOutput=true` and diff before committing.
  **Always add a representation test** illustrating new behaviour when
  you change the managed strategy, transformation pipeline, or trace
  output.

## Conventions

- Kotlin, 4-space indentation. `kotlin.code.style=official`.
- `allWarningsAsErrors = true` — warnings fail the build.
- **Packages**: main `org.jetbrains.lincheck.*`; tests
  `org.jetbrains.kotlinx.lincheck_test.*` (note underscore); bootstrap
  classes **must** live in `sun.nio.ch.lincheck`.
- **Test-class naming**: `*Test.kt` (regular), `*IsolatedTest*.kt` (picked
  up by `testIsolated`), `AbstractTraceIntegrationTest` for trace integ
  tests.
- **Conditional sources**: `src/jvm/test-jdk8/` and `src/jvm/test-jdk11/`
  only compile under the matching toolchain.
- Bootstrap is JDK-8 source/target; everything else uses the toolchain
  (default 17, `-PjdkToolchainVersion=<v>`).

## Don't

- Don't bake machine-specific paths or env assumptions into tests.
