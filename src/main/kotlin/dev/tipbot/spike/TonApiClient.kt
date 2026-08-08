package dev.tipbot.spike

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A page of account events, or the reason there isn't one. */
sealed interface AccountEvents {
    data class Ok(val json: JsonNode) : AccountEvents

    /** Distinct from [Failed] because the caller must back off rather than retry immediately. */
    data object RateLimited : AccountEvents

    data class Failed(val reason: String) : AccountEvents
}

/**
 * Where [TipPoller] gets its events. An interface so the poller can be tested against
 * canned TonAPI responses - the matching rules are what matter, and they should not need
 * a network to exercise.
 */
fun interface EventSource {
    fun eventsFor(rawAddress: String): AccountEvents
}

/**
 * Reads account events from TonAPI.
 *
 * Failures are returned, not thrown. The poller runs forever against a third-party service,
 * so a timeout or a 502 is an expected event in normal operation rather than an error - it
 * must cost one skipped cycle, not the worker thread.
 */
class TonApiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : EventSource {

    private val mapper = ObjectMapper()

    override fun eventsFor(rawAddress: String): AccountEvents {
        val encoded = URLEncoder.encode(rawAddress, StandardCharsets.UTF_8)
        val uri = URI.create("$baseUrl/v2/accounts/$encoded/events?limit=$EVENT_PAGE_SIZE")

        val request = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofSeconds(20))
            .header("Accept-Encoding", "gzip")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val status = response.statusCode()
            val bodyBytes = response.body() ?: ByteArray(0)

            when (status) {
                200 -> {
                    // Check if the server compressed the response using gzip
                    val isGzipped = response.headers()
                        .firstValue("Content-Encoding")
                        .map { it.equals("gzip", ignoreCase = true) }
                        .orElse(false)

                    val inputStream = if (isGzipped) {
                        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bodyBytes))
                    } else {
                        java.io.ByteArrayInputStream(bodyBytes)
                    }

                    // Parse directly from InputStream to avoid materializing the JSON string in memory, reducing GC pressure
                    AccountEvents.Ok(mapper.readTree(inputStream))
                }
                429 -> AccountEvents.RateLimited
                else -> {
                    val errorText = bodyBytes.decodeToString().take(200)
                    AccountEvents.Failed("HTTP $status: $errorText")
                }
            }
        } catch (e: IOException) {
            AccountEvents.Failed(e.message ?: e::class.simpleName ?: "request failed")
        } catch (e: InterruptedException) {
            // Restore the flag so a shutdown that interrupts mid-request still stops the loop.
            Thread.currentThread().interrupt()
            AccountEvents.Failed("interrupted")
        }
    }

    private companion object {
        /**
         * Deep enough that a burst of unrelated activity cannot push a tip off the page
         * between two polls, which would strand a genuine payment as unconfirmed.
         */
        const val EVENT_PAGE_SIZE = 50
    }
}
