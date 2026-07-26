package dev.tipbot.spike

/**
 * Turns an incoming message into a reply. Pure: no network, no Telegram types, so the whole
 * conversation can be tested without a bot token.
 *
 * Deliberately stateless. `/setup` followed by a bare address works because *any* message
 * that parses as a TON address is treated as a wallet registration - no "awaiting input"
 * flag to lose on restart, and no way for a redeploy to strand a half-finished conversation.
 */
class CommandHandler(
    private val store: TipStore,
    private val testnet: Boolean,
) {

    data class Reply(val text: String)

    fun handle(chatId: Long, text: String, now: Long): Reply {
        val trimmed = text.trim()
        // "/setup@my_bot arg" - Telegram appends the bot username in groups.
        val command = trimmed.substringBefore(' ').substringBefore('@').lowercase()
        val argument = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()

        return when {
            command == "/start" || command == "/help" -> welcome()
            command == "/setup" && argument.isNotEmpty() -> registerWallet(chatId, argument, now)
            command == "/setup" -> Reply(
                "Send me your TON wallet address and I'll register it for tips.\n\n" +
                    "It looks like EQ... or UQ... - copy it from Tonkeeper or whichever wallet you use."
            )
            command == "/wallet" -> showWallet(chatId)
            trimmed.startsWith("/") -> Reply("I don't know that command. Try /help.")
            // Not a command: if it parses as an address, treat it as registration.
            else -> registerWallet(chatId, trimmed, now)
        }
    }

    private fun welcome() = Reply(
        """
        Hi! I take tips in TON and send them straight to your wallet.

        Nothing is held for you - tips go wallet to wallet, and I only watch the chain to
        confirm they arrived.

        /setup - register the wallet that receives your tips
        /wallet - show the wallet I have on file
        """.trimIndent()
    )

    private fun registerWallet(chatId: Long, input: String, now: Long): Reply =
        when (val result = AddressNormalizer.normalize(input, testnet)) {
            is AddressNormalizer.Result.Ok -> {
                store.upsertCreator(chatId, result.raw, now)
                Reply(
                    "Wallet registered.\n\n" +
                        "${result.raw}\n\n" +
                        "Tips sent to you will land here. Send another address any time to change it."
                )
            }

            is AddressNormalizer.Result.Rejected -> Reply(result.reason)
        }

    private fun showWallet(chatId: Long): Reply {
        val creator = store.findCreator(chatId)
            ?: return Reply("You haven't registered a wallet yet. Send /setup to get started.")

        return Reply("Your tips go to:\n\n${creator.rawAddress}")
    }
}
