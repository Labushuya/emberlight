package io.github.labushuya.emberlight.share.server

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(1024) { (it % 251).toByte() }

    @Test
    fun storesTheFileUnderARandomNameOnly() =
        testApplication {
            val dir = tmp.root
            installUpload(dir, maxBytes = 4096)

            val response = upload(payload, fileName = "../../evil.txt")
            assertEquals(HttpStatusCode.Created, response.status)
            val stored = dir.listFiles().orEmpty().single()
            assertTrue(stored.name.endsWith(".bin"))
            assertFalse(stored.name.contains(".."))
            assertArrayEquals(payload, stored.readBytes())
        }

    @Test
    fun uploadAboveLimitIsRejectedAndLeavesNoFile() =
        testApplication {
            val dir = tmp.root
            installUpload(dir, maxBytes = 10)

            assertEquals(HttpStatusCode.PayloadTooLarge, upload(ByteArray(11) { 1 }).status)
            assertEquals(0, dir.listFiles().orEmpty().size)
        }

    @Test
    fun uploadAtLimitIsAccepted() =
        testApplication {
            installUpload(tmp.root, maxBytes = 10)
            assertEquals(HttpStatusCode.Created, upload(ByteArray(10) { 1 }).status)
        }

    @Test
    fun oversizedContentLengthIsRejectedBeforeReading() =
        testApplication {
            val dir = tmp.root
            installUpload(dir, maxBytes = 10)

            // 100 KiB liegen über 10 Byte plus Multipart-Rahmen: abgelehnt, bevor etwas geschrieben wird.
            assertEquals(HttpStatusCode.PayloadTooLarge, upload(ByteArray(100 * 1024) { 1 }).status)
            assertEquals(0, dir.listFiles().orEmpty().size)
        }

    @Test
    fun uploadAboveFiftyMebibytesIsAccepted() =
        testApplication {
            val dir = tmp.root
            installUpload(dir, maxBytes = MAX_TEST_BYTES)

            // Ktor begrenzt Dateiteile standardmäßig auf 50 MiB. Das Limit des Aufrufers muss darüber gelten.
            val bytes = ByteArray(FIFTY_MIB + 1024) { (it % 251).toByte() }
            assertEquals(HttpStatusCode.Created, upload(bytes).status)
            assertEquals(
                bytes.size.toLong(),
                dir
                    .listFiles()
                    .orEmpty()
                    .single()
                    .length(),
            )
        }

    @Test
    fun formWithoutFileIsBadRequest() =
        testApplication {
            installUpload(tmp.root, maxBytes = 4096)
            val response =
                client.submitFormWithBinaryData(
                    url = "/upload",
                    formData = formData { append("note", "ohne Datei") },
                )
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    private fun ApplicationTestBuilder.installUpload(
        dir: File,
        maxBytes: Long,
    ) {
        application {
            routing { uploadRoute(dir, maxBytes) }
        }
    }

    private suspend fun ApplicationTestBuilder.upload(
        bytes: ByteArray,
        fileName: String = "upload.bin",
    ): HttpResponse =
        client.submitFormWithBinaryData(
            url = "/upload",
            formData =
                formData {
                    append(
                        "file",
                        bytes,
                        Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"") },
                    )
                },
        )

    private companion object {
        const val FIFTY_MIB = 50 * 1024 * 1024
        const val MAX_TEST_BYTES = 2L * 1024 * 1024 * 1024
    }
}

/** Test-Route: nimmt einen Upload entgegen und antwortet so, wie eine echte Route es täte. */
private fun Route.uploadRoute(
    dir: File,
    maxBytes: Long,
) {
    post("/upload") {
        when (call.receiveSingleFile(dir, maxBytes)) {
            is UploadResult.Stored -> call.respondText("stored", status = HttpStatusCode.Created)
            UploadResult.NoFile -> call.respond(HttpStatusCode.BadRequest)
            UploadResult.TooLarge -> call.respond(HttpStatusCode.PayloadTooLarge)
        }
    }
}
