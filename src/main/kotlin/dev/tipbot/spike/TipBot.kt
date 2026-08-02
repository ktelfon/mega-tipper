package dev.tipbot.spike

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.File
import java.time.Instant

/**
 * Telegram plumbing. Deliberately thin: every decision about what to say lives in
 * [CommandHandler], which is pure and tested without a token or a network.
 *
 * Long polling rather than webhooks, so the bot needs no public URL and behaves identically
 * on a laptop and in the cloud. Webhooks are a step 6 concern.
 */
class TipBot(
    private val handler: CommandHandler,
    private val client: TelegramClient,
) : LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(TipBot::class.java)

    override fun consume(update: Update) {
        when {
            update.hasCallbackQuery() -> handleCallback(update)
            update.hasMessage() && update.message.hasText() -> handleMessage(update)
            // Posts in a channel arrive as channel_post, not message. Without this they are
            // dropped silently - which looks exactly like the bot being broken.
            update.hasChannelPost() && update.channelPost.hasText() -> handleChannelPost(update)
        }
    }

    private fun handleMessage(update: Update) {
        val message = update.message
        val chatId = message.chatId
        val isGroup = message.chat.isGroupChat || message.chat.isSuperGroupChat

        // Log the command only, never the full message: a wallet address is not secret, but
        // there is no reason to write whatever else people type into the operator's logs. In a
        // group with privacy mode off this is every message in the room, which makes logging
        // the text outright unacceptable.
        log.info("chat {} -> {}", chatId, message.text.trim().substringBefore(' ').take(32))

        val incoming = CommandHandler.Incoming(
            chatId = chatId,
            userId = message.from?.id ?: chatId,
            text = message.text,
            isGroup = isGroup,
        )

        val reply = safely(chatId) { handler.handle(incoming, Instant.now().epochSecond) }
        if (reply != null) send(chatId, reply)
    }

    /**
     * A post in a channel. There is no sender to speak of - posts are made by the channel - so
     * the chat stands in for the user, and the only command that means anything is the one that
     * publishes a tip card for subscribers to tap.
     */
    private fun handleChannelPost(update: Update) {
        val post = update.channelPost
        val chatId = post.chatId

        log.info("channel {} -> {}", chatId, post.text.trim().substringBefore(' ').take(32))

        val incoming = CommandHandler.Incoming(
            chatId = chatId,
            userId = chatId,
            text = post.text,
            isChannel = true,
        )

        val reply = safely(chatId) { handler.handle(incoming, Instant.now().epochSecond) }
        if (reply != null) send(chatId, reply)
    }

    private fun handleCallback(update: Update) {
        val query = update.callbackQuery
        val chatId = query.message.chatId

        log.info("chat {} tapped {}", chatId, query.data)

        // Telegram spins the button until the query is answered, so this comes first and is
        // never skipped - even if building the reply throws.
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(query.id).build())
        } catch (e: Exception) {
            log.warn("Failed answering callback from {}", chatId, e)
        }

        val reply = safely(chatId) {
            // The tipper is whoever tapped, which in a group is nobody's chat id but their own.
            handler.handleCallback(chatId, query.from.id, query.data, Instant.now().epochSecond)
        }
        if (reply != null) send(chatId, reply)
    }

    /** One bad message must not take the bot down for everyone else. */
    private fun safely(chatId: Long, block: () -> CommandHandler.Reply?): CommandHandler.Reply? =
        try {
            block()
        } catch (e: Exception) {
            log.error("Failed handling update from {}", chatId, e)
            CommandHandler.Reply("Something went wrong handling that. Try again in a moment.")
        }

    private fun send(chatId: Long, reply: CommandHandler.Reply) {
        val message = SendMessage.builder()
            .chatId(chatId.toString())
            .text(reply.text)
            // The payment links are long and Telegram's preview of a ton:// URI is noise.
            .disableWebPagePreview(true)
            .apply { if (reply.buttons.isNotEmpty()) replyMarkup(keyboard(reply.buttons)) }
            .build()

        try {
            client.execute(message)
        } catch (e: Exception) {
            log.error("Failed replying to {}", chatId, e)
        }
    }

    /** One button per row: the labels carry amounts, and a wrapped row hides the last one. */
    private fun keyboard(buttons: List<CommandHandler.Button>) =
        InlineKeyboardMarkup.builder()
            .keyboard(
                buttons.map { button ->
                    val built = InlineKeyboardButton.builder().text(button.label).apply {
                        when (button) {
                            is CommandHandler.Button.Url -> url(button.url)
                            is CommandHandler.Button.Callback -> callbackData(button.data)
                        }
                    }.build()
                    InlineKeyboardRow(built)
                }
            )
            .build()
}

fun main() {
    val config = Config.load()
    println("Starting tip bot: $config")

    // One bot, one person, one wallet, baked in at deploy time. A bad address aborts startup
    // rather than running a deployment that silently swallows every tip sent to it.
    //
    // Checked before anything touches the network: a missing or malformed wallet file is the
    // likelier mistake and costs nothing to detect, so it should not be reported as a confusing
    // Telegram error behind a failed round trip.
    val owner = OwnerConfig.load(File(config.walletFile), config.testnet)
    println("Collecting tips for ${owner.name} -> ${owner.raw}")
    if (owner.chatId == null) {
        println("No ownerChatId set - confirmations go to whichever chat the tip was raised in.")
    }

    val dataSource = Database.connect(config.jdbcUrl, config.jdbcUser, config.jdbcPassword)
    val store = TipStore(dataSource)
    val client = OkHttpTelegramClient(config.telegramBotToken)

    // Asked for rather than configured. The username is needed to build share links, and
    // reading it from the token that is already in hand means it cannot drift out of sync
    // with the bot the token actually belongs to.
    val username = client.execute(GetMe()).userName

    val handler = CommandHandler(store, owner, testnet = config.testnet, botUsername = username)

    // The chain watcher runs alongside the bot rather than inside it: confirming a payment is
    // driven by the blockchain, not by anyone sending a message, so it cannot live in the
    // update loop. Daemon, so Ctrl+C is not held up waiting for the current sleep to finish.
    val poller = TipPoller(
        store = store,
        events = TonApiClient(config.tonApiBaseUrl, config.tonApiKey),
        notifier = { chatId, text ->
            client.execute(SendMessage.builder().chatId(chatId.toString()).text(text).build())
        },
        ownerChatId = owner.chatId,
    )
    Thread({ poller.runForever(config.pollSeconds) }, "tip-poller").apply { isDaemon = true }.start()

    TelegramBotsLongPollingApplication().use { app ->
        app.registerBot(config.telegramBotToken, TipBot(handler, client))
        println("@$username is running on ${if (config.testnet) "testnet" else "mainnet"}. Ctrl+C to stop.")
        Thread.currentThread().join()
    }
}
