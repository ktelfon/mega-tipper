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
        // These cover the self-setup conversation, so it is switched on. Operator mode - the
        // default, where wallets come from the file - is covered at the bottom of this class.
        handler = CommandHandler(store, testnet = false, botUsername = "mega_tipper_bot", allowSelfSetup = true)
    }

    @AfterTest
    fun tearDown() {
        (dataSource as? com.zaxxer.hikari.HikariDataSource)?.close()
        dir.toFile().deleteRecursively()
    }

    private fun send(text: String, chatId: Long = CHAT) = handler.handle(chatId, text, NOW)!!.text

    @Test
    fun `start explains the bot is non-custodial`() {
        val reply = send("/start")
        assertTrue(reply.contains("wallet to wallet"), "users should know funds are never held: $reply")
        assertTrue(reply.contains("/setup"))
    }

    @Test
    fun `setup with an address registers it in one step`() {
        val reply = send("/setup $EQ")

        assertTrue(reply.contains("registered"), reply)
        assertEquals(RAW, store.findCreator(CHAT)?.rawAddress)
    }

    @Test
    fun `setup alone prompts, then a bare address completes it`() {
        assertTrue(send("/setup").contains("Send me your TON wallet address"))
        assertNull(store.findCreator(CHAT), "prompting must not store anything yet")

        send(EQ)

        assertEquals(RAW, store.findCreator(CHAT)?.rawAddress)
    }

    @Test
    fun `the stored address is always canonical raw form`() {
        // Whichever spelling the creator pastes, matching later compares against TonAPI's raw.
        send("/setup $UQ")
        assertEquals(RAW, store.findCreator(CHAT)?.rawAddress)
    }

    @Test
    fun `a typo is rejected with a reason and nothing is stored`() {
        val reply = send("/setup ${EQ.dropLast(1)}X")

        assertTrue(reply.contains("checksum"), reply)
        assertNull(store.findCreator(CHAT), "a bad address must never be stored - tips sent there are lost")
    }

    @Test
    fun `a testnet address is refused on a mainnet bot`() {
        val reply = send("/setup $KQ")

        assertTrue(reply.contains("testnet"), reply)
        assertNull(store.findCreator(CHAT))
    }

    @Test
    fun `wallet shows nothing before setup and the address after`() {
        assertTrue(send("/wallet").contains("haven't registered"))

        send("/setup $EQ")

        assertTrue(send("/wallet").contains(RAW))
    }

    @Test
    fun `sending a new address replaces the old one`() {
        send("/setup $EQ")
        val reply = send(OTHER_EQ)

        assertTrue(reply.contains("registered"), reply)
        assertEquals(OTHER_RAW, store.findCreator(CHAT)?.rawAddress)
    }

    @Test
    fun `creators are kept separate by chat id`() {
        // One deployment serves everyone, so two people must never share a wallet row.
        send("/setup $EQ", chatId = CHAT)
        send("/setup $OTHER_EQ", chatId = OTHER_CHAT)

        assertEquals(RAW, store.findCreator(CHAT)?.rawAddress)
        assertEquals(OTHER_RAW, store.findCreator(OTHER_CHAT)?.rawAddress)
    }

    @Test
    fun `commands work when Telegram appends the bot username in groups`() {
        val reply = send("/setup@mega_tipper_bot $EQ")

        assertTrue(reply.contains("registered"), reply)
        assertEquals(RAW, store.findCreator(CHAT)?.rawAddress)
    }

    @Test
    fun `an unknown command points at help rather than being read as an address`() {
        val reply = send("/nonsense")

        assertTrue(reply.contains("/help"), reply)
        assertNull(store.findCreator(CHAT))
    }

    @Test
    fun `chatter that is not an address gets a useful complaint`() {
        val reply = send("hello there")

        assertTrue(reply.contains("TON address"), reply)
        assertNull(store.findCreator(CHAT))
    }

    // --- tipping ---------------------------------------------------------------------------

    @Test
    fun `registering hands back a share link the creator can post`() {
        val reply = send("/setup $EQ")

        assertTrue(reply.contains("https://t.me/mega_tipper_bot?start=$CHAT"), reply)
    }

    @Test
    fun `link is refused until there is a wallet to pay into`() {
        assertTrue(send("/link").contains("/setup"))

        send("/setup $EQ")

        assertTrue(send("/link").contains("https://t.me/mega_tipper_bot?start=$CHAT"))
    }

    @Test
    fun `opening a share link offers amounts for that creator`() {
        send("/setup $EQ", chatId = CHAT)

        val reply = handler.handle(TIPPER, "/start $CHAT", NOW)!!

        assertTrue(reply.buttons.isNotEmpty(), "a tipper needs something to tap")
        val payloads = reply.buttons.map { (it as CommandHandler.Button.Callback).data }
        assertTrue(payloads.all { it.startsWith("tip:$CHAT:") }, "every button must name the creator: $payloads")
    }

    @Test
    fun `a share link for someone who never set up a wallet does not offer to take money`() {
        val reply = handler.handle(TIPPER, "/start $CHAT", NOW)!!

        assertTrue(reply.buttons.isEmpty(), "there is nowhere to send the funds")
        assertTrue(reply.text.contains("no wallet"), reply.text)
    }

    @Test
    fun `tapping an amount issues a pending tip against the creator's wallet`() {
        send("/setup $EQ", chatId = CHAT)

        val reply = handler.handleCallback(TIPPER, TIPPER, "tip:$CHAT:1000000000", NOW)

        val tip = store.pendingTips(NOW).single()
        assertEquals(CHAT, tip.creatorChatId)
        assertEquals(TIPPER, tip.tipperChatId)
        assertEquals(RAW, tip.rawAddress, "the invoice must point at the creator's stored address")
        assertEquals(1_000_000_000L, tip.amountNano)
        assertTrue(reply.text.contains(tip.nonce), "the tipper needs the comment to attach: ${reply.text}")
    }

    @Test
    fun `the payment link carries the address, exact amount and nonce`() {
        send("/setup $EQ", chatId = CHAT)

        val reply = handler.handleCallback(TIPPER, TIPPER, "tip:$CHAT:1500000000", NOW)
        val nonce = store.pendingTips(NOW).single().nonce

        // Non-bounceable, so a tip to a creator whose wallet is not yet deployed still lands.
        assertTrue(reply.text.contains("ton://transfer/$UQ"), reply.text)
        assertTrue(reply.text.contains("amount=1500000000"), reply.text)
        assertTrue(reply.text.contains("text=$nonce"), reply.text)
    }

    @Test
    fun `the pay button is an https link because Telegram rejects other schemes`() {
        send("/setup $EQ", chatId = CHAT)

        val reply = handler.handleCallback(TIPPER, TIPPER, "tip:$CHAT:1000000000", NOW)

        val button = reply.buttons.single() as CommandHandler.Button.Url
        assertTrue(button.url.startsWith("https://"), button.url)
    }

    @Test
    fun `every tip request gets its own nonce`() {
        // A reused nonce would let one payment be credited against two requests.
        send("/setup $EQ", chatId = CHAT)

        repeat(5) { handler.handleCallback(TIPPER, TIPPER, "tip:$CHAT:1000000000", NOW) }

        val nonces = store.pendingTips(NOW).map { it.nonce }
        assertEquals(5, nonces.size)
        assertEquals(5, nonces.toSet().size, "nonces must be unguessable and unique: $nonces")
    }

    @Test
    fun `a mangled button payload never creates a tip`() {
        send("/setup $EQ", chatId = CHAT)

        listOf("", "tip:", "tip:$CHAT", "nope:$CHAT:1000000000", "tip:abc:1000000000", "tip:$CHAT:1e9")
            .forEach { payload ->
                handler.handleCallback(TIPPER, TIPPER, payload, NOW)
            }

        assertTrue(store.pendingTips(NOW).isEmpty(), "garbage in a payload must not reach the store")
    }

    @Test
    fun `tip with an amount alone bills your own wallet, for testing`() {
        send("/setup $EQ", chatId = CHAT)

        val reply = send("/tip 2")

        val tip = store.pendingTips(NOW).single()
        assertEquals(CHAT, tip.creatorChatId)
        assertEquals(2_000_000_000L, tip.amountNano)
        assertTrue(reply.contains("2 TON"), reply)
    }

    @Test
    fun `a bad amount is explained and creates nothing`() {
        send("/setup $EQ", chatId = CHAT)

        val reply = send("/tip lots")

        assertTrue(reply.contains("amount"), reply)
        assertTrue(store.pendingTips(NOW).isEmpty())
    }

    @Test
    fun `tipping someone with no wallet is refused before anything is stored`() {
        val reply = send("/tip $OTHER_CHAT 1")

        assertTrue(reply.contains("no wallet"), reply)
        assertTrue(store.pendingTips(NOW).isEmpty())
    }

    // --- groups ----------------------------------------------------------------------------
    //
    // A bot in a group sees traffic meant for other people and other bots. Almost every test
    // here is about what the bot must *not* say.

    private fun inGroup(
        text: String,
        userId: Long = TIPPER,
        replyTo: Long? = null,
        replyName: String? = null,
        admin: Boolean = false,
    ) = handler.handle(
        CommandHandler.Incoming(
            chatId = GROUP,
            userId = userId,
            text = text,
            isGroup = true,
            replyToUserId = replyTo,
            replyToName = replyName,
            senderIsAdmin = { admin },
        ),
        NOW,
    )

    @Test
    fun `ordinary group chatter is ignored, not answered`() {
        // The old behaviour read every message as a wallet address. In a group that is a reply
        // to every single line anyone types, which gets the bot removed within minutes.
        listOf("hello there", "what do you think?", "0.5", EQ).forEach { text ->
            assertNull(inGroup(text), "should have stayed silent on: $text")
        }
        assertNull(store.findCreator(GROUP), "group chatter must never register a wallet")
    }

    @Test
    fun `a command aimed at another bot is ignored`() {
        assertNull(inGroup("/tip@some_other_bot"))
        assertNull(inGroup("/start@rival_bot"))
    }

    @Test
    fun `an unknown slash command in a group is left to whoever it belongs to`() {
        assertNull(inGroup("/roll 2d6"))
    }

    @Test
    fun `bang tip works as well as slash tip`() {
        inGroup("/setup $EQ", admin = true)

        val bang = inGroup("!tip")!!
        val slash = inGroup("/tip")!!

        assertEquals(slash.buttons.size, bang.buttons.size)
        assertTrue(bang.buttons.isNotEmpty(), "!tip should offer amounts too")
    }

    @Test
    fun `only an admin can point the group's tips at a wallet`() {
        val refused = inGroup("/setup $EQ", admin = false)!!

        assertTrue(refused.text.contains("admin"), refused.text)
        assertNull(store.findCreator(GROUP), "a member must not be able to redirect the group's earnings")

        inGroup("/setup $EQ", admin = true)
        assertEquals(RAW, store.findCreator(GROUP)?.rawAddress)
    }

    @Test
    fun `tip in a group pays the group's wallet`() {
        inGroup("/setup $EQ", admin = true)

        val reply = inGroup("/tip 1", userId = TIPPER)!!

        val tip = store.pendingTips(NOW).single()
        assertEquals(GROUP, tip.creatorChatId)
        assertEquals(TIPPER, tip.tipperChatId, "the tipper is the person, never the group")
        assertEquals(RAW, tip.rawAddress)
        assertTrue(reply.text.contains(tip.nonce), reply.text)
    }

    @Test
    fun `tip as a reply pays the person being replied to`() {
        // The social point of the feature: tip whoever just said the useful thing.
        send("/setup $EQ", chatId = CREATOR_USER)

        val reply = inGroup("/tip", userId = TIPPER, replyTo = CREATOR_USER, replyName = "@bob")!!

        assertTrue(reply.text.contains("@bob"), "the menu should name who gets paid: ${reply.text}")
        val payloads = reply.buttons.map { (it as CommandHandler.Button.Callback).data }
        assertTrue(payloads.all { it.startsWith("tip:$CREATOR_USER:") }, payloads.toString())
    }

    @Test
    fun `replying to someone with no wallet tells them how to get one`() {
        val reply = inGroup("/tip", replyTo = CREATOR_USER, replyName = "@bob")!!

        assertTrue(reply.buttons.isEmpty(), "nowhere to send it")
        assertTrue(reply.text.contains("/setup"), "it should convert them: ${reply.text}")
    }

    @Test
    fun `tipping a group with no wallet does not silently create an invoice`() {
        val reply = inGroup("/tip")!!

        assertTrue(reply.buttons.isEmpty())
        assertTrue(store.pendingTips(NOW).isEmpty())
    }

    @Test
    fun `a tap in a group credits the tapper, not the group`() {
        inGroup("/setup $EQ", admin = true)

        handler.handleCallback(GROUP, TIPPER, "tip:$GROUP:1000000000", NOW)

        val tip = store.pendingTips(NOW).single()
        assertEquals(GROUP, tip.creatorChatId)
        assertEquals(TIPPER, tip.tipperChatId)
    }

    @Test
    fun `two people can tip the same group at once without colliding`() {
        inGroup("/setup $EQ", admin = true)

        handler.handleCallback(GROUP, TIPPER, "tip:$GROUP:1000000000", NOW)
        handler.handleCallback(GROUP, OTHER_TIPPER, "tip:$GROUP:1000000000", NOW)

        val tips = store.pendingTips(NOW)
        assertEquals(2, tips.size)
        assertEquals(2, tips.map { it.nonce }.toSet().size, "identical amounts still need distinct nonces")
        assertEquals(setOf(TIPPER, OTHER_TIPPER), tips.map { it.tipperChatId }.toSet())
    }

    @Test
    fun `a bad amount in a group is explained rather than ignored`() {
        inGroup("/setup $EQ", admin = true)

        val reply = inGroup("/tip banana")!!

        assertTrue(reply.text.contains("amount"), reply.text)
        assertTrue(store.pendingTips(NOW).isEmpty())
    }

    // --- operator mode ------------------------------------------------------------------
    //
    // The default. Wallets come from the operator's file, and nothing said in Telegram can
    // change one - because the person running the bot is not the person in the group.

    private val operatorBot
        get() = CommandHandler(store, testnet = false, botUsername = "mega_tipper_bot", allowSelfSetup = false)

    @Test
    fun `setup cannot change a wallet when the operator owns the config`() {
        val reply = operatorBot.handle(CHAT, "/setup $EQ", NOW)!!

        assertTrue(reply.text.contains("runs it"), reply.text)
        assertNull(store.findCreator(CHAT), "chat must never override the operator's file")
    }

    @Test
    fun `a bare address is ignored rather than registered`() {
        assertNull(operatorBot.handle(CHAT, EQ, NOW))
        assertNull(store.findCreator(CHAT))
    }

    @Test
    fun `a group admin cannot redirect the group's earnings either`() {
        val reply = operatorBot.handle(
            CommandHandler.Incoming(
                chatId = GROUP, userId = TIPPER, text = "/setup $EQ",
                isGroup = true, senderIsAdmin = { true },
            ),
            NOW,
        )!!

        assertTrue(reply.text.contains("runs it"), reply.text)
        assertNull(store.findCreator(GROUP))
    }

    @Test
    fun `chatid reports the id the operator needs for the wallet file`() {
        val group = operatorBot.handle(
            CommandHandler.Incoming(chatId = GROUP, userId = TIPPER, text = "/chatid", isGroup = true),
            NOW,
        )!!

        assertTrue(group.text.contains(GROUP.toString()), group.text)
    }

    @Test
    fun `a wallet placed by the operator is tippable with no setup conversation`() {
        // Exactly the deployment story: the file is applied, and the group just works.
        WalletDirectory.apply(
            WalletDirectory.Directory(
                listOf(WalletDirectory.Entry(GROUP, "Bob's Chat", RAW)),
                allowSelfSetup = false,
            ),
            store,
            NOW,
        )

        val reply = operatorBot.handle(
            CommandHandler.Incoming(chatId = GROUP, userId = TIPPER, text = "/tip 1", isGroup = true),
            NOW,
        )!!

        val tip = store.pendingTips(NOW).single()
        assertEquals(GROUP, tip.creatorChatId)
        assertEquals(RAW, tip.rawAddress)
        assertTrue(reply.text.contains(tip.nonce), reply.text)
    }

    @Test
    fun `help does not advertise a command the operator has switched off`() {
        assertTrue(!operatorBot.handle(CHAT, "/help", NOW)!!.text.contains("/setup"))
        assertTrue(handler.handle(CHAT, "/help", NOW)!!.text.contains("/setup"))
    }

    private companion object {
        const val CHAT = 12345L
        const val TIPPER = 99999L
        const val OTHER_TIPPER = 77777L
        const val CREATOR_USER = 4242L
        const val GROUP = -1001234567890L
        const val OTHER_CHAT = 54321L
        const val EQ = "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"
        const val UQ = "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI"
        const val KQ = "kQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqKYH"
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        const val OTHER_EQ = "EQCXJkOVvWWiVaQpsRMmyEEot9cP_teUmrrjA21Qa6OGIeng"
        const val OTHER_RAW = "0:97264395bd65a255a429b11326c84128b7d70ffed7949abae3036d506ba38621"
        const val NOW = 1_774_200_000L
    }
}
