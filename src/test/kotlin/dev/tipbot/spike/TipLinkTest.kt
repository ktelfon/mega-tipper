package dev.tipbot.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TipLinkTest {

    @Test
    fun `the ton uri carries address, nanoTON amount and comment`() {
        assertEquals(
            "ton://transfer/$UQ?amount=1500000000&text=tip_9f3a1c7b2d4e6f80",
            TipLink.tonUri(UQ, 1_500_000_000L, "tip_9f3a1c7b2d4e6f80"),
        )
    }

    @Test
    fun `every wallet button is https, which is all Telegram allows on a button`() {
        TipLink.WALLETS.forEach { wallet ->
            val url = wallet.url(UQ, 1_000_000_000L, "tip_9f3a1c7b2d4e6f80")

            assertTrue(url.startsWith("https://"), "${wallet.label}: $url")
            assertTrue(url.contains(UQ), "${wallet.label} must name the recipient: $url")
            assertTrue(url.contains("amount=1000000000"), "${wallet.label}: $url")
            assertTrue(url.contains("text=tip_9f3a1c7b2d4e6f80"), "${wallet.label} must carry the nonce: $url")
        }
    }

    @Test
    fun `more than one wallet is offered, so nobody is forced to install a particular app`() {
        assertTrue(TipLink.WALLETS.size >= 3, TipLink.WALLETS.toString())
        assertEquals(TipLink.WALLETS.size, TipLink.WALLETS.map { it.label }.toSet().size, "labels must be distinct")
    }

    @Test
    fun `the expiry rides along so a wallet can refuse a payment we would not credit`() {
        val uri = TipLink.tonUri(UQ, 1L, "tip_x", expiresAt = 1_774_200_900L)

        assertTrue(uri.contains("exp=1774200900"), uri)
        assertTrue(!TipLink.tonUri(UQ, 1L, "tip_x").contains("exp="), "omitted when not given")
    }

    @Test
    fun `a comment with query characters is escaped, not left to truncate the amount`() {
        val uri = TipLink.tonUri(UQ, 1_000_000_000L, "tip&amount=1")

        assertTrue(uri.contains("text=tip%26amount%3D1"), uri)
        assertTrue(uri.contains("amount=1000000000"), "the real amount must survive: $uri")
    }

    @Test
    fun `spaces encode as percent-20, which every wallet accepts`() {
        // URLEncoder emits "+" for a space, which is form encoding - wrong in a URI path query
        // for wallets that read the comment literally.
        assertTrue(TipLink.tonUri(UQ, 1L, "a b").contains("text=a%20b"))
    }

    private companion object {
        const val UQ = "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI"
    }
}
