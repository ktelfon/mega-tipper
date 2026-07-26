package dev.tipbot.spike

import com.fasterxml.jackson.databind.JsonNode

/**
 * Pure matching logic: given a page of TonAPI account events and a pending invoice,
 * decide whether the tip has genuinely been paid.
 *
 * Deliberately paranoid, because a text comment is public plaintext that anybody can
 * attach to any transfer. Live mainnet data shows both duplicate comments and zero-value
 * dusting scams in the wild, so every one of these checks defends against something real:
 *
 *  1. recipient must be the creator's address - not just any transfer in the trace
 *  2. status must be "ok"                     - failed transactions move no money
 *  3. amount must match exactly               - no tolerance, no "at least"
 *  4. comment must match exactly              - case and whitespace sensitive
 *  5. event timestamp inside invoice window   - stops an attacker replaying a historical
 *                                               transfer carrying the same comment
 *  6. is_scam events rejected                 - TonAPI's own spam flag
 *  7. already-credited event ids skipped      - idempotency across polls and restarts
 */
object TipMatcher {

    data class Match(
        val eventId: String,
        val senderAddress: String,
        val amountNano: Long,
        val timestamp: Long,
    )

    /**
     * @param eventsResponse   parsed body of GET /v2/accounts/{id}/events
     * @param invoice          the pending tip request
     * @param creditedEventIds event ids already paid out, for idempotency
     * @return the matching payment, or null if this page contains no genuine payment
     */
    fun findPayment(
        eventsResponse: JsonNode,
        invoice: TipInvoice,
        creditedEventIds: Set<String>,
    ): Match? {
        val events = eventsResponse["events"]?.takeIf { it.isArray } ?: return null

        for (event in events) {
            val eventId = event.path("event_id").asText()

            if (eventId in creditedEventIds) continue
            if (event.path("is_scam").asBoolean(false)) continue
            // An unfinished trace can still change; wait for it to settle.
            if (event.path("in_progress").asBoolean(false)) continue

            val timestamp = event.path("timestamp").asLong(0)
            if (!invoice.isWithinWindow(timestamp)) continue

            val actions = event["actions"]?.takeIf { it.isArray } ?: continue

            for (action in actions) {
                if (action.path("type").asText() != "TonTransfer") continue
                if (action.path("status").asText() != "ok") continue

                val transfer = action["TonTransfer"] ?: continue

                val recipient = transfer.path("recipient").path("address").asText("")
                if (!recipient.equals(invoice.recipientAddress, ignoreCase = true)) continue

                if (transfer.path("comment").asText(null) != invoice.commentNonce) continue

                val amountNano = transfer.path("amount").asLong(-1)
                if (amountNano != invoice.expectedNanoTon) continue

                return Match(
                    eventId = eventId,
                    senderAddress = transfer.path("sender").path("address").asText("unknown"),
                    amountNano = amountNano,
                    timestamp = timestamp,
                )
            }
        }

        return null
    }
}
