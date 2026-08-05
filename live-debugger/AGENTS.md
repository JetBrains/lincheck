# AGENTS.md — Live Debugger agent

Module-specific guidance for [`lincheck/live-debugger/`](.).
Parent guidance: [`../AGENTS.md`](../AGENTS.md).

A JVM agent for dynamic, non-suspending breakpoints.
A *snapshot breakpoint* captures the program state at a source line —
stack trace, local variables, and watch-expression values —
without stopping any thread,
and emits it as a trace point into the regular trace output.

The agent is built on the [`tracer`](../tracer) engine
and runs the instrumentation in `InstrumentationMode.LIVE_DEBUGGING`.

## How breakpoints work

A `SnapshotBreakpoint` (see [`common`](../common), package `org.jetbrains.lincheck.settings`)
is identified by UUID and addressed by class name, file name, and line number.
It may carry a condition and watch expressions — shipped as precompiled bytecode class fragments —
and a hit limit.

- Adding or removing breakpoints re-transforms the affected loaded classes;
  `SnapshotBreakpointTransformer` (in [`jvm-agent`](../jvm-agent)) injects the capture code,
  which calls back through `sun.nio.ch.lincheck.Injections.onSnapshotLineBreakpoint`.
- Each hit produces a `TRSnapshotLineBreakpointTracePoint`
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

- relocates `org.objectweb.asm`, `net.bytebuddy`, `org.java_websocket`, and `org.slf4j`
  under `org.jetbrains.lincheck.shadow.*` to avoid classpath collisions with the target app;
- embeds `bootstrap.jar` as a nested resource, installed on the bootstrap classloader at attach;
- sets `Premain-Class`/`Agent-Class` to `org.jetbrains.lincheck.livedebugger.LiveDebuggerAgent`.

`liveDebuggerFatJarVerify` asserts the packaging invariants
(class-package whitelist; nested — never unpacked — `bootstrap.jar`)
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

`bootstrap` (compile-only), `common`, `jvm-agent`, `trace`, `tracer`.

## Publishing

`maven-publish` publishes the fat jar as
`org.jetbrains.appglass:app-glass-agent:<liveDebuggerFatVersion>`
(coordinates in this module's `gradle.properties`).

## Testing

End-to-end coverage lives in [`integration-test/live-debugger`](../integration-test/live-debugger):

```shell
./gradlew :integration-test:live-debugger:liveDebuggerIntegrationTest
```
