package dev.tipbot.spike

/**
 * Turns an incoming message into a reply, or into silence. Pure apart from the store: no
 * network, no Telegram types, so the whole conversation can be tested without a bot token.
 *
 * **One bot, one person, one wallet.** The address comes from [OwnerConfig] at deploy time, so
 * there is nothing to register, nothing to look up, and no message anyone can send that points
 * the money somewhere else. Wherever the bot is and whoever asks, tips go to the same address.
 *
 * Stateless: there is no "awaiting input" flag anywhere. The amount travels in the button
 * payload, so a tap on a button posted days ago still works after a restart.
 *
 * **Groups are not private chats.** A bot in a group sees traffic meant for other people and
 * other bots, so unrecognised text is ignored rather than answered. Getting this wrong makes the
 * bot a spammer that gets removed within minutes.
 */
class CommandHandler(
    private val store: TipStore,
    private val owner: OwnerConfig,
    private val testnet: Boolean,
    private val botUsername: String,
) {

    /**
     * One message, with the context needed to answer it safely in a group.
     *
     * @property chatId where to reply, and where the confirmation will be announced
     * @property userId who sent it - the tipper, which in a group is not [chatId]
     */
    data class Incoming(
        val chatId: Long,
        val userId: Long,
        val text: String,
        val isGroup: Boolean = false,
    )

    /** A button label plus what tapping it does. [TipBot] renders these; nothing here knows Telegram. */
    sealed interface Button {
        val label: String

        /** Opens a link. Telegram only accepts http(s) and tg schemes here - never `ton://`. */
        data class Url(override val label: String, val url: String) : Button

        /** Sends [data] back to the bot, handled by [handleCallback]. Telegram caps it at 64 bytes. */
        data class Callback(override val label: String, val data: String) : Button
    }

    data class Reply(val text: String, val buttons: List<Button> = emptyList())

    /** Convenience for a private chat, where the chat id and the user id are the same thing. */
    fun handle(chatId: Long, text: String, now: Long): Reply? =
        handle(Incoming(chatId = chatId, userId = chatId, text = text), now)

    /** @return the reply, or null to stay silent - which in a group is usually the right answer */
    fun handle(message: Incoming, now: Long): Reply? {
        val trimmed = message.text.trim()
        val head = trimmed.substringBefore(' ')

        // "/tip@some_other_bot" is not ours. Groups routinely hold several bots, and answering a
        // command addressed to one of them is how a bot gets removed from a group.
        val mention = head.substringAfter('@', "")
        if (mention.isNotEmpty() && !mention.equals(botUsername, ignoreCase = true)) return null

        // "!tip" as well as "/tip": Telegram only delivers "/" commands to a bot with privacy
        // mode on, but "!" is the friendlier thing to type once privacy mode is off.
        if (!head.startsWith("/") && !head.startsWith("!")) return null

        val verb = head.substringBefore('@').removePrefix("/").removePrefix("!").lowercase()
        val argument = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()

        return when (verb) {
            "start", "help" -> welcome(message)
            "tip" -> tip(message, argument, now)
            "wallet" -> Reply("Tips go straight to ${owner.name}'s wallet:\n\n${friendlyAddress()}")
            "link" -> Reply("Anyone can tip ${owner.name} here:\n\nhttps://t.me/$botUsername")
            // An unknown "/command" in a group belongs to someone else. Only answer in private.
            else -> if (message.isGroup) null else Reply("I don't know that command. Try /help.")
        }
    }

    /**
     * Handles an inline-button tap. The amount travels in the payload, so a tap on a button
     * posted days ago still works after a restart.
     *
     * @param chatId where the button was tapped - the confirmation is announced there
     * @param userId who tapped; in a group this is the tipper, not the chat
     */
    fun handleCallback(chatId: Long, userId: Long, data: String, now: Long): Reply {
        val parts = data.split(':')
        val amountNano = parts.getOrNull(1)?.toLongOrNull()

        if (parts.size != 2 || parts[0] != CALLBACK_TIP || amountNano == null) {
            return Reply("That button is from an older version of me. Send /tip to start again.")
        }

        return issueInvoice(chatId, userId, amountNano, now)
    }

    private fun welcome(message: Incoming): Reply {
        val intro = if (message.isGroup) {
            "I collect tips for ${owner.name}, in TON.\n\n" +
                "Send /tip to pick an amount. The money goes straight from your wallet to theirs " +
                "- I never hold it, and I couldn't spend it if I wanted to."
        } else {
            "Hi! I collect tips for ${owner.name}, in TON.\n\n" +
                "Nothing is held here - tips go wallet to wallet, and I only watch the chain to " +
                "confirm they arrived.\n\n" +
                "Pick an amount below, or send /tip 2.5 for something specific."
        }

        // Offering the amounts straight away, rather than making /start a dead end that only
        // explains that /tip exists.
        return Reply(intro, buttons = amountButtons())
    }

    private fun tip(message: Incoming, argument: String, now: Long): Reply =
        if (argument.isEmpty()) {
            Reply("How much would you like to tip ${owner.name}?", buttons = amountButtons())
        } else {
            when (val amount = TipAmount.parse(argument)) {
                is TipAmount.Result.Rejected -> Reply(amount.reason)
                is TipAmount.Result.Ok -> issueInvoice(message.chatId, message.userId, amount.nano, now)
            }
        }

    private fun amountButtons() = PRESET_NANO.map { nano ->
        Button.Callback("${TipAmount.format(nano)} TON", "$CALLBACK_TIP:$nano")
    }

    private fun friendlyAddress() = AddressNormalizer.toUserFriendly(owner.raw, testnet)

    /**
     * Creates the pending tip and hands back the payment links.
     *
     * The nonce comes from [TipStore.createTip], which draws it from `SecureRandom`. That is a
     * security property, not a style choice: anyone who can guess a live nonce can attach it to
     * their own unrelated transfer and have it credited as someone else's tip. In a group the
     * nonce is posted in the open, so it is unguessable but never secret - the owner is paid
     * either way, and the exposure is mis-attribution rather than theft.
     */
    private fun issueInvoice(originChatId: Long, tipperChatId: Long, amountNano: Long, now: Long): Reply {
        val tip = store.createTip(
            originChatId = originChatId,
            tipperChatId = tipperChatId,
            // Recorded per tip, so changing the configured wallet cannot redirect an invoice
            // that is already in flight.
            rawAddress = owner.raw,
            amountNano = amountNano,
            now = now,
        )

        val address = friendlyAddress()
        val amount = TipAmount.format(amountNano)
        val minutes = (tip.expiresAt - tip.createdAt) / 60

        return Reply(
            text = """
            Tip of $amount TON to ${owner.name}

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

        /** Small enough that the first one is an easy impulse tap in a group. */
        val PRESET_NANO = listOf(
            TipAmount.NANO_PER_TON / 2,
            TipAmount.NANO_PER_TON,
            TipAmount.NANO_PER_TON * 5,
        )
    }
}
