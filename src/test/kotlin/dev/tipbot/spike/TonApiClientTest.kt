package dev.tipbot.spike

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonApiClientTest {

    private lateinit var server: HttpServer
    private var responseCode = 200
    private var responseBytes = ByteArray(0)
    private val responseHeaders = mutableMapOf<String, String>()
    private var lastRequestEncoding: String? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastRequestEncoding = exchange.requestHeaders.getFirst("Accept-Encoding")
            responseHeaders.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
            exchange.sendResponseHeaders(responseCode, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.start()
        responseCode = 200
        responseBytes = ByteArray(0)
        responseHeaders.clear()
        lastRequestEncoding = null
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun getBaseUrl() = "http://localhost:${server.address.port}"

    @Test
    fun `test successful non-gzipped parsing`() {
        val json = """{"events":[]}"""
        responseBytes = json.toByteArray(Charsets.UTF_8)
        responseCode = 200

        val client = TonApiClient(getBaseUrl(), null)
        val result = client.eventsFor("some_address")

        assertTrue(result is AccountEvents.Ok)
        assertEquals(0, result.json["events"].size())
        assertEquals("gzip", lastRequestEncoding)
    }

    @Test
    fun `test successful gzipped decompression and parsing`() {
        val json = """{"events":[]}"""
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }

        responseBytes = bos.toByteArray()
        responseCode = 200
        responseHeaders["Content-Encoding"] = "gzip"

        val client = TonApiClient(getBaseUrl(), null)
        val result = client.eventsFor("some_address")

        assertTrue(result is AccountEvents.Ok)
        assertEquals(0, result.json["events"].size())
        assertEquals("gzip", lastRequestEncoding)
    }

    @Test
    fun `test rate limit response`() {
        responseCode = 429

        val client = TonApiClient(getBaseUrl(), null)
        val result = client.eventsFor("some_address")

        assertTrue(result is AccountEvents.RateLimited)
    }

    @Test
    fun `test error status response`() {
        responseCode = 500
        responseBytes = "Server Error".toByteArray(Charsets.UTF_8)

        val client = TonApiClient(getBaseUrl(), null)
        val result = client.eventsFor("some_address")

        assertTrue(result is AccountEvents.Failed)
        assertTrue(result.reason.contains("HTTP 500: Server Error"))
    }
}
