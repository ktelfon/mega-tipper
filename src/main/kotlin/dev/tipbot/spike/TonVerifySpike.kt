package dev.tipbot.spike

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Standalone spike: poll TonAPI for a TonTransfer landing on a wallet and match it
 * against a pending invoice. No Telegram, no DB - just proving the blockchain-side
 * detection loop works before building anything around it.
 *
 * Usage:
 *   ./gradlew run --args="<address> <comment> <amountTon> [timeoutSeconds] [pollSeconds]"
 *
 * Env:
 *   TONAPI_BASE_URL  default https://tonapi.io (use https://testnet.tonapi.io for testnet)
 *   TONAPI_KEY       optional bearer token, raises the free rate limit
 *   TIP_LOOKBACK_SEC how far back the invoice window opens, default 300. Only widen this
 *                    for testing against historical transfers - in production a narrow
 *                    window is what stops an old transfer being replayed as a new tip.
 */
private const val NANO_PER_TON = 1_000_000_000L

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("Usage: <address> <comment> <amountTon> [timeoutSeconds] [pollSeconds]")
        exitProcess(2)
    }

    val testnet = (System.getenv("TONAPI_BASE_URL") ?: "").contains("testnet")

    // Accept any address form the user pastes; compare against TonAPI in raw form only.
    val account = when (val result = AddressNormalizer.normalize(args[0], testnet)) {
        is AddressNormalizer.Result.Ok -> result.raw
        is AddressNormalizer.Result.Rejected -> {
            System.err.println("Bad address: ${result.reason}")
            exitProcess(2)
        }
    }
    if (account != args[0]) {
        println("Normalized ${args[0]} -> $account")
    }

    val comment = args[1]
    val expectedNanoTon = BigDecimal(args[2]).multiply(BigDecimal.valueOf(NANO_PER_TON)).longValueExact()
    val timeoutSeconds = args.getOrNull(3)?.toLong() ?: 300L
    val pollSeconds = args.getOrNull(4)?.toLong() ?: 5L

    val baseUrl = System.getenv("TONAPI_BASE_URL") ?: "https://tonapi.io"
    val apiKey = System.getenv("TONAPI_KEY")

    val lookbackSeconds = System.getenv("TIP_LOOKBACK_SEC")?.toLongOrNull() ?: 300L

    val now = Instant.now().epochSecond
    val invoice = TipInvoice(
        commentNonce = comment,
        recipientAddress = account,
        expectedNanoTon = expectedNanoTon,
        createdAtEpoch = now - lookbackSeconds,
        expiresAtEpoch = now + timeoutSeconds,
    )

    println("Watching $account for comment=\"$comment\" amount=$expectedNanoTon nanoTON")
    println("Base URL: $baseUrl | timeout: ${timeoutSeconds}s | poll: ${pollSeconds}s")

    val client = HttpClient.newHttpClient()
    val mapper = ObjectMapper()
    val credited = mutableSetOf<String>()

    val encoded = URLEncoder.encode(account, StandardCharsets.UTF_8)
    val uri = URI.create("$baseUrl/v2/accounts/$encoded/events?limit=50")
    val deadline = Instant.now().plusSeconds(timeoutSeconds)

    while (Instant.now().isBefore(deadline)) {
        try {
            val request = HttpRequest.newBuilder(uri).GET().apply {
                if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
            }.build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                200 -> {
                    val match = TipMatcher.findPayment(mapper.readTree(response.body()), invoice, credited)
                    if (match != null) {
                        credited += match.eventId
                        println()
                        println("MATCH FOUND")
                        println("  event_id : ${match.eventId}")
                        println("  sender   : ${match.senderAddress}")
                        println("  amount   : ${match.amountNano} nanoTON")
                        println("  time     : ${Instant.ofEpochSecond(match.timestamp)}")
                        return
                    }
                }

                429 -> {
                    println("Rate limited, backing off...")
                    Thread.sleep(maxOf(pollSeconds * 2, 10) * 1000)
                    continue
                }

                else -> System.err.println("Unexpected status ${response.statusCode()}: ${response.body().take(300)}")
            }
        } catch (e: IOException) {
            System.err.println("Request failed: ${e.message}")
        }

        Thread.sleep(pollSeconds * 1000)
        print(".")
        System.out.flush()
    }

    println()
    println("Timed out after ${timeoutSeconds}s without a match.")
    exitProcess(1)
}
