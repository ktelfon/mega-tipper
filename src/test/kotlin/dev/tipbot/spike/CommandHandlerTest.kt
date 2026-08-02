package dev.tipbot.spike

import java.nio.file.Files
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole conversation, exercised without a bot token or a network. */
class CommandHandlerTest {

    private val dir = Files.createTempDirectory("cmdhandler-test")
    private lateinit var dataSource: DataSource
    private lateinit var store: TipStore
    private lateinit var handler: CommandHandler

    @BeforeTest
    fun setUp() {
        dataSource = Database.connect("jdbc:sqlite:${dir.resolve("test.db")}")
        store = TipStore(dataSource)
        handler = CommandHandler(
            store,
            OwnerConfig(name = "@user123", raw = RAW, chatId = null),
            testnet = false,
            botUsername = "tipping_bot_for_user123",
        )
    }

    @AfterTest
    fun tearDown() {
        (dataSource as? com.zaxxer.hikari.HikariDataSource)?.close()
        dir.toFile().deleteRecursively()
    }

    private fun dm(text: String, chatId: Long = TIPPER) = handler.handle(chatId, text, NOW)

    private fun inGroup(text: String, userId: Long = TIPPER) = handler.handle(
        CommandHandler.Incoming(chatId = GROUP, userId = userId, text = text, isGroup = true),
        NOW,
    )

    // --- the conversation -------------------------------------------------------------------

    @Test
    fun `start names who it collects for and offers amounts straight away`() {
        val reply = dm("/start")!!

        assertTrue(reply.text.contains("@user123"), reply.text)
        assertTrue(reply.text.contains("wallet to wallet"), "tippers should know funds are never held")
        assertTrue(reply.buttons.isNotEmpty(), "/start should not be a dead end that only mentions /tip")
    }

    @Test
    fun `tip asks how much`() {
        val reply = inGroup("/tip")!!

        assertTrue(reply.text.contains("How much"), reply.text)
        val payloads = reply.buttons.map { (it as CommandHandler.Button.Callback).data }
        assertTrue(payloads.all { it.startsWith("tip:") }, payloads.toString())
        assertTrue(store.pendingTips(NOW).isEmpty(), "asking must not create an invoice yet")
    }

    @Test
    fun `bang tip works as well as slash tip`() {
        assertEquals(inGroup("/tip")!!.buttons.size, inGroup("!tip")!!.buttons.size)
    }

    @Test
    fun `tapping an amount issues an invoice against the owner's wallet`() {
        val reply = handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW)

        val tip = store.pendingTips(NOW).single()
        assertEquals(RAW, tip.rawAddress, "every tip goes to the one configured wallet")
        assertEquals(1_000_000_000L, tip.amountNano)
        assertEquals(TIPPER, tip.tipperChatId, "the tipper is the person, never the group")
        assertEquals(GROUP, tip.originChatId, "the confirmation belongs where the tip was asked for")
        assertTrue(reply.text.contains(tip.nonce), reply.text)
        assertTrue(reply.text.contains("@user123"), "the reply should name who is being paid")
    }

    @Test
    fun `an explicit amount skips the menu`() {
        val reply = dm("/tip 2.5")!!

        val tip = store.pendingTips(NOW).single()
        assertEquals(2_500_000_000L, tip.amountNano)
        assertTrue(reply.text.contains("2.5 TON"), reply.text)
    }

    @Test
    fun `the payment link carries the address, exact amount and nonce`() {
        val reply = handler.handleCallback(TIPPER, TIPPER, "tip:1500000000", NOW)
        val nonce = store.pendingTips(NOW).single().nonce

        // Non-bounceable, so a tip to a wallet that is not yet deployed still lands.
        assertTrue(reply.text.contains("ton://transfer/$UQ"), reply.text)
        assertTrue(reply.text.contains("amount=1500000000"), reply.text)
        assertTrue(reply.text.contains("text=$nonce"), reply.text)
    }

    @Test
    fun `the invoice offers a button per wallet, all https`() {
        // Telegram rejects any button scheme other than http(s)/tg, so the ton:// link lives in
        // the message body and each wallet gets its own https button.
        val reply = handler.handleCallback(TIPPER, TIPPER, "tip:1000000000", NOW)

        val urls = reply.buttons.map { (it as CommandHandler.Button.Url).url }
        assertEquals(TipLink.WALLETS.size + 1, urls.size, "the deep-linkable wallets, plus Telegram Wallet")
        assertTrue(urls.all { it.startsWith("https://") }, urls.toString())
        assertTrue(reply.text.contains("ton://transfer/"), "and anything else uses the scheme link")
    }

    @Test
    fun `Telegram Wallet is offered even though it cannot be deep-linked`() {
        // It is a Mini App: no ton:// scheme, so the fallback link in the body does nothing for
        // its users, and no documented transfer link exists. Opening it is better than nothing.
        val reply = handler.handleCallback(TIPPER, TIPPER, "tip:1000000000", NOW)

        val labels = reply.buttons.map { it.label }
        assertTrue(labels.contains("Telegram Wallet"), labels.toString())
    }

    @Test
    fun `the three values needed to pay by hand are all in the message`() {
        val reply = handler.handleCallback(TIPPER, TIPPER, "tip:1000000000", NOW)
        val tip = store.pendingTips(NOW).single()

        assertTrue(reply.text.contains("Address: $UQ"), reply.text)
        assertTrue(reply.text.contains("Amount:  1 TON"), reply.text)
        assertTrue(reply.text.contains("Comment: ${tip.nonce}"), reply.text)
    }

    @Test
    fun `every tip request gets its own nonce`() {
        // A reused nonce would let one payment be credited against two requests. Spread across
        // two tippers because one person is capped at three live invoices by the flood guard.
        repeat(3) { handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW) }
        repeat(3) { handler.handleCallback(GROUP, OTHER_TIPPER, "tip:1000000000", NOW) }

        val nonces = store.pendingTips(NOW).map { it.nonce }
        assertEquals(6, nonces.size)
        assertEquals(6, nonces.toSet().size, "nonces must be unguessable and unique: $nonces")
    }

    @Test
    fun `two people tipping at once do not collide`() {
        handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW)
        handler.handleCallback(GROUP, OTHER_TIPPER, "tip:1000000000", NOW)

        val tips = store.pendingTips(NOW)
        assertEquals(2, tips.size)
        assertEquals(2, tips.map { it.nonce }.toSet().size, "identical amounts still need distinct nonces")
        assertEquals(setOf(TIPPER, OTHER_TIPPER), tips.map { it.tipperChatId }.toSet())
    }

    @Test
    fun `a mangled button payload never creates a tip`() {
        listOf("", "tip:", "nope:1000000000", "tip:abc", "tip:1e9", "tip:1:2")
            .forEach { handler.handleCallback(GROUP, TIPPER, it, NOW) }

        assertTrue(store.pendingTips(NOW).isEmpty(), "garbage in a payload must not reach the store")
    }

    @Test
    fun `a bad amount is explained and creates nothing`() {
        assertTrue(dm("/tip banana")!!.text.contains("amount"))
        assertTrue(dm("/tip 0.0001")!!.text.contains("too small"))
        assertTrue(store.pendingTips(NOW).isEmpty())
    }

    @Test
    fun `wallet shows the address so a tipper can check where the money goes`() {
        assertTrue(dm("/wallet")!!.text.contains(UQ))
    }

    @Test
    fun `link hands out the bot's own address, since there is nothing to look up`() {
        assertTrue(dm("/link")!!.text.contains("https://t.me/tipping_bot_for_user123"))
    }

    // --- flood control -----------------------------------------------------------------------

    @Test
    fun `a tipper is capped at three live invoices`() {
        repeat(3) { handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW) }
        assertEquals(3, store.pendingTips(NOW).size)

        val refused = handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW)

        assertTrue(refused.text.contains("already have"), refused.text)
        assertTrue(refused.buttons.isEmpty(), "a refusal must not offer a way to pay")
        assertEquals(3, store.pendingTips(NOW).size, "the fourth must not reach the store")
    }

    @Test
    fun `the cap is per tipper, so a busy group is not mistaken for abuse`() {
        // Several people tipping at once is the normal case in a group.
        repeat(3) { handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW) }

        val other = handler.handleCallback(GROUP, OTHER_TIPPER, "tip:1000000000", NOW)

        assertTrue(other.buttons.isNotEmpty(), "another person must still be able to tip")
        assertEquals(4, store.pendingTips(NOW).size)
    }

    @Test
    fun `paying frees a slot`() {
        repeat(3) { handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW) }
        val paid = store.pendingTips(NOW).first()
        store.confirm(paid.nonce, "event_x", "0:sender", NOW)

        val allowed = handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW)

        assertTrue(allowed.buttons.isNotEmpty(), "a settled invoice no longer counts against you")
    }

    @Test
    fun `expiry frees a slot`() {
        repeat(3) { handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW) }

        // Past the 15 minute window; the old invoices are no longer live.
        val later = handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW + 3_600)

        assertTrue(later.buttons.isNotEmpty(), "waiting it out is the way back in")
    }

    @Test
    fun `the cap also applies to the typed command, not just the buttons`() {
        repeat(3) { dm("/tip 1") }

        assertTrue(dm("/tip 1")!!.text.contains("already have"))
        assertEquals(3, store.pendingTips(NOW).size)
    }

    @Test
    fun `asking for the menu is never blocked - it creates nothing`() {
        repeat(3) { handler.handleCallback(GROUP, TIPPER, "tip:1000000000", NOW) }

        repeat(5) { assertTrue(inGroup("/tip")!!.buttons.isNotEmpty(), "the menu costs nothing to show") }
        assertEquals(3, store.pendingTips(NOW).size)
    }

    // --- groups ------------------------------------------------------------------------------
    //
    // A bot in a group sees traffic meant for other people and other bots. Most of what matters
    // here is what the bot must *not* say.

    @Test
    fun `ordinary group chatter is ignored, not answered`() {
        listOf("hello there", "what do you think?", "0.5", EQ).forEach { text ->
            assertNull(inGroup(text), "should have stayed silent on: $text")
        }
        assertTrue(store.pendingTips(NOW).isEmpty())
    }

    @Test
    fun `a command aimed at another bot is ignored`() {
        assertNull(inGroup("/tip@some_other_bot"))
        assertNull(inGroup("/start@rival_bot"))
    }

    @Test
    fun `our own command still works when Telegram appends the bot username`() {
        assertTrue(inGroup("/tip@tipping_bot_for_user123")!!.buttons.isNotEmpty())
    }

    @Test
    fun `an unknown slash command in a group is left to whoever it belongs to`() {
        assertNull(inGroup("/roll 2d6"))
    }

    @Test
    fun `an unknown command in a private chat points at help`() {
        assertTrue(dm("/nonsense")!!.text.contains("/help"))
    }

    @Test
    fun `a bare address is not treated as anything - there is nothing to register`() {
        assertNull(inGroup(EQ))
        assertNull(dm(EQ))
    }

    // --- channels ----------------------------------------------------------------------------
    //
    // Subscribers cannot post in a broadcast channel at all, so nothing here can rely on anyone
    // typing. The only useful response is something they can tap.

    private fun inChannel(text: String) = handler.handle(
        CommandHandler.Incoming(chatId = CHANNEL, userId = CHANNEL, text = text, isChannel = true),
        NOW,
    )

    @Test
    fun `tip in a channel publishes a card subscribers can tap`() {
        val reply = inChannel("/tip")!!

        val button = reply.buttons.single() as CommandHandler.Button.Url
        assertTrue(button.url.contains("t.me/tipping_bot_for_user123?start=tip"), button.url)
        assertTrue(reply.text.contains("@user123"), reply.text)
    }

    @Test
    fun `the channel card links to a private chat rather than paying in the channel`() {
        // An invoice posted back into the channel would spam it with every subscriber's payment
        // link, and Telegram will not message anyone who has not opened a chat with the bot.
        val reply = inChannel("/tip")!!

        assertTrue(reply.buttons.all { it is CommandHandler.Button.Url }, "no callbacks in a channel")
        assertTrue(store.pendingTips(NOW).isEmpty(), "posting a card must not create an invoice")
    }

    @Test
    fun `bang tip publishes the card too`() {
        assertEquals(inChannel("/tip")!!.buttons.size, inChannel("!tip")!!.buttons.size)
    }

    @Test
    fun `the deep link from the card opens the amount menu`() {
        // t.me/<bot>?start=tip arrives in the private chat as "/start tip".
        val reply = dm("/start tip")!!

        assertTrue(reply.buttons.isNotEmpty(), "the tipper must land on something tappable")
        assertTrue(reply.buttons.all { it is CommandHandler.Button.Callback })
    }

    @Test
    fun `an unknown command in a channel is ignored`() {
        assertNull(inChannel("/announcement"))
        assertNull(inChannel("just a normal post"))
    }

    private companion object {
        const val TIPPER = 99999L
        const val CHANNEL = -1001111111111L
        const val OTHER_TIPPER = 77777L
        const val GROUP = -1001234567890L
        const val EQ = "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"
        const val UQ = "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI"
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        const val NOW = 1_774_200_000L
    }
}
