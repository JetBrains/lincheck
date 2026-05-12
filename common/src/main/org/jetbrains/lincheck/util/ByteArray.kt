package org.jetbrains.lincheck.util

/**
 * Renders this byte array as a length annotation followed by an uppercase hex preview
 * of its first [prefixBytes] bytes.
 *
 * Example: `16 bytes [CA FE BA BE 00 00 00 32 ...]`.
 */
fun ByteArray.toHexPreview(prefixBytes: Int): String {
    val prefix = take(prefixBytes).joinToString(" ") { "%02X".format(it) }
    val ellipsis = if (size > prefixBytes) " ..." else ""
    return "$size bytes [$prefix$ellipsis]"
}