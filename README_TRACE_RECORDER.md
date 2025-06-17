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
java -javaagent:"${pathToLincheckFarJar}=${className}:${methodName}:${output}" -Dlincheck.traceRecorderMode=true ...
```

In `gradle.build.kts` it will be something like this:


```kotlin
    named("jvmTest", Test::class) {
        val pathToLincheckFarJar = "/some/path/where/lincheck-fat.jar"
        val className = "dotted.class.name.to.Trace"
        val methodName = "methodNameWithoutSignature"
        val output = "path/to/output.bin"

        jvmArgs("-javaagent:${pathToLincheckFarJar}=$className,$methodName,$output")
        jvmArgs("-Dlincheck.traceRecorderMode=true")
    }
```

## Format of an output file
Trace Recorder uses Kotlin ProtoBuf serialization with custom framing to avoid the 2 GiB limitation of current
Kotlin's implementation.

It isn't suitable for parsing without using Lincheck classes from package `org.jetbrains.kotlinx.lincheck.tracedata`.

All multibyte values not included in ProtoBuf data are stored in network (big endian) byte order.

Format looks like this:

| offset | size | description                                                                                                                             |
|--------|------|-----------------------------------------------------------------------------------------------------------------------------------------|
| 0      | 8    | Magic `Long` value `0x706e547124ee5f70L`.                                                                                               |
| 8      | 8    | Version of format, `Long` value. Now must be `1`                                                                                        |
| 16     | ?    | Content of method descriptors cache. Content is instances of `org.jetbrains.kotlinx.lincheck.tracedata.MethodDescriptor` class.         |
| ?      | ?    | Content of field descriptors cache. Content is instances of `org.jetbrains.kotlinx.lincheck.tracedata.FieldDescriptor` class.           |
| ?      | ?    | Content of local variables descriptors cache. Content is instances of `org.jetbrains.kotlinx.lincheck.tracedata.FieldDescriptor` class. |
| ?      | 4    | Number of traced threads.                                                                                                               |
| ?      | ?    | Each thread's root call.                                                                                                                |

### Format of caches with descriptors.
Each cache is saved as 4 byte size of cache (number of elements) and ProtoBuf-serialized elements in order.
Each element is serialized as a separate ProtoBuf message.

### Format of a per-thread call tree.
Each trace point is stored as one separate ProtoBuf message. All tracepoints but `TRMethodCallTracePoint` doesn't have
any additional data.

`TRMethodCallTracePoint` stores list of children events after its ProtoBuf serialization, which doesn't include this list.

List of events is stored as 4 byte size and ProtoBuf serializations of all children (which can include other
`TRMethodCallTracePoint`) instances.

### Format of ProtoBuf message.
Each ProtoBuf message is stored as 4 byte size of message and message itself.

## Sample code of trace reading.

Look at `org.jetbrains.kotlinx.lincheck.strategy.tracerecorder.TraceCollectingEventTracker.exampleTraceReading()`.