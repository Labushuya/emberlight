package io.github.labushuya.emberlight.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceIsolationGuardTest {
    private val tokens = listOf("MediaStore", "READ_MEDIA_", "getExternalFilesDir")

    @Test
    fun forbiddenTokenIsReportedWithFileAndLine() {
        val files = mapOf("src/main/A.kt" to listOf("class A", "val x = MediaStore.Images"))

        assertEquals(
            listOf("src/main/A.kt:2: MediaStore"),
            SourceIsolationGuard.findViolations(files, tokens),
        )
    }

    @Test
    fun manifestPermissionIsReported() {
        val files =
            mapOf(
                "src/main/AndroidManifest.xml" to
                    listOf("<uses-permission android:name=\"android.permission.READ_MEDIA_IMAGES\" />"),
            )

        assertEquals(
            listOf("src/main/AndroidManifest.xml:1: READ_MEDIA_"),
            SourceIsolationGuard.findViolations(files, tokens),
        )
    }

    @Test
    fun cleanSourcesReportNothing() {
        val files = mapOf("src/main/A.kt" to listOf("fun serve() = filesDir.resolve(\"space\")"))

        assertTrue(SourceIsolationGuard.findViolations(files, tokens).isEmpty())
    }

    @Test
    fun severalTokensOnOneLineAreReportedOnce() {
        val files = mapOf("src/main/A.kt" to listOf("MediaStore and getExternalFilesDir and MediaStore"))

        assertEquals(
            listOf("src/main/A.kt:1: MediaStore", "src/main/A.kt:1: getExternalFilesDir"),
            SourceIsolationGuard.findViolations(files, tokens),
        )
    }

    @Test
    fun resultIsSortedByFileThenLine() {
        val files =
            mapOf(
                "src/b.kt" to listOf("MediaStore"),
                "src/a.kt" to listOf("ok", "MediaStore"),
            )

        assertEquals(
            listOf("src/a.kt:2: MediaStore", "src/b.kt:1: MediaStore"),
            SourceIsolationGuard.findViolations(files, tokens),
        )
    }
}
