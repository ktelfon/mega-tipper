package dev.tipbot.spike

/**
 * Turns an incoming message into a reply. Pure apart from the store: no network, no Telegram
 * types, so the whole conversation can be tested without a bot token.
 *
 * Deliberately stateless - there is no "awaiting input" flag anywhere. `/setup` followed by a
 * bare address works because *any* message that parses as a TON address is treated as a wallet
 * registration, and the tipping flow carries the creator's id in the deep link and in the
 * button payload rather than in memory. Nothing is lost on a restart, and a redeploy cannot
 * strand a half-finished conversation.
 */
class CommandHandler(
    private val store: TipStore,
    private val testnet: Boolean,
    private val botUsername: String,
) {

    /** A button label plus what tapping it does. [TipBot] renders these; nothing here knows Telegram. */
    sealed interface Button {
        val label: String

        /** Opens a link. Telegram only accepts http(s) and tg schemes here - never `ton://`. */
        data class Url(override val label: String, val url: String) : Button

        /** Sends [data] back to the bot, handled by [handleCallback]. Telegram caps it at 64 bytes. */
        data class Callback(override val label: String, val data: String) : Button
    }

    data class Reply(val text: String, val buttons: List<Button> = emptyList())

    fun handle(chatId: Long, text: String, now: Long): Reply {
        val trimmed = text.trim()
        // "/setup@my_bot arg" - Telegram appends the bot username in groups.
        val command = trimmed.substringBefore(' ').substringBefore('@').lowercase()
        val argument = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()

        return when {
            // `t.me/<bot>?start=<creatorChatId>` arrives here as "/start <creatorChatId>".
            command == "/start" && argument.isNotEmpty() -> openTipMenu(argument)
            command == "/start" || command == "/help" -> welcome()
            command == "/setup" && argument.isNotEmpty() -> registerWallet(chatId, argument, now)
            command == "/setup" -> Reply(
                "Send me your TON wallet address and I'll register it for tips.\n\n" +
                    "It looks like EQ... or UQ... - copy it from Tonkeeper or whichever wallet you use."
            )
            command == "/wallet" -> showWallet(chatId)
            command == "/link" -> showShareLink(chatId)
            command == "/tip" -> tipCommand(chatId, argument, now)
            trimmed.startsWith("/") -> Reply("I don't know that command. Try /help.")
            // Not a command: if it parses as an address, treat it as registration.
            else -> registerWallet(chatId, trimmed, now)
        }
    }

    /**
     * Handles an inline-button tap. The creator and amount travel in the payload, so a tap on
     * a button sent days ago still works after a restart.
     */
    fun handleCallback(chatId: Long, data: String, now: Long): Reply {
        val parts = data.split(':')
        if (parts.size != 3 || parts[0] != CALLBACK_TIP) {
            return Reply("That button is from an older version of me. Send /start to begin again.")
        }

        val creatorChatId = parts[1].toLongOrNull()
        val amountNano = parts[2].toLongOrNull()
        if (creatorChatId == null || amountNano == null) {
            return Reply("That button is from an older version of me. Send /start to begin again.")
        }

        return issueInvoice(creatorChatId, tipperChatId = chatId, amountNano = amountNano, now = now)
    }

    private fun welcome() = Reply(
        """
        Hi! I take tips in TON and send them straight to your wallet.

        Nothing is held for you - tips go wallet to wallet, and I only watch the chain to
        confirm they arrived.

        /setup - register the wallet that receives your tips
        /wallet - show the wallet I have on file
        /link - get the tip link to share with your audience
        /tip <amount> - send yourself a test tip
        """.trimIndent()
    )

    private fun registerWallet(chatId: Long, input: String, now: Long): Reply =
        when (val result = AddressNormalizer.normalize(input, testnet)) {
            is AddressNormalizer.Result.Ok -> {
                store.upsertCreator(chatId, result.raw, now)
                Reply(
                    "Wallet registered.\n\n" +
                        "${result.raw}\n\n" +
                        "Tips sent to you will land here. Send another address any time to change it.\n\n" +
                        "Your tip link:\n${shareLink(chatId)}\n\n" +
                        "Share it anywhere. Anyone who opens it can tip you without me ever holding the money."
                )
            }

            is AddressNormalizer.Result.Rejected -> Reply(result.reason)
        }

    private fun showWallet(chatId: Long): Reply {
        val creator = store.findCreator(chatId)
            ?: return Reply("You haven't registered a wallet yet. Send /setup to get started.")

        return Reply("Your tips go to:\n\n${creator.rawAddress}")
    }

    private fun showShareLink(chatId: Long): Reply {
        store.findCreator(chatId)
            ?: return Reply("Register a wallet first with /setup, then I can give you a tip link.")

        return Reply("Share this to collect tips:\n\n${shareLink(chatId)}")
    }

    private fun shareLink(creatorChatId: Long) = "https://t.me/$botUsername?start=$creatorChatId"

    /** What a tipper sees after opening a creator's share link. */
    private fun openTipMenu(payload: String): Reply {
        val creatorChatId = payload.toLongOrNull()
            ?: return Reply("That tip link looks broken. Ask for a fresh one.")

        store.findCreator(creatorChatId)
            ?: return Reply("That creator hasn't registered a wallet yet, so there's nowhere to send a tip.")

        return Reply(
            "Pick an amount and I'll build the payment link.\n\n" +
                "The tip goes straight from your wallet to theirs - I never hold it.",
            buttons = PRESET_NANO.map { nano ->
                Button.Callback("${TipAmount.format(nano)} TON", "$CALLBACK_TIP:$creatorChatId:$nano")
            },
        )
    }

    /**
     * `/tip <amount>` tips your own registered wallet, which is how you test the full flow
     * without a second account. `/tip <creatorChatId> <amount>` tips someone else - the
     * typed equivalent of tapping a button in their share link.
     */
    private fun tipCommand(chatId: Long, argument: String, now: Long): Reply {
        val parts = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val (creatorChatId, rawAmount) = when (parts.size) {
            1 -> chatId to parts[0]
            2 -> (parts[0].toLongOrNull() ?: return Reply("I couldn't read \"${parts[0]}\" as a creator id."))
                .to(parts[1])

            else -> return Reply(
                "Send /tip <amount> to tip your own wallet as a test, like /tip 1.\n" +
                    "To tip someone else, open their tip link instead."
            )
        }

        return when (val amount = TipAmount.parse(rawAmount)) {
            is TipAmount.Result.Rejected -> Reply(amount.reason)
            is TipAmount.Result.Ok -> issueInvoice(creatorChatId, chatId, amount.nano, now)
        }
    }

    /**
     * Creates the pending tip and hands back the payment links.
     *
     * The nonce comes from [TipStore.createTip], which draws it from `SecureRandom`. That is a
     * security property, not a style choice: anyone who can guess a live nonce can attach it
     * to their own unrelated transfer and have it credited as someone else's tip.
     */
    private fun issueInvoice(creatorChatId: Long, tipperChatId: Long, amountNano: Long, now: Long): Reply {
        val creator = store.findCreator(creatorChatId)
            ?: return Reply("That creator hasn't registered a wallet yet, so there's nowhere to send a tip.")

        val tip = store.createTip(
            creatorChatId = creatorChatId,
            tipperChatId = tipperChatId,
            rawAddress = creator.rawAddress,
            amountNano = amountNano,
            now = now,
        )

        val address = AddressNormalizer.toUserFriendly(creator.rawAddress, testnet)
        val amount = TipAmount.format(amountNano)
        val minutes = (tip.expiresAt - tip.createdAt) / 60

        return Reply(
            text = """
            Tip of $amount TON

            Tap the button to open your wallet, or use this link:
            ${TipLink.tonUri(address, amountNano, tip.nonce)}

            Leave the comment exactly as it is - "${tip.nonce}" is how I recognise your payment.
            Change it and the tip cannot be matched.

            This request is good for $minutes minutes.
            """.trimIndent(),
            buttons = listOf(
                Button.Url("Pay $amount TON", TipLink.tonkeeperUrl(address, amountNano, tip.nonce))
            ),
        )
    }

    private companion object {
        const val CALLBACK_TIP = "tip"

        /** Small enough that the first one is affordable on a testnet faucet's handout. */
        val PRESET_NANO = listOf(
            TipAmount.NANO_PER_TON / 2,
            TipAmount.NANO_PER_TON,
            TipAmount.NANO_PER_TON * 5,
        )
    }
}
