package dev.tipbot.spike

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first payment ever made through this bot, captured verbatim from TonAPI.
 *
 * Everything else in the suite asserts against JSON we wrote ourselves, which only proves the
 * matcher agrees with our idea of the shape. This is the shape mainnet actually produced: a
 * 0.5 TON tip, issued 2026-08-02, confirmed 27 seconds after the invoice was created.
 *
 * Only the wallet address is changed - swapped for the test address used elsewhere, so a real
 * person's wallet is not published in a public repository next to their name. Every other field,
 * including the two-action structure and the field ordering, is exactly as returned.
 */
class RealMainnetPaymentTest {

    private val events = ObjectMapper().readTree(
        """
        {"events":[{
          "event_id": "27db08a5b452d40c342d070d1f5183e5c0a3099851e819ba42c9fd7a51bc5fe8",
          "account": {"address": "$RAW", "is_scam": false, "is_wallet": true},
          "timestamp": 1785697200,
          "actions": [
            {
              "type": "TonTransfer",
              "status": "ok",
              "TonTransfer": {
                "sender": {"address": "$RAW", "is_scam": false, "is_wallet": true},
                "recipient": {"address": "$RAW", "is_scam": false, "is_wallet": true},
                "amount": 500000000,
                "comment": "$NONCE"
              },
              "simple_preview": {
                "name": "Gram Transfer", "description": "Transferring 0.5 Gram",
                "value": "0.5 Gram", "accounts": []
              },
              "base_transactions": ["f332ce9ddac9da069e3e4d705d845d542587a6d6c4481b3e3d4f53025c348896"]
            },
            {
              "type": "ContractDeploy",
              "status": "ok",
              "ContractDeploy": {"address": "$RAW", "interfaces": ["wallet_v5r1"]},
              "simple_preview": {
                "name": "Contract Deploy",
                "description": "Deploying a contract with interfaces wallet_v5r1", "accounts": []
              },
              "base_transactions": ["27db08a5b452d40c342d070d1f5183e5c0a3099851e819ba42c9fd7a51bc5fe8"]
            }
          ],
          "is_scam": false, "lt": 94191626000001, "in_progress": false,
          "extra": -1601339, "progress": 1,
          "ext_msg_hash": "f33802f4880131c906414d8f521ef35315ae27a86c360a4bd42948e00f0b336f"
        }]}
        """.trimIndent()
    )

    private fun invoice(
        nonce: String = NONCE,
        recipient: String = RAW,
        amountNano: Long = 500_000_000L,
        createdAt: Long = INVOICE_CREATED,
    ) = TipInvoice(nonce, recipient, amountNano, createdAt, createdAt + 900)

    @Test
    fun `the payment mainnet actually produced is matched`() {
        val match = TipMatcher.findPayment(events, invoice(), emptySet())

        assertNotNull(match)
        assertEquals("27db08a5b452d40c342d070d1f5183e5c0a3099851e819ba42c9fd7a51bc5fe8", match.eventId)
        assertEquals(500_000_000L, match.amountNano)
        assertEquals(1785697200L, match.timestamp)
    }

    @Test
    fun `the transfer is found even though the event carries a second action`() {
        // The real event contains both a TonTransfer and a ContractDeploy, because the wallet
        // had never sent a transaction before and was deployed by this very payment. A matcher
        // that assumed one action per event, or read actions[0] blindly, would work by luck.
        val actions = events["events"][0]["actions"]
        assertEquals(2, actions.size())
        assertEquals("ContractDeploy", actions[1]["type"].asText())

        assertNotNull(TipMatcher.findPayment(events, invoice(), emptySet()))
    }

    @Test
    fun `a wallet deployed by its first payment is exactly why links are non-bounceable`() {
        // ContractDeploy in this event proves the receiving wallet was not yet deployed. A
        // bounceable transfer would have been returned to the sender minus fees.
        val deploy = events["events"][0]["actions"][1]

        assertEquals("wallet_v5r1", deploy["ContractDeploy"]["interfaces"][0].asText())
        assertTrue(AddressNormalizer.toUserFriendly(RAW).startsWith("UQ"), "payment links must be non-bounceable")
    }

    @Test
    fun `sender and recipient being the same wallet does not stop it matching`() {
        // This was a self-test tip, and TonAPI reports sender == recipient. The matcher never
        // looks at the sender, which is what makes that work.
        val transfer = events["events"][0]["actions"][0]["TonTransfer"]

        assertEquals(
            transfer["sender"]["address"].asText(),
            transfer["recipient"]["address"].asText(),
        )
        assertNotNull(TipMatcher.findPayment(events, invoice(), emptySet()))
    }

    @Test
    fun `against the real event, every guard still refuses what it should`() {
        assertNull(TipMatcher.findPayment(events, invoice(nonce = "tip_0000000000000000"), emptySet()), "wrong comment")
        assertNull(TipMatcher.findPayment(events, invoice(amountNano = 499_999_999L), emptySet()), "wrong amount")
        assertNull(TipMatcher.findPayment(events, invoice(recipient = OTHER_RAW), emptySet()), "wrong recipient")
        assertNull(
            TipMatcher.findPayment(events, invoice(createdAt = 1785697201), emptySet()),
            "an invoice created after the transfer must not claim it",
        )
        assertNull(
            TipMatcher.findPayment(events, invoice(), setOf("27db08a5b452d40c342d070d1f5183e5c0a3099851e819ba42c9fd7a51bc5fe8")),
            "already credited",
        )
    }

    @Test
    fun `the observed round trip fits comfortably inside the invoice window`() {
        // Invoice issued at 1785697181, paid at 1785697200, confirmed at 1785697208: 19 seconds
        // to pay and 8 more to notice. The 15 minute window is not tight.
        val paidAfter = 1785697200L - INVOICE_CREATED
        val confirmedAfter = 1785697208L - 1785697200L

        assertEquals(19L, paidAfter)
        assertEquals(8L, confirmedAfter, "one poll interval, as configured")
        assertTrue(invoice().isWithinWindow(1785697200L))
    }

    private companion object {
        const val NONCE = "tip_8f09d9d7538bdae0"
        /** The owner's real address, replaced with the suite's test address. */
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        const val OTHER_RAW = "0:97264395bd65a255a429b11326c84128b7d70ffed7949abae3036d506ba38621"
        const val INVOICE_CREATED = 1785697181L
    }
}
