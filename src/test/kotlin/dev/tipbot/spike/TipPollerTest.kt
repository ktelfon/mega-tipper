package dev.tipbot.spike

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The poller driven a pass at a time against canned TonAPI responses. No network and no
 * sleeping, so the settlement rules - which decide whether money is treated as received -
 * are exercised deterministically.
 */
class TipPollerTest {

    private val dir = Files.createTempDirectory("poller-test")
    private lateinit var dataSource: DataSource
    private lateinit var store: TipStore

    /** chatId to message, in send order. */
    private val sent = mutableListOf<Pair<Long, String>>()

    private var responses = mutableMapOf<String, AccountEvents>()
    private var lookups = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        dataSource = Database.connect("jdbc:sqlite:${dir.resolve("test.db")}")
        store = TipStore(dataSource)
    }

    @AfterTest
    fun tearDown() {
        (dataSource as? com.zaxxer.hikari.HikariDataSource)?.close()
        dir.toFile().deleteRecursively()
    }

    private fun poller(now: Long = NOW, ownerChatId: Long? = null) = TipPoller(
        store = store,
        events = { address ->
            lookups += address
            responses[address] ?: AccountEvents.Ok(ObjectMapper().readTree("""{"events":[]}"""))
        },
        notifier = { chatId, text -> sent += chatId to text },
        ownerChatId = ownerChatId,
        clock = { now },
    )

    private fun givenEvents(address: String, json: String) {
        responses[address] = AccountEvents.Ok(ObjectMapper().readTree(json))
    }

    /** A well-formed TonAPI event carrying a payment for [nonce]. */
    private fun payment(
        nonce: String,
        amountNano: Long = ONE_TON,
        recipient: String = RAW,
        eventId: String = "event_${nonce}",
        timestamp: Long = NOW,
        isScam: Boolean = false,
        inProgress: Boolean = false,
        status: String = "ok",
    ) = """
        {"events":[{
          "event_id":"$eventId","timestamp":$timestamp,
          "is_scam":$isScam,"in_progress":$inProgress,
          "actions":[{"type":"TonTransfer","status":"$status","TonTransfer":{
            "sender":{"address":"$SENDER"},
            "recipient":{"address":"$recipient"},
            "amount":$amountNano,"comment":"$nonce"
          }}]
        }]}
    """.trimIndent()

    private fun pendingTip(origin: Long = GROUP, tipper: Long? = TIPPER, amount: Long = ONE_TON): Tip =
        store.createTip(origin, tipper, RAW, amount, NOW)

    @Test
    fun `a matching payment confirms the tip and records who paid`() {
        val tip = pendingTip()
        givenEvents(RAW, payment(tip.nonce))

        assertEquals(1, poller().pollOnce())

        val settled = store.findTip(tip.nonce)!!
        assertEquals(TipStatus.CONFIRMED, settled.status)
        assertEquals("event_${tip.nonce}", settled.eventId)
        assertEquals(SENDER, settled.senderAddress)
    }

    @Test
    fun `the confirmation lands where the tip was asked for`() {
        // Social proof: a tip raised in a group is announced in that group, not hidden in a DM.
        val tip = pendingTip(origin = GROUP, tipper = TIPPER)
        givenEvents(RAW, payment(tip.nonce))

        poller().pollOnce()

        assertEquals(setOf(GROUP, TIPPER), sent.map { it.first }.toSet())
        assertTrue(sent.all { it.second.contains("1 TON") }, sent.toString())
    }

    @Test
    fun `a tip raised in a private chat tells that person once, not twice`() {
        val tip = pendingTip(origin = TIPPER, tipper = TIPPER)
        givenEvents(RAW, payment(tip.nonce))

        poller().pollOnce()

        assertEquals(1, sent.size, "one chat, one message: $sent")
    }

    @Test
    fun `the owner is told privately when they asked to be`() {
        val tip = pendingTip(origin = GROUP, tipper = TIPPER)
        givenEvents(RAW, payment(tip.nonce))

        poller(ownerChatId = OWNER).pollOnce()

        assertTrue(sent.any { it.first == OWNER }, sent.toString())
    }

    @Test
    fun `the owner is not told twice when the tip was raised in their own chat`() {
        val tip = pendingTip(origin = OWNER, tipper = OWNER)
        givenEvents(RAW, payment(tip.nonce))

        poller(ownerChatId = OWNER).pollOnce()

        assertEquals(1, sent.size, "same chat three ways, one message: $sent")
    }

    @Test
    fun `polling again after confirmation does not pay out or notify twice`() {
        val tip = pendingTip()
        givenEvents(RAW, payment(tip.nonce))

        assertEquals(1, poller().pollOnce())
        assertEquals(0, poller().pollOnce(), "a confirmed tip is no longer pending")
        assertEquals(2, sent.size, "the second pass must announce nothing")
    }

    @Test
    fun `a payment for a different nonce is ignored`() {
        val tip = pendingTip()
        givenEvents(RAW, payment("tip_0000000000000000"))

        assertEquals(0, poller().pollOnce())
        assertEquals(TipStatus.PENDING, store.findTip(tip.nonce)!!.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the wrong amount is not close enough`() {
        val tip = pendingTip(amount = ONE_TON)
        givenEvents(RAW, payment(tip.nonce, amountNano = ONE_TON - 1))

        assertEquals(0, poller().pollOnce())
        assertEquals(TipStatus.PENDING, store.findTip(tip.nonce)!!.status)
    }

    @Test
    fun `a flagged or unsettled or failed event never confirms a tip`() {
        val scam = pendingTip()
        givenEvents(RAW, payment(scam.nonce, isScam = true))
        assertEquals(0, poller().pollOnce())

        givenEvents(RAW, payment(scam.nonce, inProgress = true))
        assertEquals(0, poller().pollOnce())

        givenEvents(RAW, payment(scam.nonce, status = "failed"))
        assertEquals(0, poller().pollOnce())

        assertEquals(TipStatus.PENDING, store.findTip(scam.nonce)!!.status)
    }

    @Test
    fun `a payment that landed on someone else's address is not credited`() {
        val tip = pendingTip()
        givenEvents(RAW, payment(tip.nonce, recipient = OTHER_RAW))

        assertEquals(0, poller().pollOnce())
        assertEquals(TipStatus.PENDING, store.findTip(tip.nonce)!!.status)
    }

    @Test
    fun `a transfer from before the invoice existed cannot be replayed`() {
        val tip = pendingTip()
        givenEvents(RAW, payment(tip.nonce, timestamp = NOW - 86_400))

        assertEquals(0, poller().pollOnce())
        assertEquals(TipStatus.PENDING, store.findTip(tip.nonce)!!.status)
    }

    @Test
    fun `expired invoices are swept and stop being watched`() {
        val tip = pendingTip()

        // Well past the 15 minute default window.
        val later = poller(now = NOW + 3_600)
        assertEquals(0, later.pollOnce())

        assertEquals(TipStatus.EXPIRED, store.findTip(tip.nonce)!!.status)
        assertTrue(lookups.isEmpty(), "nothing pending means no reason to call TonAPI")
    }

    @Test
    fun `an expired invoice cannot still be paid`() {
        val tip = pendingTip()
        givenEvents(RAW, payment(tip.nonce, timestamp = NOW + 3_600))

        assertEquals(0, poller(now = NOW + 3_600).pollOnce())
        assertEquals(TipStatus.EXPIRED, store.findTip(tip.nonce)!!.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `two tips on one wallet cost a single request`() {
        // The rate limit is the constraint, and two tippers at once is the normal case.
        pendingTip(tipper = TIPPER)
        pendingTip(tipper = OTHER_TIPPER)

        poller().pollOnce()

        assertEquals(listOf(RAW), lookups)
    }

    @Test
    fun `one address failing does not stop another confirming`() {
        // Two addresses coexist while a configured wallet is changed with invoices in flight.
        val broken = store.createTip(OTHER_GROUP, TIPPER, OTHER_RAW, ONE_TON, NOW)
        val fine = pendingTip()

        responses[OTHER_RAW] = AccountEvents.Failed("connection reset")
        givenEvents(RAW, payment(fine.nonce))

        assertEquals(1, poller().pollOnce())
        assertEquals(TipStatus.CONFIRMED, store.findTip(fine.nonce)!!.status)
        assertEquals(TipStatus.PENDING, store.findTip(broken.nonce)!!.status, "it should be retried, not lost")
    }

    @Test
    fun `a rate limit ends the pass instead of hammering the remaining addresses`() {
        store.createTip(OTHER_GROUP, TIPPER, OTHER_RAW, ONE_TON, NOW)
        pendingTip()

        responses[RAW] = AccountEvents.RateLimited
        responses[OTHER_RAW] = AccountEvents.RateLimited

        poller().pollOnce()

        assertEquals(1, lookups.size, "the pass must stop at the first 429: $lookups")
    }

    @Test
    fun `a notification failure does not undo a confirmed payment`() {
        // The money has moved and the row is CONFIRMED. Telegram being down cannot change that.
        val tip = pendingTip()
        givenEvents(RAW, payment(tip.nonce))

        val exploding = TipPoller(
            store = store,
            events = { responses[it]!! },
            notifier = { _, _ -> throw RuntimeException("telegram is down") },
            clock = { NOW },
        )

        assertEquals(1, exploding.pollOnce())
        assertEquals(TipStatus.CONFIRMED, store.findTip(tip.nonce)!!.status)
    }

    @Test
    fun `an empty pass makes no calls and confirms nothing`() {
        assertEquals(0, poller().pollOnce())
        assertTrue(lookups.isEmpty())
    }

    private companion object {
        const val GROUP = -1001234567890L
        const val OTHER_GROUP = -1009876543210L
        const val OWNER = 12345L
        const val TIPPER = 99999L
        const val OTHER_TIPPER = 88888L
        const val ONE_TON = 1_000_000_000L
        const val NOW = 1_774_200_000L
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        const val OTHER_RAW = "0:97264395bd65a255a429b11326c84128b7d70ffed7949abae3036d506ba38621"
        const val SENDER = "0:1111111111111111111111111111111111111111111111111111111111111111"
    }
}
