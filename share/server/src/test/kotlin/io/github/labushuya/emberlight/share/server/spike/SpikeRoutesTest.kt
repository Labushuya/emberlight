package io.github.labushuya.emberlight.share.server.spike

import io.github.labushuya.emberlight.share.server.AccessToken
import io.github.labushuya.emberlight.share.server.installEmberlightModule
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpikeRoutesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val token = AccessToken.of("testtoken-0123456789")
    private val auth = "Bearer testtoken-0123456789"
    private val payload = ByteArray(1024) { (it % 251).toByte() }

    @Test
    fun uploadStoresTheFileAndReturnsItsId() =
        testApplication {
            val dir = tmp.root
            application { installEmberlightModule(token, spikeRoutes(dir)) }

            val response = upload(payload)
            assertEquals(HttpStatusCode.Created, response.status)
            val id = idFrom(response.bodyAsText())
            val stored = File(dir, "$id.bin")
            assertTrue(stored.isFile)
            assertArrayEquals(payload, stored.readBytes())
        }

    @Test
    fun clientFileNameIsNeverUsedOnDisk() =
        testApplication {
            val dir = tmp.root
            application { installEmberlightModule(token, spikeRoutes(dir)) }

            upload(payload, fileName = "../../evil.txt")
            val names = dir.listFiles().orEmpty().map { it.name }
            assertEquals(1, names.size)
            assertTrue(names.single().endsWith(".bin"))
            assertFalse(names.single().contains(".."))
        }

    @Test
    fun downloadReturnsTheWholeFile() =
        testApplication {
            application { installEmberlightModule(token, spikeRoutes(tmp.root)) }
            val id = idFrom(upload(payload).bodyAsText())

            val response = client.get("/spike/files/$id") { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertArrayEquals(payload, response.bodyAsBytes())
        }

    @Test
    fun rangeRequestReturnsPartialContent() =
        testApplication {
            application { installEmberlightModule(token, spikeRoutes(tmp.root)) }
            val id = idFrom(upload(payload).bodyAsText())

            val response =
                client.get("/spike/files/$id") {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.Range, "bytes=0-1023")
                }
            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertEquals("1024", response.headers[HttpHeaders.ContentLength])
            assertArrayEquals(payload.copyOfRange(0, 1024), response.bodyAsBytes())
        }

    @Test
    fun openEndedRangeReturnsTheRest() =
        testApplication {
            application { installEmberlightModule(token, spikeRoutes(tmp.root)) }
            val id = idFrom(upload(payload).bodyAsText())

            val response =
                client.get("/spike/files/$id") {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.Range, "bytes=1000-")
                }
            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertArrayEquals(payload.copyOfRange(1000, 1024), response.bodyAsBytes())
        }

    @Test
    fun unknownOrMalformedIdsAreNotFound() =
        testApplication {
            application { installEmberlightModule(token, spikeRoutes(tmp.root)) }
            val unknown =
                client.get("/spike/files/00000000-0000-0000-0000-000000000000") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            val traversal =
                client.get(
                    "/spike/files/..%2F..%2Fetc%2Fpasswd",
                ) { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.NotFound, traversal.status)
        }

    @Test
    fun uploadAboveLimitIsRejectedAndLeavesNoFile() =
        testApplication {
            val dir = tmp.root
            application { installEmberlightModule(token, spikeRoutes(dir, maxUploadBytes = 10)) }

            val response = upload(ByteArray(11) { 1 })
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(0, dir.listFiles().orEmpty().size)
        }

    @Test
    fun uploadAtLimitIsAccepted() =
        testApplication {
            application { installEmberlightModule(token, spikeRoutes(tmp.root, maxUploadBytes = 10)) }
            assertEquals(HttpStatusCode.Created, upload(ByteArray(10) { 1 }).status)
        }

    @Test
    fun oversizedContentLengthIsRejectedBeforeReading() =
        testApplication {
            val dir = tmp.root
            application { installEmberlightModule(token, spikeRoutes(dir, maxUploadBytes = 10)) }

            // 100 KiB liegen über 10 Byte plus 64 KiB Multipart-Rahmen: abgelehnt, bevor etwas geschrieben wird.
            val response = upload(ByteArray(100 * 1024) { 1 })
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(0, dir.listFiles().orEmpty().size)
        }

    @Test
    fun uploadWithoutFileIsBadRequest() =
        testApplication {
            application { installEmberlightModule(token, spikeRoutes(tmp.root)) }
            val response =
                client.submitFormWithBinaryData(
                    url = "/spike/upload",
                    formData = formData { append("note", "ohne Datei") },
                ) { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun progressReportsTheFinalSize() =
        testApplication {
            val reported = mutableListOf<Long>()
            application { installEmberlightModule(token, spikeRoutes(tmp.root) { reported += it }) }
            upload(payload)
            assertEquals(listOf(payload.size.toLong()), reported)
        }

    @Test
    fun spikeRoutesStillNeedTheToken() =
        testApplication {
            application { installEmberlightModule(token, spikeRoutes(tmp.root)) }
            val response =
                client.submitFormWithBinaryData(
                    url = "/spike/upload",
                    formData = formData { append("file", payload, Headers.Empty) },
                )
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.upload(
        bytes: ByteArray,
        fileName: String = "upload.bin",
    ): HttpResponse =
        client.submitFormWithBinaryData(
            url = "/spike/upload",
            formData =
                formData {
                    append(
                        "file",
                        bytes,
                        Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"") },
                    )
                },
        ) { header(HttpHeaders.Authorization, auth) }

    private fun idFrom(json: String): String =
        Regex(""""id":"([^"]+)"""").find(json)?.groupValues?.get(1) ?: error("Keine ID in der Antwort.")
}
