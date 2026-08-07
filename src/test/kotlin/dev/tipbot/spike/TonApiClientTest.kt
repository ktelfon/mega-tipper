package dev.tipbot.spike

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TonApiClientTest {

    private lateinit var server: HttpServer
    private var lastHeaders: Map<String, List<String>> = emptyMap()
    private var responseBody: String = ""
    private var responseGzipped = false
    private var responseStatus = 200

    @BeforeTest
    fun setUp() {
        // Ephemeral port (0) lets the OS assign a free port automatically
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            lastHeaders = exchange.requestHeaders

            val responseBytes = if (responseGzipped) {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { gzos ->
                    gzos.write(responseBody.toByteArray(Charsets.UTF_8))
                }
                bos.toByteArray()
            } else {
                responseBody.toByteArray(Charsets.UTF_8)
            }

            if (responseGzipped) {
                exchange.responseHeaders.set("Content-Encoding", "gzip")
            }
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, responseBytes.size.toLong())
            exchange.responseBody.write(responseBytes)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `requests and decompresses gzip payload successfully`() {
        responseBody = """{"events": [{"event_id": "test_id"}]}"""
        responseGzipped = true
        responseStatus = 200

        val port = server.address.port
        val client = TonApiClient("http://localhost:$port", "test-api-key")
        val result = client.eventsFor("some-address")

        // 1. Verify we requested GZIP
        val acceptEncoding = lastHeaders["Accept-encoding"] ?: lastHeaders["Accept-Encoding"]
        assertNotNull(acceptEncoding)
        assertTrue(acceptEncoding.any { it.contains("gzip") })

        // 2. Verify we parsed the JSON correctly
        assertTrue(result is AccountEvents.Ok)
        val json = (result as AccountEvents.Ok).json
        assertEquals("test_id", json["events"][0]["event_id"].asText())
    }

    @Test
    fun `handles uncompressed response successfully`() {
        responseBody = """{"events": [{"event_id": "plain_id"}]}"""
        responseGzipped = false
        responseStatus = 200

        val port = server.address.port
        val client = TonApiClient("http://localhost:$port", null)
        val result = client.eventsFor("some-address")

        assertTrue(result is AccountEvents.Ok)
        val json = (result as AccountEvents.Ok).json
        assertEquals("plain_id", json["events"][0]["event_id"].asText())
    }

    @Test
    fun `handles failure status correctly`() {
        responseBody = "Internal Server Error"
        responseGzipped = false
        responseStatus = 500

        val port = server.address.port
        val client = TonApiClient("http://localhost:$port", null)
        val result = client.eventsFor("some-address")

        assertTrue(result is AccountEvents.Failed)
        assertTrue((result as AccountEvents.Failed).reason.contains("HTTP 500"))
        assertTrue(result.reason.contains("Internal Server Error"))
    }
}
