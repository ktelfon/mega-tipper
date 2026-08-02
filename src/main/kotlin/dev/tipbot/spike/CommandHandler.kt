package dev.tipbot.spike

/**
 * Turns an incoming message into a reply, or into silence. Pure apart from the store: no
 * network, no Telegram types, so the whole conversation can be tested without a bot token.
 *
 * Deliberately stateless - there is no "awaiting input" flag anywhere. `/setup` followed by a
 * bare address works because any message that parses as a TON address is a registration, and
 * the tipping flow carries the creator's id in the deep link and in the button payload rather
 * than in memory. Nothing is lost on a restart, and a redeploy cannot strand a half-finished
 * conversation.
 *
 * **Groups are not private chats.** A bot in a group sees traffic meant for other people and
 * other bots, so the rules differ: unrecognised text is ignored rather than answered, and
 * changing the group's payout wallet is restricted to admins. Getting this wrong makes the bot
 * a spammer that gets removed within minutes.
 */
class CommandHandler(
    private val store: TipStore,
    private val testnet: Boolean,
    private val botUsername: String,
) {

    /**
     * One message, with the context needed to answer it safely in a group.
     *
     * @property chatId         where to reply; in a private chat this is also the user's id
     * @property userId         who sent it - the tipper, which in a group is not [chatId]
     * @property replyToUserId  if this replies to someone, who they are: the tip recipient
     * @property senderIsAdmin  resolved lazily, since it costs a Telegram round trip and only
     *                          `/setup` in a group needs the answer
     */
    data class Incoming(
        val chatId: Long,
        val userId: Long,
        val text: String,
        val isGroup: Boolean = false,
        val replyToUserId: Long? = null,
        val replyToName: String? = null,
        val senderIsAdmin: () -> Boolean = { false },
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

        // "/tip@some_other_bot" is not ours. Groups routinely hold several bots, and answering
        // a command addressed to one of them is how a bot gets removed from a group.
        val mention = head.substringAfter('@', "")
        if (mention.isNotEmpty() && !mention.equals(botUsername, ignoreCase = true)) return null

        // "!tip" as well as "/tip": Telegram only delivers "/" commands to a bot with privacy
        // mode on, but "!" is the friendlier thing to type once privacy mode is off.
        val isCommand = head.startsWith("/") || head.startsWith("!")
        val verb = head.substringBefore('@').removePrefix("/").removePrefix("!").lowercase()
        val argument = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()

        if (!isCommand) {
            // In a private chat, a bare address is a registration. In a group it is somebody
            // talking to their friends, and must not be answered.
            return if (message.isGroup) null else registerWallet(message.chatId, trimmed, now)
        }

        return when (verb) {
            // `t.me/<bot>?start=<creatorChatId>` arrives here as "/start <creatorChatId>".
            "start" -> if (argument.isNotEmpty() && !message.isGroup) startPayload(argument) else welcome(message)
            "help" -> welcome(message)
            "setup" -> setup(message, argument, now)
            "wallet" -> showWallet(message)
            "link" -> showShareLink(message)
            "tip" -> tipCommand(message, argument, now)
            // An unknown "/command" in a group belongs to someone else. Only answer in private.
            else -> if (message.isGroup) null else Reply("I don't know that command. Try /help.")
        }
    }

    /**
     * Handles an inline-button tap. The creator and amount travel in the payload, so a tap on
     * a button posted days ago still works after a restart.
     *
     * @param userId who tapped - in a group this is the tipper, not the chat
     */
    fun handleCallback(chatId: Long, userId: Long, data: String, now: Long): Reply {
        val parts = data.split(':')
        val creatorChatId = parts.getOrNull(1)?.toLongOrNull()
        val amountNano = parts.getOrNull(2)?.toLongOrNull()

        if (parts.size != 3 || parts[0] != CALLBACK_TIP || creatorChatId == null || amountNano == null) {
            return Reply("That button is from an older version of me. Send /start to begin again.")
        }

        return issueInvoice(creatorChatId, tipperChatId = userId, amountNano = amountNano, now = now)
    }

    private fun welcome(message: Incoming) = Reply(
        if (message.isGroup) {
            """
            I take tips in TON, straight from one wallet to another. Nothing is ever held here.

            /tip - tip this group's wallet
            /tip - as a reply to someone, tips them instead
            /setup - admins only: set the wallet this group's tips go to
            """.trimIndent()
        } else {
            """
            Hi! I take tips in TON and send them straight to your wallet.

            Nothing is held for you - tips go wallet to wallet, and I only watch the chain to
            confirm they arrived.

            /setup - register the wallet that receives your tips
            /wallet - show the wallet I have on file
            /link - get the tip link to share with your audience
            /tip <amount> - send yourself a test tip

            Add me to a group and people can tip with /tip right there.
            """.trimIndent()
        }
    )

    /**
     * In a group this sets where *the group's* tips go, so it is admin-only. Without that check
     * any member could point the group's earnings at their own wallet.
     */
    private fun setup(message: Incoming, argument: String, now: Long): Reply {
        if (message.isGroup && !message.senderIsAdmin()) {
            return Reply("Only an admin can set the wallet this group's tips go to.")
        }

        if (argument.isEmpty()) {
            return Reply(
                if (message.isGroup) {
                    "Send /setup followed by the TON address this group's tips should go to."
                } else {
                    "Send me your TON wallet address and I'll register it for tips.\n\n" +
                        "It looks like EQ... or UQ... - copy it from Tonkeeper or whichever wallet you use."
                }
            )
        }

        return registerWallet(message.chatId, argument, now, isGroup = message.isGroup)
    }

    private fun registerWallet(chatId: Long, input: String, now: Long, isGroup: Boolean = false): Reply =
        when (val result = AddressNormalizer.normalize(input, testnet)) {
            is AddressNormalizer.Result.Ok -> {
                store.upsertCreator(chatId, result.raw, now)
                if (isGroup) {
                    Reply(
                        "Wallet registered for this group.\n\n${result.raw}\n\n" +
                            "Anyone here can now send /tip and pay straight into it."
                    )
                } else {
                    Reply(
                        "Wallet registered.\n\n${result.raw}\n\n" +
                            "Tips sent to you will land here. Send another address any time to change it.\n\n" +
                            "Your tip link:\n${shareLink(chatId)}\n\n" +
                            "Share it anywhere, or add me to a group and people can tip you with /tip."
                    )
                }
            }

            is AddressNormalizer.Result.Rejected -> Reply(result.reason)
        }

    private fun showWallet(message: Incoming): Reply {
        val creator = store.findCreator(message.chatId)
            ?: return Reply(
                if (message.isGroup) "This group has no wallet yet. An admin can set one with /setup."
                else "You haven't registered a wallet yet. Send /setup to get started."
            )

        return Reply(
            if (message.isGroup) "Tips here go to:\n\n${creator.rawAddress}"
            else "Your tips go to:\n\n${creator.rawAddress}"
        )
    }

    private fun showShareLink(message: Incoming): Reply {
        store.findCreator(message.chatId)
            ?: return Reply("Register a wallet first with /setup, then I can give you a tip link.")

        return Reply("Share this to collect tips:\n\n${shareLink(message.chatId)}")
    }

    private fun shareLink(creatorChatId: Long) = "https://t.me/$botUsername?start=$creatorChatId"

    private fun startPayload(payload: String): Reply {
        val creatorChatId = payload.toLongOrNull()
            ?: return Reply("That tip link looks broken. Ask for a fresh one.")

        return tipMenu(creatorChatId, "them")
    }

    /**
     * `/tip` in a group tips the group's wallet, or - when sent as a reply - the person being
     * replied to. That second form is the one that matters socially: you tip the person who
     * just said the useful thing, without either of you leaving the conversation.
     */
    private fun tipCommand(message: Incoming, argument: String, now: Long): Reply {
        val parts = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (message.isGroup) {
            val recipient = message.replyToUserId ?: message.chatId
            val label = message.replyToName ?: if (message.replyToUserId != null) "them" else "this group"

            return when (parts.size) {
                0 -> tipMenu(
                    recipient,
                    label,
                    // Whose problem it is to fix depends on who was being tipped.
                    if (message.replyToUserId != null) SETUP_IN_DM else "An admin can set one with /setup here.",
                )
                1 -> withAmount(parts[0]) { nano -> issueInvoice(recipient, message.userId, nano, now) }
                else -> Reply("Send /tip on its own to pick an amount, or /tip 1 to go straight there.")
            }
        }

        // Private chat. One argument tips your own wallet, which is how the whole flow gets
        // tested without a second account.
        val (creatorChatId, rawAmount) = when (parts.size) {
            1 -> message.chatId to parts[0]
            2 -> (parts[0].toLongOrNull() ?: return Reply("I couldn't read \"${parts[0]}\" as a creator id."))
                .to(parts[1])

            else -> return Reply(
                "Send /tip <amount> to tip your own wallet as a test, like /tip 1.\n" +
                    "To tip someone else, open their tip link instead."
            )
        }

        return withAmount(rawAmount) { nano -> issueInvoice(creatorChatId, message.userId, nano, now) }
    }

    private inline fun withAmount(raw: String, issue: (Long) -> Reply): Reply =
        when (val amount = TipAmount.parse(raw)) {
            is TipAmount.Result.Rejected -> Reply(amount.reason)
            is TipAmount.Result.Ok -> issue(amount.nano)
        }

    /**
     * The amount picker. [label] names the recipient in a way that reads in the chat it lands
     * in, and [setupHint] tells the right person how to fix a missing wallet - which differs,
     * since a group's wallet is set by an admin in the group and a person's is set in a DM.
     */
    private fun tipMenu(creatorChatId: Long, label: String, setupHint: String = SETUP_IN_DM): Reply {
        store.findCreator(creatorChatId)
            ?: return Reply("There's no wallet set up for $label yet. $setupHint")

        return Reply(
            "Pick an amount to tip $label.\n\n" +
                "It goes straight from your wallet to theirs - I never hold it.",
            buttons = PRESET_NANO.map { nano ->
                Button.Callback("${TipAmount.format(nano)} TON", "$CALLBACK_TIP:$creatorChatId:$nano")
            },
        )
    }

    /**
     * Creates the pending tip and hands back the payment links.
     *
     * The nonce comes from [TipStore.createTip], which draws it from `SecureRandom`. That is a
     * security property, not a style choice: anyone who can guess a live nonce can attach it to
     * their own unrelated transfer and have it credited as someone else's tip. In a group the
     * nonce is posted publicly, so it is only ever unguessable, never secret - the creator is
     * paid either way, and the exposure is mis-attribution rather than theft.
     */
    private fun issueInvoice(creatorChatId: Long, tipperChatId: Long, amountNano: Long, now: Long): Reply {
        val creator = store.findCreator(creatorChatId)
            ?: return Reply("There's no wallet registered for that yet, so there's nowhere to send a tip.")

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

        /** Doubles as the growth loop: every un-set-up recipient is told how to start earning. */
        const val SETUP_IN_DM = "They can start receiving tips by sending me /setup in a private chat."

        /** Small enough that the first one is an easy impulse tap in a group. */
        val PRESET_NANO = listOf(
            TipAmount.NANO_PER_TON / 2,
            TipAmount.NANO_PER_TON,
            TipAmount.NANO_PER_TON * 5,
        )
    }
}
