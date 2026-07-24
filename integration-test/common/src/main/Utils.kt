import java.io.File
import kotlin.io.path.createTempFile

/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

fun String.escape(): String = this.replace("\\", "\\\\")
fun String.escapeDollar() = replace("$", "\\$")

fun <T> withPermissions(block: (File) -> T): T {
    val permissions = createTempFile("permissions", "txt").toFile()
    return try {
        permissions.writeText(
            """
                grant {
                    permission java.security.AllPermission;
                };
            """.trimIndent()
        )
        block(permissions)
    } finally {
        permissions.delete()
    }
}