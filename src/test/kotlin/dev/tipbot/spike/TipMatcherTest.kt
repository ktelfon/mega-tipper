package dev.tipbot.spike

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Adversarial cases for the tip matcher, using mocked TonAPI payloads shaped exactly
 * like the live responses verified against tonapi.io.
 */
class TipMatcherTest {

    private val mapper = ObjectMapper()

    private val invoice = TipInvoice(
        commentNonce = NONCE,
        recipientAddress = CREATOR,
        expectedNanoTon = ONE_TON,
        createdAtEpoch = CREATED,
        expiresAtEpoch = EXPIRES,
    )

    /** Builds a TonAPI-shaped events page; every field defaults to the happy path. */
    private fun events(
        eventId: String,
        timestamp: Long = CREATED + 60,
        recipient: String = CREATOR,
        comment: String? = NONCE,
        amountNano: Long = ONE_TON,
        status: String = "ok",
        isScam: Boolean = false,
        inProgress: Boolean = false,
    ): JsonNode = mapper.readTree(
        """
        {"events":[{
          "event_id": "$eventId",
          "account": {"address": "$CREATOR", "is_scam": false, "is_wallet": true},
          "timestamp": $timestamp,
          "actions": [{
            "type": "TonTransfer",
            "status": "$status",
            "TonTransfer": {
              "sender":    {"address": "$TIPPER", "is_scam": false, "is_wallet": true},
              "recipient": {"address": "$recipient", "is_scam": false, "is_wallet": true},
              "amount": $amountNano,
              "comment": ${comment?.let { "\"$it\"" } ?: "null"}
            }
          }],
          "is_scam": $isScam,
          "lt": 68254314000001,
          "in_progress": $inProgress,
          "extra": 0,
          "progress": 1
        }], "next_from": 0}
        """.trimIndent()
    )

    @Test
    fun `matches a genuine payment`() {
        val match = TipMatcher.findPayment(events("evt_good"), invoice, emptySet())

        assertNotNull(match, "a correct payment should match")
        assertEquals("evt_good", match.eventId)
        assertEquals(TIPPER, match.senderAddress)
        assertEquals(ONE_TON, match.amountNano)
    }

    @Test
    fun `rejects replay of an older transfer carrying the same comment`() {
        // The attack: a transfer with this exact comment already existed on-chain
        // *before* the invoice was issued. Without a time window it would credit.
        val historical = events("evt_old", timestamp = CREATED - 3_600)

        assertNull(
            TipMatcher.findPayment(historical, invoice, emptySet()),
            "a transfer predating the invoice must not be credited",
        )
    }

    @Test
    fun `rejects payment arriving after expiry`() {
        assertNull(
            TipMatcher.findPayment(events("evt_late", timestamp = EXPIRES + 1), invoice, emptySet()),
            "a transfer after the expiry window must not be credited",
        )
    }

    @Test
    fun `rejects wrong amount even by one nanoton`() {
        assertNull(
            TipMatcher.findPayment(events("evt_short", amountNano = ONE_TON - 1), invoice, emptySet()),
            "underpayment by 1 nanoTON must not be credited",
        )
    }

    @Test
    fun `rejects transfer to a different recipient`() {
        // Same comment, right amount, but the money went somewhere else entirely.
        assertNull(
            TipMatcher.findPayment(events("evt_elsewhere", recipient = TIPPER), invoice, emptySet()),
            "a transfer to another address must not be credited",
        )
    }

    @Test
    fun `rejects failed transaction`() {
        assertNull(
            TipMatcher.findPayment(events("evt_failed", status = "failed"), invoice, emptySet()),
            "a failed transaction moves no money and must not be credited",
        )
    }

    @Test
    fun `rejects scam flagged event`() {
        // Mirrors the real zero-value "Claim your 1,000 TON airdrop" dusting seen on mainnet.
        assertNull(
            TipMatcher.findPayment(events("evt_scam", isScam = true), invoice, emptySet()),
            "scam-flagged events must not be credited",
        )
    }

    @Test
    fun `skips unsettled trace`() {
        assertNull(
            TipMatcher.findPayment(events("evt_pending", inProgress = true), invoice, emptySet()),
            "an in-progress trace can still change and must not be credited yet",
        )
    }

    @Test
    fun `comment match is exact`() {
        assertNull(
            TipMatcher.findPayment(events("evt_padded", comment = " $NONCE "), invoice, emptySet()),
            "whitespace-padded comment must not match",
        )
        assertNull(
            TipMatcher.findPayment(events("evt_cased", comment = NONCE.uppercase()), invoice, emptySet()),
            "differently-cased comment must not match",
        )
    }

    @Test
    fun `tolerates transfer with no comment`() {
        assertNull(
            TipMatcher.findPayment(events("evt_nocomment", comment = null), invoice, emptySet()),
            "a plain transfer with no comment must not match, and must not throw",
        )
    }

    @Test
    fun `is idempotent for an already credited event`() {
        // The poller sees the same event on every cycle; it must only ever pay out once.
        assertNull(
            TipMatcher.findPayment(events("evt_good"), invoice, setOf("evt_good")),
            "an already-credited event must not be credited a second time",
        )
    }

    @Test
    fun `handles empty and malformed pages`() {
        assertNull(
            TipMatcher.findPayment(mapper.readTree("""{"events":[],"next_from":0}"""), invoice, emptySet()),
        )
        assertNull(
            TipMatcher.findPayment(mapper.readTree("{}"), invoice, emptySet()),
            "a response with no events array must not throw",
        )
    }

    private companion object {
        const val CREATOR = "0:97264395bd65a255a429b11326c84128b7d70ffed7949abae3036d506ba38621"
        const val TIPPER = "0:dffbaf7d8a18f8d1e0316e7560a2aee27f199c6b527c47482b671601ded9e2c7"
        const val NONCE = "tip_9f3a1c7b"
        const val ONE_TON = 1_000_000_000L
        const val CREATED = 1_774_200_000L
        const val EXPIRES = 1_774_200_900L // 15 minute window
    }
}
