# AGENTS.md — Tracer

Module-specific guidance for [`lincheck/tracer/`](.).
Parent guidance: [`../AGENTS.md`](../AGENTS.md).

The runtime tracing engine shared by the `trace-recorder` and `live-debugger` JVM agents.
It provides the common agent scaffolding (`premain`/`agentmain`),
manages tracing sessions,
and converts bytecode-instrumentation callbacks into recorded trace points.

The module builds on two siblings:
[`jvm-agent`](../jvm-agent) — the bytecode-transformation machinery
(`LincheckClassFileTransformer`, the ASM method transformers, `TraceAgentParameters` argument parsing) —
and [`trace`](../trace) — the trace-point model and serialization strategies.
Only `:trace-recorder` and `:live-debugger` depend on `:tracer`;
the Lincheck framework module (`:lincheck`) uses `:jvm-agent` directly and does not.

## How tracing works

### 1. Agent startup

`TracerAgent` is the abstract base class subclassed by `TraceRecorderAgent` and `LiveDebuggerAgent`.
Its `premain`/`agentmain` perform, in order:

1. set the agent's mode system property (`lincheck.traceRecorderMode` / `lincheck.liveDebuggerMode`);
2. attach the `Instrumentation` instance to `LincheckInstrumentation`
   and append the embedded `bootstrap.jar` to the bootstrap classloader search path;
3. parse and validate agent arguments (`TraceAgentParameters`);
4. select the `TracingEntryPoint`:
   `MethodCall` (trace one method), `ApplicationStart` (trace the whole run), or `ExternalRequest` (server-driven);
5. install the instrumentation (`LincheckInstrumentation.install`) —
   and, for `MethodCall`, a `TracingEntryPointTransformer` that switches tracing on/off at that method's entry/exit;
6. optionally start a WebSocket tracing server and/or an application-start tracing session.

### 2. Bytecode transformation (in `jvm-agent`)

`LincheckInstrumentation.install` registers `LincheckClassFileTransformer` as a `ClassFileTransformer`;
the tracing modes (`TRACE_RECORDING`, `LIVE_DEBUGGING`) always use the *eager* strategy,
re-transforming all loaded classes up front.
For each class, `LincheckClassVisitor` assembles a per-method chain of ASM transformers —
method calls, shared-memory and local-variable accesses, loops, throws and catch blocks,
monitors, parking, thread start/join, `invokedynamic`, object creation, snapshot breakpoints —
where a `TransformationProfile` decides per method which events are tracked
under the active `InstrumentationMode`.

The transformers inject static calls to `sun.nio.ch.lincheck.Injections`
(e.g. `Injections::onMethodCall`, `Injections::onMethodCallReturn`).
`Injections` lives in the [`bootstrap`](../bootstrap) module whose jar is appended to the bootstrap classloader,
so the injected calls resolve from every classloader, including classes of `java.base`.

### 3. Trace collection (this module)

`Injections` forwards each event to the installed `EventTracker` (also a `bootstrap` interface).
`Tracer.startTracing` registers `TraceCollectingEventTracker` globally
via `Injections.enableGlobalEventTracking`.
The tracker keeps a per-thread shadow call stack,
creates `TR*` trace points (defined in `trace`),
and feeds them to a `TraceCollectingStrategy` chosen by `TraceOutputMode`:

| `TraceOutputMode` | Strategy (from `trace`) | Behaviour |
|---|---|---|
| `BinaryFileStream` | `FileStreamingTraceCollecting` | stream to a file during execution (default) |
| `BinaryFileDump` | `MemoryTraceCollecting` | collect in memory, dump at the end |
| `BinaryNetworkStream` | `NetworkStreamingTraceCollecting` | stream over WebSocket to connected readers |
| `Text` | `MemoryTraceCollecting` | collect in memory, print as text |
| `Null` | `NullTraceCollecting` | discard everything (testing/benchmarking) |

### 4. Session lifecycle

The `Tracer` singleton owns at most one `TracingSession` at a time.
`launchTracingSession` starts a session, installs an on-finish dump hook,
and registers a JVM shutdown hook that stops tracing on process exit.
`stopTracing` disables global event tracking and finalizes the session;
`dumpTrace` writes the collected trace.
`TracingSession.StartMode` mirrors the entry points: `MethodCall`, `ApplicationStart`, `ExternalRequest`.

## Sources

All sources live in `src/main/org/jetbrains/lincheck/tracer/`:

| File | Contents |
|---|---|
| `TracerAgent.kt` | `TracerAgent` base class, `TracingEntryPoint` |
| `Tracer.kt` | `Tracer` — session-lifecycle entry points |
| `TracingSession.kt` | `TracingSession` — state machine, dump-on-finish hooks |
| `TraceOutputMode.kt` | `TraceOutputMode` — output-strategy selection and parsing |
| `TraceCollectingEventTracker.kt` | the `EventTracker` implementation building trace points |

## Gotchas

- **Append `bootstrap.jar` right after attach.**
  `TracerAgent` appends it before parsing arguments,
  because argument handling may already touch bootstrap-only classes
  (e.g. live-debugger breakpoint loading references `sun.nio.ch.lincheck.BreakpointStorage`).
  `LincheckInstrumentation.appendBootstrapJarToClassLoaderSearch` is idempotent;
  `install` invokes it again as a safety net.
- **Lincheck's own classes are never instrumented.**
  `LincheckClassFileTransformer.shouldTransform` rejects them first (`isInLincheckPackage`)
  to avoid class-loading circularity.
- **Transformed bytecode is cached per `InstrumentationMode`** —
  except `LIVE_DEBUGGING`, which disables the cache
  so re-transformations pick up breakpoint additions and removals.
- **Failed transformation is not fatal**:
  the transformer logs a warning and keeps the original bytecode for that class.
- **One session, one global context.**
  `startTracing` returns the already-running session instead of creating a second one,
  and every session reuses the global `TraceContext` (`LincheckInstrumentation.context`).
- **Debug system properties**:
  `lincheck.dumpTransformedSources` writes transformed bytecode under `build/transformedBytecode/`;
  `lincheck.collectTransformationStatistics` logs transformation statistics;
  `lincheck.instrumentAllClasses` forces eager re-transformation of all loaded classes.
- **Internals are friend-visible.**
  `trace-recorder` and `live-debugger` compile against this module's `internal` declarations
  (via `getAccessToInternalDefinitionsOf` in `buildSrc/src/main/kotlin/Toolchain.kt`),
  so renaming internals here can break the agents.
