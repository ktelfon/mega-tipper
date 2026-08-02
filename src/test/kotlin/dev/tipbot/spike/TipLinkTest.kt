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
    fun `the button link is https, which is all Telegram allows on a button`() {
        val url = TipLink.tonkeeperUrl(UQ, 1_000_000_000L, "tip_9f3a1c7b2d4e6f80")

        assertTrue(url.startsWith("https://"), url)
        assertTrue(url.contains("amount=1000000000"), url)
        assertTrue(url.contains("text=tip_9f3a1c7b2d4e6f80"), url)
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
