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
        /**
         * A broadcast channel. Subscribers cannot post in one at all, so a command here can only
         * have come from an admin - and the useful response is to publish something subscribers
         * can *tap*, since they can never type.
         */
        val isChannel: Boolean = false,
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
            // In a channel this publishes a tip card rather than starting a conversation.
            "tip" -> if (message.isChannel) tipCard() else tip(message, argument, now)
            "wallet" -> Reply("Tips go straight to ${owner.name}'s wallet:\n\n${friendlyAddress()}")
            "link" -> Reply("Anyone can tip ${owner.name} here:\n\nhttps://t.me/$botUsername")
            // An unknown "/command" in a group belongs to someone else. Only answer in private.
            else -> if (message.isGroup || message.isChannel) null
            else Reply("I don't know that command. Try /help.")
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

    /**
     * The card the bot posts into a channel. Its button is a deep link into the bot's own private
     * chat, not a callback, for two reasons: an invoice posted back into the channel would spam
     * it with every subscriber's payment link, and Telegram refuses to message anyone who has
     * never opened a chat with the bot - which tapping the link does by definition.
     */
    private fun tipCard() = Reply(
        "Enjoying this? You can tip ${owner.name} in TON.\n\n" +
            "It goes straight from your wallet to theirs - nothing is held in between.",
        buttons = listOf(Button.Url("Tip ${owner.name}", "https://t.me/$botUsername?start=tip")),
    )

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
        // Flood guard. Without it one person tapping repeatedly fills the pending table, and
        // every entry costs the poller a TonAPI request per cycle until it expires - so the
        // cost of spamming is paid by the rate limit every real tipper shares.
        //
        // Counted per tipper rather than per chat: in a group, several people tipping at once is
        // the normal case and must not look like abuse.
        val live = store.countLivePending(tipperChatId, now)
        if (live >= MAX_LIVE_INVOICES) {
            return Reply(
                "You already have $live tip requests open. Pay one of them, or wait a few " +
                    "minutes for them to expire, and then I can make you another."
            )
        }

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

            Pick your wallet below. Using something else? Open this link:
            ${TipLink.tonUri(address, amountNano, tip.nonce, tip.expiresAt)}

            Paying by hand, in Telegram Wallet or anywhere else? Copy these three:
            Address: $address
            Amount:  $amount TON
            Comment: ${tip.nonce}

            The comment is how I recognise your payment - change it and the tip cannot be
            matched. This request is good for $minutes minutes.
            """.trimIndent(),
            // One button per wallet, so a tipper taps the one they already have instead of being
            // sent to install a particular app.
            buttons = TipLink.WALLETS.map { wallet ->
                Button.Url(wallet.label, wallet.url(address, amountNano, tip.nonce))
            // Opens Telegram Wallet, which cannot be deep-linked into a prefilled transfer.
            // The tipper pastes the three values above.
            } + Button.Url("Telegram Wallet", TipLink.TELEGRAM_WALLET),
        )
    }

    private companion object {
        const val CALLBACK_TIP = "tip"

        /**
         * Live invoices one tipper may hold at once. Three is enough to change your mind about
         * the amount twice, and low enough that filling the table is not worth anyone's time -
         * with a 15 minute expiry it caps a single spammer at three rows per quarter hour.
         */
        const val MAX_LIVE_INVOICES = 3

        /** Small enough that the first one is an easy impulse tap in a group. */
        val PRESET_NANO = listOf(
            TipAmount.NANO_PER_TON / 2,
            TipAmount.NANO_PER_TON,
            TipAmount.NANO_PER_TON * 5,
        )
    }
}
