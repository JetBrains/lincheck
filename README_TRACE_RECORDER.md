# This is README for running Trace Recorder and parsing its output.
## Principle of operation
Trace Recorder can record the execution of one method without arguments. It doesn't arrange the execution of the method
itself, it only instruments and prepares the method for recording. Method should be called by other means: via
JUnit harness, `main()` function, or any other way to run JVM program.

Method must not have any arguments (this limitation will be lifted in the future).

Results of trace (call tree rooted at the method and all support data) is written to binary output file.

## Configuring Trace Recorder.
To configure Trace Recorder, you should run JVM with an attached Java Agent like this:

```shell
pathToLincheckFarJar=/some/path/where/lincheck-fat.jar
className=dotted.class.name.to.Trace
methodName=methodNameWithoutSignature
output=path/to/output.bin
format=binary
option=stream
java -javaagent:"${pathToLincheckFarJar}=${className},${methodName},${output},${format},${option}" -Dlincheck.traceRecorderMode=true ...
```

In `gradle.build.kts` it will be something like this:


```kotlin
    named("jvmTest", Test::class) {
        val pathToLincheckFarJar = "/some/path/where/lincheck-fat.jar"
        val className = "dotted.class.name.to.Trace"
        val methodName = "methodNameWithoutSignature"
        val output = "path/to/output.bin"
        val format = "binary"
        val option = "stream"

        jvmArgs("-javaagent:${pathToLincheckFarJar}=$className,$methodName,$output,$format,$option")
        jvmArgs("-Dlincheck.traceRecorderMode=true")
    }
```

### Possible formats and options:
- `binary` — compact binary format. It is default. Possible options are:
  - `stream` — write a binary file on the fly, without collecting trace in the memory. It is default.
  - `dump` — write a binary file after the trace collection is finished.
- `text` — human-readable text format. Possible options are:
  - `verbose` — write a text file with detailed information about each trace point.

## Format of an output file
The format of an output file is not described or specified.

Please use function `org.jetbrains.kotlinx.lincheck.tracedata.loadRecordedTrace()`.