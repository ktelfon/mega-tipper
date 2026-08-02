package dev.tipbot.spike

import com.fasterxml.jackson.databind.ObjectMapper
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole stack, driven the way Telegram drives it.
 *
 * Every other test calls [CommandHandler] directly, which means nothing exercised
 * [TipBot.consume] - the update router. That gap is not hypothetical: channel posts arrive as
 * `channel_post` rather than `message` and were being dropped there silently, while 116 unit
 * tests passed. The bug was found by hand, in a real channel.
 *
 * So these tests start from raw Telegram JSON, push it through the real `consume`, capture what
 * the bot tries to send, and carry on to the poller confirming a payment. The only things faked
 * are the two edges we do not own: Telegram's wire and TonAPI's.
 */
class EndToEndTest {

    private val dir = Files.createTempDirectory("e2e-test")
    private lateinit var dataSource: DataSource
    private lateinit var store: TipStore
    private lateinit var bot: TipBot

    /** Everything the bot tried to send, in order. */
    private val sent = mutableListOf<SendMessage>()

    @BeforeTest
    fun setUp() {
        dataSource = Database.connect("jdbc:sqlite:${dir.resolve("e2e.db")}")
        store = TipStore(dataSource)
        val handler = CommandHandler(
            store,
            OwnerConfig(name = "@Mdefman", raw = RAW, chatId = null),
            testnet = false,
            botUsername = "mega_tipper_bot",
        )
        bot = TipBot(handler, recordingClient())
    }

    @AfterTest
    fun tearDown() {
        (dataSource as? com.zaxxer.hikari.HikariDataSource)?.close()
        dir.toFile().deleteRecursively()
    }

    /**
     * [TelegramClient] has 40 `execute` overloads, so it is implemented reflectively rather than
     * by hand. Only the three the bot actually calls need to do anything.
     */
    private fun recordingClient(): TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, _, args ->
            when (val request = args?.firstOrNull()) {
                is SendMessage -> { sent += request; null }
                is AnswerCallbackQuery -> true
                else -> null
            }
        } as TelegramClient

    private fun deliver(json: String) {
        bot.consume(ObjectMapper().readValue(json, Update::class.java))
    }

    private fun privateMessage(text: String, from: Long = SAM) = deliver(
        """
        {"update_id":1,"message":{"message_id":10,"date":$DATE,"text":"$text",
          "chat":{"id":$from,"type":"private"},
          "from":{"id":$from,"is_bot":false,"first_name":"Sam"}}}
        """.trimIndent()
    )

    private fun groupMessage(text: String) = deliver(
        """
        {"update_id":2,"message":{"message_id":11,"date":$DATE,"text":"$text",
          "chat":{"id":$GROUP,"type":"supergroup","title":"Traders"},
          "from":{"id":$SAM,"is_bot":false,"first_name":"Sam"}}}
        """.trimIndent()
    )

    private fun channelPost(text: String) = deliver(
        """
        {"update_id":3,"channel_post":{"message_id":12,"date":$DATE,"text":"$text",
          "chat":{"id":$CHANNEL,"type":"channel","title":"My_CHANNA1"}}}
        """.trimIndent()
    )

    private fun buttonTap(data: String, chat: Long, type: String, from: Long = SAM) = deliver(
        """
        {"update_id":4,"callback_query":{"id":"cb_1","data":"$data",
          "from":{"id":$from,"is_bot":false,"first_name":"Sam"},
          "message":{"message_id":13,"date":$DATE,"chat":{"id":$chat,"type":"$type"}}}}
        """.trimIndent()
    )

    private val lastSent get() = sent.last()

    private fun buttonUrls(message: SendMessage) =
        message.replyMarkup.let { it as org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup }
            .keyboard.flatten().mapNotNull { it.url }

    private fun callbackData(message: SendMessage) =
        message.replyMarkup.let { it as org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup }
            .keyboard.flatten().mapNotNull { it.callbackData }

    // --- the whole journey --------------------------------------------------------------------

    @Test
    fun `a tip in a group goes from command to confirmed payment`() {
        // 1. Someone asks to tip.
        groupMessage("/tip")

        assertEquals(GROUP.toString(), lastSent.chatId)
        assertTrue(lastSent.text.contains("How much"), lastSent.text)
        val amounts = callbackData(lastSent)
        assertTrue(amounts.contains("tip:1000000000"), amounts.toString())

        // 2. They tap 1 TON.
        buttonTap("tip:1000000000", chat = GROUP, type = "supergroup")

        val tip = store.pendingTips(0).single()
        assertEquals(GROUP, tip.originChatId)
        assertEquals(SAM, tip.tipperChatId)
        assertTrue(lastSent.text.contains(tip.nonce), "the tipper needs the comment: ${lastSent.text}")

        val wallets = buttonUrls(lastSent)
        assertTrue(wallets.any { it.startsWith("https://app.tonkeeper.com/transfer/") }, wallets.toString())
        assertTrue(wallets.any { it == "https://t.me/wallet" }, wallets.toString())

        // 3. They pay, and TonAPI reports it.
        sent.clear()
        val confirmed = poller(paymentFor(tip), now = tip.createdAt).pollOnce()

        assertEquals(1, confirmed)
        assertEquals(TipStatus.CONFIRMED, store.findTip(tip.nonce)?.status)

        // 4. The group is told, publicly, where the tip was asked for.
        val announcedIn = sent.map { it.chatId }
        assertTrue(announcedIn.contains(GROUP.toString()), "the group must see it: $announcedIn")
        assertTrue(sent.any { it.text.contains("1 TON") }, sent.map { it.text }.toString())
    }

    @Test
    fun `a channel post publishes a card that links into a private chat`() {
        // The regression test for the bug the unit tests could not see: channel_post never
        // reached the handler at all, and the bot looked simply broken.
        channelPost("/tip")

        assertEquals(CHANNEL.toString(), lastSent.chatId)
        assertEquals(listOf("https://t.me/mega_tipper_bot?start=tip"), buttonUrls(lastSent))
    }

    @Test
    fun `the channel deep link lands on the amount menu`() {
        privateMessage("/start tip")

        assertEquals(SAM.toString(), lastSent.chatId)
        assertTrue(callbackData(lastSent).isNotEmpty(), "the tipper must land on something tappable")
    }

    @Test
    fun `a private tip with an explicit amount goes straight to the payment card`() {
        privateMessage("/tip 2.5")

        val tip = store.pendingTips(0).single()
        assertEquals(2_500_000_000L, tip.amountNano)
        assertEquals(SAM, tip.originChatId)
        assertTrue(lastSent.text.contains("2.5 TON"), lastSent.text)
        assertTrue(lastSent.text.contains("Comment: ${tip.nonce}"), "manual payers need it spelled out")
    }

    @Test
    fun `group chatter produces no reply at all`() {
        groupMessage("hello everyone")
        groupMessage("what do you think about 0.5")
        groupMessage("/roll 2d6")
        groupMessage("/tip@some_other_bot")

        assertTrue(sent.isEmpty(), "the bot must be silent in a group it was not addressed in: $sent")
    }

    @Test
    fun `an update with no text is ignored rather than crashing the router`() {
        deliver("""{"update_id":5,"message":{"message_id":14,"date":$DATE,"chat":{"id":$SAM,"type":"private"}}}""")
        deliver("""{"update_id":6}""")

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a payment that never arrives leaves the invoice pending, then expired`() {
        privateMessage("/tip 1")
        val tip = store.pendingTips(0).single()

        sent.clear()
        assertEquals(0, poller("""{"events":[]}""", now = tip.createdAt).pollOnce())
        assertEquals(TipStatus.PENDING, store.findTip(tip.nonce)?.status)

        // Past the window.
        assertEquals(0, poller("""{"events":[]}""", now = tip.expiresAt + 1).pollOnce())
        assertEquals(TipStatus.EXPIRED, store.findTip(tip.nonce)?.status)
        assertTrue(sent.isEmpty(), "nothing arrived, so nobody should have been told")
    }

    private fun poller(eventsJson: String, now: Long) = TipPoller(
        store = store,
        events = { AccountEvents.Ok(ObjectMapper().readTree(eventsJson)) },
        notifier = { chatId, text ->
            sent += SendMessage.builder().chatId(chatId.toString()).text(text).build()
        },
        clock = { now },
    )

    /**
     * What TonAPI returns once the tipper has actually paid. The timestamp is taken from the tip
     * the bot really issued, because [TipBot] stamps invoices with the wall clock - a fixed
     * constant here would fall outside the invoice window and never match.
     */
    private fun paymentFor(tip: Tip) = """
        {"events":[{
          "event_id":"event_${tip.nonce}","timestamp":${tip.createdAt},"is_scam":false,"in_progress":false,
          "actions":[{"type":"TonTransfer","status":"ok","TonTransfer":{
            "sender":{"address":"$SENDER"},"recipient":{"address":"$RAW"},
            "amount":${tip.amountNano},"comment":"${tip.nonce}"}}]}]}
    """.trimIndent()

    private companion object {
        const val SAM = 99999L
        const val GROUP = -1001234567890L
        const val CHANNEL = -1001111111111L
        const val RAW = "0:15fedae08ddc2ca14cbe9f9f4ec6a9c1c499230ded686992cfd6ff5a2848f828"
        const val SENDER = "0:1111111111111111111111111111111111111111111111111111111111111111"
        const val NOW = 1_774_200_000L
        const val DATE = 1_774_200_000L
    }
}
