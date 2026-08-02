package dev.tipbot.spike

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds the payment links a tipper taps.
 *
 * Two forms are produced for the same invoice, because they are consumed differently:
 *
 * - [tonUri] is the open `ton://` scheme any TON wallet registers. It goes in the message
 *   *body*: Telegram rejects inline-button URLs whose scheme is not http(s) or tg, so a
 *   `ton://` link cannot be a button.
 * - [tonkeeperUrl] is an https universal link, which can be a button.
 *
 * Both carry the same three parameters, and the comment is the one that matters: it is the
 * nonce [TipMatcher] looks for. A wallet that drops or alters the comment produces a payment
 * that can never be matched, which is why the amount is pinned in the link too rather than
 * being left for the tipper to type.
 */
object TipLink {

    fun tonUri(userFriendlyAddress: String, amountNano: Long, comment: String): String =
        "ton://transfer/$userFriendlyAddress?amount=$amountNano&text=${encode(comment)}"

    fun tonkeeperUrl(userFriendlyAddress: String, amountNano: Long, comment: String): String =
        "https://app.tonkeeper.com/transfer/$userFriendlyAddress?amount=$amountNano&text=${encode(comment)}"

    /**
     * Nonces are `tip_` + lowercase hex today, so nothing here needs escaping - but the
     * comment is the field most likely to grow a human-readable prefix later, and an
     * unescaped `&` in a query string silently truncates the amount.
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
