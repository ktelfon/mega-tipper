package dev.tipbot.spike

import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Watches the chain for payments against pending invoices, confirms them, and notifies.
 *
 * This is where step 0's spike stops being a spike. The matching rules are unchanged -
 * [TipMatcher] still decides - but the invoices now come from the database instead of the
 * command line, and a match writes back a confirmation instead of printing and exiting.
 *
 * [pollOnce] is a single pass and returns what it confirmed, so the whole worker can be
 * driven deterministically in tests against canned TonAPI responses. No sleeping, no clock,
 * no network in the test path.
 */
class TipPoller(
    private val store: TipStore,
    private val events: EventSource,
    private val notifier: Notifier,
    /** Told privately about every tip, when configured. Null means nobody is. */
    private val ownerChatId: Long? = null,
    private val clock: () -> Long = { Instant.now().epochSecond },
) {

    /** Sends a message to a chat. An interface so a pass can be tested without Telegram. */
    fun interface Notifier {
        fun send(chatId: Long, text: String)
    }

    private val log = LoggerFactory.getLogger(TipPoller::class.java)

    /** @return how many tips this pass confirmed */
    fun pollOnce(): Int {
        val now = clock()

        val swept = store.expireStale(now)
        if (swept > 0) log.info("Expired {} stale invoice(s)", swept)

        val pending = store.pendingTips(now)
        if (pending.isEmpty()) return 0

        // One request per distinct address, not per tip. Two people tipping the same creator
        // at once is the normal case, and it must not double the call rate against a service
        // that rate-limits us.
        var confirmed = 0
        for ((address, tips) in pending.groupBy { it.rawAddress }) {
            when (val result = events.eventsFor(address)) {
                is AccountEvents.RateLimited -> {
                    // Abandon the whole pass rather than walking the remaining addresses into
                    // the same wall. The next cycle's sleep is the back-off.
                    log.warn("Rate limited by TonAPI; ending this pass early")
                    return confirmed
                }

                is AccountEvents.Failed -> {
                    // One creator's lookup failing must not stop the others being checked.
                    log.warn("Event lookup failed for {}: {}", address, result.reason)
                }

                is AccountEvents.Ok -> confirmed += settle(tips, result.json, now)
            }
        }
        return confirmed
    }

    private fun settle(tips: List<Tip>, eventsJson: com.fasterxml.jackson.databind.JsonNode, now: Long): Int {
        // Belt and braces. Two invoices cannot really match one event, because each carries a
        // distinct nonce and TipMatcher compares the comment exactly - but if that ever stopped
        // being true, this keeps one payment from settling two invoices inside a single pass.
        val creditedThisPass = mutableSetOf<String>()
        var confirmed = 0

        // Oldest first, so the invoice that has been waiting longest claims a payment.
        for (tip in tips.sortedBy { it.createdAt }) {
            val match = TipMatcher.findPayment(eventsJson, tip.toInvoice(), creditedThisPass) ?: continue
            creditedThisPass += match.eventId

            // False means the database refused it - already settled, or this event has already
            // paid out another tip. Either way: do not notify, do not deliver.
            if (!store.confirm(tip.nonce, match.eventId, match.senderAddress, now)) {
                log.warn("Refused to credit {} against event {} - already settled", tip.nonce, match.eventId)
                continue
            }

            confirmed++
            log.info("Confirmed {} - {} nanoTON from {}", tip.nonce, match.amountNano, match.senderAddress)
            announce(tip, match)
        }
        return confirmed
    }

    /**
     * Announced **where the tip was asked for**, not to a fixed chat. A tip raised in a group is
     * confirmed in that group, where the owner and everyone else can see it - which is the
     * social proof that makes the next person tip.
     *
     * Telegram refuses to message anyone who has never opened a chat with the bot, so a private
     * message is never the only place a confirmation goes.
     */
    private fun announce(tip: Tip, match: TipMatcher.Match) {
        val amount = TipAmount.format(match.amountNano)
        val told = mutableSetOf<Long>()

        fun tell(chatId: Long, text: String) {
            if (told.add(chatId)) notify(chatId, text)
        }

        tell(tip.originChatId, "Tip received: $amount TON. Thank you!")

        // The tipper, when they tipped from somewhere other than their own chat with the bot.
        tip.tipperChatId?.let { tell(it, "Your tip of $amount TON arrived. Thanks!") }

        // The owner, if they asked to be told and were not already in one of the chats above.
        ownerChatId?.let { tell(it, "You received a tip: $amount TON.\n\nIt is already in your wallet.") }
    }

    /**
     * A tip is confirmed the moment the database says so. If Telegram is down, the money has
     * still moved and the row is still CONFIRMED - so a failed notification is logged and
     * dropped, never retried in a way that could re-run the confirmation.
     */
    private fun notify(chatId: Long, text: String) {
        try {
            notifier.send(chatId, text)
        } catch (e: Exception) {
            log.error("Confirmed the tip but failed to notify {}", chatId, e)
        }
    }

    /** Blocks forever. Intended for a daemon thread that dies with the process. */
    fun runForever(intervalSeconds: Long) {
        log.info("Poller started, checking every {}s", intervalSeconds)
        while (!Thread.currentThread().isInterrupted) {
            try {
                pollOnce()
            } catch (e: Exception) {
                // The worker must outlive any single bad cycle - a dropped connection or a
                // malformed response cannot be allowed to stop every future payment confirming.
                log.error("Poll cycle failed", e)
            }

            try {
                Thread.sleep(intervalSeconds * 1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        log.info("Poller stopped")
    }
}
