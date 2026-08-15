package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import java.nio.file.Files
import kotlin.test.Test

class FilesCreateTempFileTest {
    @Operation
    fun operation(): List<String> = List(10) {
        val tempFile = Files.createTempFile("test-prefix", ".txt")
        require(Files.exists(tempFile)) { "File was not created: $tempFile" }
        tempFile.toString()
    }

    // The test fails with the following error message:
    // "java.lang.IllegalStateException: File operations are not supported in Lincheck"
    @Test
    fun modelChecking() = ModelCheckingOptions().check(this::class)
}
