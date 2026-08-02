package dev.tipbot.spike

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds the payment links a tipper taps.
 *
 * [tonUri] is the open `ton://transfer` scheme every TON wallet registers, and it is what makes
 * this work with wallets nobody here has heard of. It cannot be a button, though: Telegram
 * rejects inline-button URLs whose scheme is not http(s) or tg. So it goes in the message body,
 * and each entry in [WALLETS] contributes an https button that opens one specific wallet.
 *
 * A wallet is only listed here if its universal-link format is documented. Guessing one would
 * produce a button that either fails to open or - far worse - opens without the comment, which
 * sends real money that can never be matched to a tip.
 *
 * All three carry the same three parameters, and the comment is the one that matters: it is the
 * nonce [TipMatcher] looks for. The amount is pinned in the link too, rather than being left for
 * the tipper to type, because the matcher compares it exactly.
 */
object TipLink {

    /** @property label what the button says; short, because it sits next to two others */
    data class Wallet(val label: String, private val base: String) {
        fun url(userFriendlyAddress: String, amountNano: Long, comment: String) =
            "$base$userFriendlyAddress?amount=$amountNano&text=${encode(comment)}"
    }

    /**
     * Ordered by how likely a TON user is to have it. Every format here is from the wallet's own
     * documentation or from docs.ton.org - see the deep-links guide.
     */
    val WALLETS = listOf(
        Wallet("Tonkeeper", "https://app.tonkeeper.com/transfer/"),
        Wallet("Tonhub", "https://tonhub.com/transfer/"),
        Wallet("MyTonWallet", "https://my.tt/transfer/"),
    )

    /**
     * Telegram's own wallet, which cannot be a [Wallet] above.
     *
     * @wallet is a Mini App, so it registers no `ton://` scheme - the fallback link in the
     * message body does nothing for its users - and it publishes no documented transfer deep
     * link, so there is no URL that opens it with the fields filled in. This button only opens
     * Wallet; the tipper copies the three values from the message.
     *
     * Manual entry is acceptable here in a way a guessed deep link is not. The worst case is a
     * mistyped *comment*, which leaves the tip unmatched and unannounced - the money still
     * reaches the owner, because the address is theirs either way. Getting the address wrong is
     * the only unrecoverable mistake, and it is the one field a tipper can paste verbatim.
     *
     * The real fix is TON Connect, which TON Space supports and which carries a comment payload.
     */
    val TELEGRAM_WALLET = "https://t.me/wallet"

    /**
     * The scheme-level link, for any wallet not listed in [WALLETS].
     *
     * @param expiresAt unix seconds the invoice dies. Passed as `exp` so a wallet can refuse a
     *   payment this bot would refuse to credit anyway - better than taking the money and
     *   leaving the tip unmatched.
     */
    fun tonUri(userFriendlyAddress: String, amountNano: Long, comment: String, expiresAt: Long? = null): String =
        buildString {
            append("ton://transfer/$userFriendlyAddress?amount=$amountNano&text=${encode(comment)}")
            if (expiresAt != null) append("&exp=$expiresAt")
        }

    /**
     * Nonces are `tip_` + lowercase hex today, so nothing here needs escaping - but the comment
     * is the field most likely to grow a human-readable prefix later, and an unescaped `&` in a
     * query string silently truncates the amount.
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
