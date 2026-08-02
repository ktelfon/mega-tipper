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
        }
    }

    private fun handleMessage(update: Update) {
        val chatId = update.message.chatId
        val text = update.message.text

        // Log the command only, never the full message: a wallet address is not secret, but
        // there is no reason to write whatever else people type into the operator's logs.
        log.info("chat {} -> {}", chatId, text.trim().substringBefore(' ').take(32))

        val reply = safely(chatId) { handler.handle(chatId, text, Instant.now().epochSecond) }
        send(chatId, reply)
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

        val reply = safely(chatId) { handler.handleCallback(chatId, query.data, Instant.now().epochSecond) }
        send(chatId, reply)
    }

    /** One bad message must not take the bot down for everyone else. */
    private fun safely(chatId: Long, block: () -> CommandHandler.Reply): CommandHandler.Reply =
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

    val dataSource = Database.connect(config.jdbcUrl, config.jdbcUser, config.jdbcPassword)
    val store = TipStore(dataSource)
    val client = OkHttpTelegramClient(config.telegramBotToken)

    // Asked for rather than configured. The username is needed to build share links, and
    // reading it from the token that is already in hand means it cannot drift out of sync
    // with the bot the token actually belongs to.
    val username = client.execute(GetMe()).userName

    val handler = CommandHandler(store, testnet = config.testnet, botUsername = username)

    TelegramBotsLongPollingApplication().use { app ->
        app.registerBot(config.telegramBotToken, TipBot(handler, client))
        println("@$username is running on ${if (config.testnet) "testnet" else "mainnet"}. Ctrl+C to stop.")
        Thread.currentThread().join()
    }
}
