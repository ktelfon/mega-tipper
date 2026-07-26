package dev.tipbot.spike

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
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
    private val client: OkHttpTelegramClient,
) : LongPollingSingleThreadUpdateConsumer {

    override fun consume(update: Update) {
        if (!update.hasMessage() || !update.message.hasText()) return

        val chatId = update.message.chatId
        val text = update.message.text

        val reply = try {
            handler.handle(chatId, text, Instant.now().epochSecond)
        } catch (e: Exception) {
            // One bad message must not take the bot down for everyone else.
            System.err.println("Failed handling message from $chatId: ${e.message}")
            CommandHandler.Reply("Something went wrong handling that. Try again in a moment.")
        }

        try {
            client.execute(SendMessage(chatId.toString(), reply.text))
        } catch (e: Exception) {
            System.err.println("Failed replying to $chatId: ${e.message}")
        }
    }
}

fun main() {
    val config = Config.load()
    println("Starting tip bot: $config")

    val dataSource = Database.connect(config.jdbcUrl, config.jdbcUser, config.jdbcPassword)
    val store = TipStore(dataSource)
    val handler = CommandHandler(store, testnet = config.testnet)
    val client = OkHttpTelegramClient(config.telegramBotToken)

    TelegramBotsLongPollingApplication().use { app ->
        app.registerBot(config.telegramBotToken, TipBot(handler, client))
        println("Bot is running on ${if (config.testnet) "testnet" else "mainnet"}. Ctrl+C to stop.")
        Thread.currentThread().join()
    }
}
