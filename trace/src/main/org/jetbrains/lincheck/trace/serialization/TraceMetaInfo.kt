package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.util.Logger
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.management.ManagementFactory

/**
 * Information about conditions in which trace was collected.
 *
 *  - [className] — Name of traced class; can be null if tracing started not from a specific method.
 *  - [methodName] — Name of traced method; can be null if tracing started not from a specific method.
 *  - [startTime] — start time of the trace collection, as returned by [System.currentTimeMillis].
 *  - [endTime] — end time of the trace collection, as returned by [System.currentTimeMillis].
 *  - [systemProperties] — state of system properties ([System.getProperties]) at the beginning of trace collection.
 *  - [environment] — state of system environment ([System.getenv]) at the beginning of trace collection.
 */
@ConsistentCopyVisibility
data class TraceMetaInfo private constructor(
    val jvmArgs: String,
    val agentArgs: String,
    val className: String,
    val methodName: String,
    val startTime: Long,
    val endTime: Long,
    val isDiff: Boolean = false,
    val leftTraceMetaInfo: TraceMetaInfo? = null,
    val rightTraceMetaInfo: TraceMetaInfo? = null
) {
    private val props: MutableMap<String, String> = mutableMapOf()
    private val env: MutableMap<String, String> = mutableMapOf()

    val systemProperties: Map<String, String> get() = props
    val environment: Map<String, String> get() = env

    /**
     * Prints this meta info in human-readable way to given [Appendable].
     */
    fun print(printer: Appendable): Unit = with(printer) {
        appendLine("$CLASS_HEADER$className")
        appendLine("$METHOD_HEADER$methodName")
        appendLine("$START_TIME_HEADER$startTime")
        appendLine("$END_TIME_HEADER$endTime")
        appendLine("$JVM_ARGS_HEADER$jvmArgs")
        appendLine("$AGENT_ARGS_HEADER$agentArgs")
        appendMap(PROPERTIES_HEADER, props)
        appendLine()
        appendMap(ENV_HEADER, env)
    }

    companion object {
        private const val CLASS_HEADER: String = "Class: "
        private const val METHOD_HEADER: String = "Method: "
        private const val START_TIME_HEADER: String = "Start time: "
        private const val END_TIME_HEADER: String = "End time: "
        private const val JVM_ARGS_HEADER: String = "JVM arguments: "
        private const val AGENT_ARGS_HEADER: String = "Agent arguments: "
        private const val PROPERTIES_HEADER: String = "Properties:"
        private const val ENV_HEADER: String = "Environment:"

        /**
         * Creates new object: sets [className] and [methodName] to passed parameters,
         * [startTime] to current time and fetch current system properties and environment.
         */
        fun create(
            agentArgs: String,
            className: String,
            methodName: String,
            startTime: Long,
            endTime: Long
        ): TraceMetaInfo {
            val bean = ManagementFactory.getRuntimeMXBean()
            // Read JVM args
            val jvmArgs = bean.inputArguments.joinToString(" ") { arg -> arg.escapeShell() }

            val meta = TraceMetaInfo(jvmArgs, agentArgs, className, methodName, startTime, endTime)
            with (meta) {
                System.getProperties().forEach {
                    props[it.key as String] = it.value as String
                }
                env.putAll(System.getenv())
            }
            return meta
        }

        fun createDiff(
            leftMetaInfo: TraceMetaInfo?,
            rightMetaInfo: TraceMetaInfo?,
            startTime: Long,
            endTime: Long
        ): TraceMetaInfo {
            val bean = ManagementFactory.getRuntimeMXBean()
            // Read JVM args
            val jvmArgs = bean.inputArguments.joinToString(" ") { arg -> arg.escapeShell() }

            val meta = TraceMetaInfo(
                jvmArgs = jvmArgs,
                agentArgs = "",
                className = "",
                methodName = "",
                startTime = startTime,
                endTime = endTime,
                isDiff = true,
                leftTraceMetaInfo = leftMetaInfo,
                rightTraceMetaInfo = rightMetaInfo
            )
            with (meta) {
                System.getProperties().forEach {
                    props[it.key as String] = it.value as String
                }
                env.putAll(System.getenv())
            }
            return meta
        }

        /**
         * Read meta info in same format as [print] writes.
         */
        fun read(
            input: InputStream,
            isDiff: Boolean = false,
            leftTraceMetaInfo: TraceMetaInfo? = null,
            rightTraceMetaInfo: TraceMetaInfo? = null
        ): TraceMetaInfo? {
            val reader = BufferedReader(InputStreamReader(input))

            val className = reader.readLine(CLASS_HEADER) ?: return null
            val methodName = reader.readLine(METHOD_HEADER) ?: return null
            val startTime = reader.readLong(START_TIME_HEADER) ?: return null
            val endTime = reader.readLong(END_TIME_HEADER) ?: return null
            val jvmArgs = reader.readLine(JVM_ARGS_HEADER) ?: return null
            val agentArgs = reader.readLine(AGENT_ARGS_HEADER) ?: return null

            val meta = TraceMetaInfo(
                jvmArgs = jvmArgs,
                agentArgs = agentArgs,
                className = className,
                methodName = methodName,
                startTime = startTime,
                endTime = endTime,
                isDiff = isDiff,
                leftTraceMetaInfo = leftTraceMetaInfo,
                rightTraceMetaInfo = rightTraceMetaInfo
            )

            if (!reader.readMap(PROPERTIES_HEADER, meta.props)) return null
            if (!reader.readMap(ENV_HEADER, meta.env)) return null

            return meta
        }

        private fun BufferedReader.readLine(prefix: String): String? {
            val line = readLine().ensureValueRead { "Unexpected EOF" } ?: return null
            if (!line.startsWith(prefix)) {
                readError { "Wrong \"$prefix\" line" }
                return null
            }
            return line.substring(prefix.length)
        }

        private fun BufferedReader.readLong(prefix: String): Long? {
            val str = readLine(prefix) ?: return null
            return str.toLongOrNull().ensureValueRead { "Invalid format for \"$prefix\": not a number" }
        }

        private fun BufferedReader.checkHeader(prefix: String): Boolean {
            val str = readLine(prefix) ?: return false
            if (str.isEmpty()) return true
            readError { "Section header \"$prefix\" contains unexpected characters" }
            return false
        }

        private fun BufferedReader.readMap(header: String, map: MutableMap<String, String>): Boolean {
            if (!checkHeader(header)) return false
            while (true) {
                val line = readLine() ?: break // EOF is Ok
                if (line.isEmpty()) break // Empty line is end-of-map, Ok
                if (line[0] != ' ') {
                    readError { "Wrong line in \"$header\" section: must start from space" }
                    return false
                }
                val p = line.parseKV()
                if (p == null) {
                    readError { "Wrong line in \"$header\" section: doesn't contains '='" }
                    return false
                }
                map[p.first] = p.second
            }
            return true
        }

        private fun String.parseKV(): Pair<String, String>? {
            val (idx, key) = unescape(1, '=', false)
            if (idx == length) return null
            val (_, value) = unescape(idx + 1)
            return key to value
        }

        private fun String.unescape(from: Int, upTo: Char? = null, special: Boolean = true): Pair<Int, String> {
            var escape = false
            var idx = from
            val sb = StringBuilder()
            while (idx < length) {
                val c = this[idx]
                if (escape) {
                    if (special) {
                        when (c) {
                            'r' -> sb.append('\r')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            else -> sb.append(c)
                        }
                    } else {
                        sb.append(c)
                    }
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == upTo) {
                    break
                } else {
                    sb.append(c)
                }
                idx++
            }
            return idx to sb.toString()
        }

        private fun readError(lazyMessage: () -> String) {
            Logger.error { "Cannot read trace meta info: ${lazyMessage()}" }
        }

        private fun <T> T?.ensureValueRead(lazyMessage: () -> String): T? {
            if (this != null) return this
            Logger.error { "Cannot read trace meta info: ${lazyMessage()}" }
            return null
        }

        private fun Appendable.appendMap(header: String, map: Map<String, String>) {
            appendLine(header)
            map.keys.sorted().forEach {
                appendLine(" ${it.escapeKey()}=${map[it]?.escapeValue()}")
            }
        }

        private fun String.escapeKey(): String = this
            .replace("\\", "\\\\")
            .replace("=", "\\=")

        private fun String.escapeValue(): String = this
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

        private fun String.escapeShell(): String {
            return if (contains(' ') || contains('*') || contains('?') || contains('[')) {
                "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } else {
                replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
            }
        }
    }
}