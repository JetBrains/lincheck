# AGENTS.md — Live Debugger agent

Module-specific guidance for [`lincheck/live-debugger/`](.).
Parent guidance: [`../AGENTS.md`](../AGENTS.md).

A JVM agent for dynamic, non-suspending breakpoints.
A *snapshot breakpoint* captures the program state at a source line —
stack trace, local variables, and watch-expression values —
without stopping any thread,
and emits it as a trace point into the regular trace output.

The agent is built on the [`tracing-agent`](../tracing-agent) engine
and runs the instrumentation in `InstrumentationMode.LIVE_DEBUGGING`.

## How breakpoints work

A `SnapshotBreakpoint` (see [`common`](../common), package `org.jetbrains.lincheck.settings`)
is identified by UUID and addressed by class name, file name, and line number.
A breakpoint may carry a condition and watch expressions and a hit limit.
Clients send expressions as plain source text (`conditionSource` / `watchSources`,
capability `AGENT_COMPILED_EXPRESSIONS_V1`),
which the agent compiles at instrumentation time,
in the language of the breakpoint's source file —
Java through the JDK's own compiler (`javax.tools`),
Kotlin through `kotlin-compiler-embeddable`,
shipped as the nested `kotlin-expression-compiler.jar` resource and loaded in an
isolated class loader so it never touches the application's class path
(see `ExpressionCompiler` in [`jvm-agent`](../jvm-agent)).
The compiled-fragment fields remain an internal representation after agent-side compilation.

- Adding or removing breakpoints re-transforms the affected loaded classes;
  the agent finds them by the breakpoint's *file name* in its own `SourceFileClassIndex`
  (in [`jvm-agent`](../jvm-agent), an index from the class-file `SourceFile` attribute
  to the class definitions — class name plus defining loader — compiled from it),
  not by the IDE-provided class name.
  The index stores no `Class` objects: a query resolves its entries against
  `Instrumentation.getAllLoadedClasses`, which it has to walk anyway to pick up
  classes loaded before a dynamic attach.
  `SnapshotBreakpointTransformer` (in [`jvm-agent`](../jvm-agent)) injects the capture code,
  which calls back through `sun.nio.ch.lincheck.Injections.onSnapshotLineBreakpoint`.
- Each hit produces a `TraceSnapshotLineBreakpointTracePoint`
  with the captured stack trace, locals, watches, and timestamp.
- Hit limits are enforced via `sun.nio.ch.lincheck.BreakpointStorage`;
  reaching the limit disables the breakpoint and notifies the client.
- Conditions and watches are checked for side effects at transformation time;
  an unsafe expression disables the breakpoint and triggers a notification.

## Remote control

With `tracingServer=on` (or in heartbeat mode) the agent runs a `TracingWebSocketServer`
(from [`trace`](../trace), package `org.jetbrains.lincheck.trace.network`):

- commands: `startFileTracing`, `startNetworkTracing`, `stopTracing`, `addBreakpoints`, `removeBreakpoints`;
- notifications: `hitLimitReached`, `breakpointExpressionUnsafe`, plus binary trace data when network-streaming.

On client disconnect, all breakpoints are removed and `BreakpointStorage` is cleared.

With `liveDebuggerHeartbeat=on` the agent opens no listening socket;
instead it POSTs a heartbeat to `<control-plane URL>/api/heartbeat` every 10 seconds
and opens a *reversed* WebSocket connection to `/api/agent/{agentId}` when the control plane requests it.
Required environment variables: `NAME`, `LIVE_DEBUGGER_CONTROL_PLANE_URL`; optional: `NAMESPACE`
(see `PhoneHomeHeartbeat.kt`).

`enableSsl=on` routes both legs over TLS: `ControlPlane` rewrites the configured `http://` URL to
`https://` (the `^http` → `ws` rewrite then yields `wss://`), installs the truststore's socket factory
on every `HttpsURLConnection`, and hands the same factory to `makeReversedConnection`.
Trust material comes from `TlsTrust` in [`common`](../common) —
`sslTruststorePath` if given, the JVM default truststore otherwise.
Both legs keep hostname verification on, so the certificate has to name the host in the URL.

## Building

```shell
./gradlew :live-debugger:liveDebuggerFatJar   # -> live-debugger/build/libs/app-glass-agent.jar
```

The fat jar (see `registerTraceAgentTasks` in `buildSrc/src/main/kotlin/TraceAgentTasks.kt`):

- exposes only the dependency-free `AgentWrapper` at the root;
- embeds `bootstrap.jar` and the unshaded `agent-payload.jar` as nested resources;
- sets `Premain-Class`/`Agent-Class` to the wrapper,
  which loads the payload with the platform classloader as parent and invokes `LiveDebuggerAgent` reflectively.

`liveDebuggerFatJarVerify` asserts the packaging invariants
(wrapper-only class whitelist; nested — never unpacked — bootstrap and payload jars)
and runs automatically after the fat jar and as part of `check`.
`liveDebuggerFatJarNoDeps` builds a dependency-free jar for debugging.

## Attaching

```shell
java -javaagent:app-glass-agent.jar=tracingServer=on,serverPort=9999 -jar yourApp.jar
```

Arguments are comma-separated `key=value` pairs (parsed by `TraceAgentParameters` in `jvm-agent`):

| Argument | Meaning |
|---|---|
| `tracingServer` | `on`/`off` — start the WebSocket server (default `off`) |
| `serverPort` | WebSocket server port (default `9999`) |
| `liveDebuggerHeartbeat` | `on`/`off` — heartbeat mode for orchestrated environments (default `off`) |
| `enableSsl` | `on`/`off` — TLS for the control-plane connections (default `off`) |
| `sslTruststorePath`, `sslTruststorePassword` | CA truststore verifying the control plane's certificate (default: JVM truststore) |
| `breakpointsFile` | path to an INI file with breakpoints, loaded at startup (`BreakpointsFileParser`) |
| `output`, `format`, `formatOption` | trace output for whole-application tracing without a server |

The agent sets `lincheck.liveDebuggerMode=true` itself at attach.
`class`/`method` arguments are rejected — live debugging has no single method under tracing.
Dynamic attach is supported via `agentmain`;
without a server or heartbeat, static attach starts whole-application tracing dumped to `output`.

## Module dependencies

`bootstrap` (compile-only), `common`, `jvm-agent`, `trace`, `tracing-agent`;
all runtime dependencies stay inside the isolated payload.

## Publishing

`maven-publish` publishes the fat jar as
`org.jetbrains.appglass:app-glass-agent:<liveDebuggerFatVersion>`
(coordinates in this module's `gradle.properties`).

## Testing

End-to-end coverage lives in [`integration-test/live-debugger`](../integration-test/live-debugger):

```shell
./gradlew :integration-test:live-debugger:liveDebuggerIntegrationTest
```
