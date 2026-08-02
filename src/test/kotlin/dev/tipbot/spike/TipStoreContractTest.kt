package dev.tipbot.spike

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behaviour [TipStore] must exhibit regardless of database engine.
 *
 * Run once per engine (see [SqliteTipStoreTest], [PostgresTipStoreTest]). Storage
 * portability is the whole reason the schema is written the way it is, so it is worth
 * proving rather than asserting - the double-payout guard in particular depends on
 * unique-index behaviour that could plausibly differ between engines.
 */
abstract class TipStoreContractTest {

    /** Opens a connection to this engine. Called more than once against the same database. */
    protected abstract fun connect(): DataSource

    private lateinit var dataSource: DataSource
    private lateinit var store: TipStore

    @BeforeTest
    fun setUp() {
        dataSource = connect()
        // Postgres reuses one database across tests, so start from a known-empty schema.
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DROP TABLE IF EXISTS tips")
                st.executeUpdate("DROP TABLE IF EXISTS creators")
            }
        }
        Database.migrate(dataSource)
        store = TipStore(dataSource)
    }

    @AfterTest
    fun tearDown() {
        (dataSource as? HikariDataSource)?.close()
    }

    @Test
    fun `a tip records the chat it was raised in, which is where it gets announced`() {
        val tip = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)

        val reread = store.findTip(tip.nonce)
        assertNotNull(reread)
        assertEquals(CHAT, reread.originChatId)
        assertEquals(TIPPER, reread.tipperChatId)
    }

    @Test
    fun `a tip keeps the address it was issued against`() {
        // So changing the configured wallet cannot redirect an invoice already in flight.
        val tip = store.createTip(CHAT, TIPPER, OTHER_RAW, ONE_TON, NOW)

        assertEquals(OTHER_RAW, store.findTip(tip.nonce)?.rawAddress)
    }

    @Test
    fun `creates a pending tip with an unguessable nonce`() {
        val tip = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)

        assertEquals(TipStatus.PENDING, tip.status)
        assertTrue(tip.nonce.startsWith("tip_"))
        assertEquals(20, tip.nonce.length, "tip_ + 16 hex chars = 64 bits of entropy")
        assertEquals(NOW + 900, tip.expiresAt, "default 15 minute window")
        assertNull(tip.eventId)

        assertEquals(tip, store.findTip(tip.nonce), "a round-trip through the database must not alter the tip")
    }

    @Test
    fun `nonces do not repeat`() {
        val nonces = (1..200).map { store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW).nonce }
        assertEquals(nonces.size, nonces.toSet().size, "a repeated nonce would misattribute a tip")
    }

    @Test
    fun `confirming a tip records the payment`() {
        val tip = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)

        assertTrue(store.confirm(tip.nonce, EVENT, SENDER, NOW + 30))

        val settled = store.findTip(tip.nonce)!!
        assertEquals(TipStatus.CONFIRMED, settled.status)
        assertEquals(EVENT, settled.eventId)
        assertEquals(SENDER, settled.senderAddress)
        assertEquals(NOW + 30, settled.confirmedAt)
    }

    @Test
    fun `confirming twice pays out only once`() {
        // The poller re-reads the same event on every cycle; only the first must win.
        val tip = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)

        assertTrue(store.confirm(tip.nonce, EVENT, SENDER, NOW + 30))
        assertFalse(store.confirm(tip.nonce, EVENT, SENDER, NOW + 60), "second confirm must be refused")
    }

    @Test
    fun `one on-chain event cannot pay two different tips`() {
        // The real double-payout risk: two invoices, one transfer credited to both.
        // Refused by the unique index, not by application logic that could race.
        val first = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)
        val second = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)

        assertTrue(store.confirm(first.nonce, EVENT, SENDER, NOW + 30))
        assertFalse(store.confirm(second.nonce, EVENT, SENDER, NOW + 30))

        assertEquals(TipStatus.PENDING, store.findTip(second.nonce)!!.status)
    }

    @Test
    fun `many pending tips coexist with null event ids`() {
        // A unique index over a nullable column must still allow many unpaid invoices.
        repeat(5) { store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW) }
        assertEquals(5, store.pendingTips(NOW).size)
    }

    @Test
    fun `poller sees only live pending tips`() {
        val live = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)
        val expired = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW - 10_000, ttlSeconds = 60)
        val settled = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)
        store.confirm(settled.nonce, EVENT, SENDER, NOW)

        val pending = store.pendingTips(NOW).map { it.nonce }

        assertEquals(listOf(live.nonce), pending)
        assertTrue(expired.nonce !in pending, "past its window")
        assertTrue(settled.nonce !in pending, "already paid")
    }

    @Test
    fun `sweeping marks stale invoices expired`() {
        val stale = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW - 10_000, ttlSeconds = 60)
        val live = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)

        assertEquals(1, store.expireStale(NOW))

        assertEquals(TipStatus.EXPIRED, store.findTip(stale.nonce)!!.status)
        assertEquals(TipStatus.PENDING, store.findTip(live.nonce)!!.status)
    }

    @Test
    fun `an expired tip can no longer be confirmed`() {
        val tip = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW - 10_000, ttlSeconds = 60)
        store.expireStale(NOW)

        assertFalse(store.confirm(tip.nonce, EVENT, SENDER, NOW), "a late payment must not settle a dead invoice")
    }

    @Test
    fun `tip converts to the invoice the matcher expects`() {
        val tip = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)
        val invoice = tip.toInvoice()

        assertEquals(tip.nonce, invoice.commentNonce)
        assertEquals(RAW, invoice.recipientAddress)
        assertEquals(ONE_TON, invoice.expectedNanoTon)
        assertTrue(invoice.isWithinWindow(NOW + 60))
        assertFalse(invoice.isWithinWindow(NOW + 1_000), "past the 15 minute default")
    }

    @Test
    fun `state survives a restart`() {
        // The reason for not using an in-memory store: this must hold across a redeploy,
        // or the double-payout guard resets every time the process restarts.
        val tip = store.createTip(CHAT, TIPPER, RAW, ONE_TON, NOW)
        store.confirm(tip.nonce, EVENT, SENDER, NOW)
        (dataSource as? HikariDataSource)?.close()

        dataSource = connect()
        val reopened = TipStore(dataSource)

        assertEquals(TipStatus.CONFIRMED, reopened.findTip(tip.nonce)?.status)
        assertFalse(reopened.confirm(tip.nonce, EVENT, SENDER, NOW), "guard must survive a restart")
    }

    protected companion object {
        const val CHAT = 12345L
        const val TIPPER = 67890L
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        const val OTHER_RAW = "0:97264395bd65a255a429b11326c84128b7d70ffed7949abae3036d506ba38621"
        const val EVENT = "a1cbe771e0ede8744a100b0f312ac9f7e1a881f095537e464daaa58b58bde8f8"
        const val SENDER = "0:f270044c96e131e9be75b982732bef3a9282fcea98fa6c71fce3ab309e28fcc2"
        const val ONE_TON = 1_000_000_000L
        const val NOW = 1_774_200_000L
    }
}
