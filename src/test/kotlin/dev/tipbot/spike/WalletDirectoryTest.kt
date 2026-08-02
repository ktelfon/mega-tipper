package dev.tipbot.spike

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The operator edits this file by hand and redeploys, so the tests are mostly about what a
 * mistake in it does. Every rejection here is a deployment that refuses to start rather than
 * one that runs and quietly sends tips nowhere.
 */
class WalletDirectoryTest {

    private val dir = Files.createTempDirectory("wallets-test")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun file(contents: String) = dir.resolve("tipbot.yaml").toFile().apply { writeText(contents) }

    private fun load(contents: String, testnet: Boolean = false) =
        WalletDirectory.load(file(contents), testnet)

    private fun rejection(contents: String): String =
        assertFailsWith<WalletDirectory.InvalidConfig> { load(contents) }.message!!

    @Test
    fun `a missing file is not an error, just nothing configured`() {
        val absent = WalletDirectory.load(dir.resolve("nope.yaml").toFile(), testnet = false)

        assertTrue(absent.entries.isEmpty())
        assertEquals(false, absent.allowSelfSetup)
    }

    @Test
    fun `entries load and addresses are stored canonically`() {
        val directory = load(
            """
            wallets:
              - chatId: -1001234567890
                label: "Bob's Chat"
                address: "$EQ"
            """.trimIndent()
        )

        val entry = directory.entries.single()
        assertEquals(-1001234567890L, entry.chatId)
        assertEquals("Bob's Chat", entry.label)
        // Whichever spelling the operator pasted, matching compares against TonAPI's raw form.
        assertEquals(RAW, entry.raw)
    }

    @Test
    fun `the UQ spelling normalizes to the same wallet`() {
        val directory = load("wallets:\n  - chatId: 1\n    address: \"$UQ\"")

        assertEquals(RAW, directory.entries.single().raw)
    }

    @Test
    fun `self-setup is off unless the file turns it on`() {
        assertEquals(false, load("wallets: []").allowSelfSetup)
        assertEquals(true, load("allowSelfSetup: true\nwallets: []").allowSelfSetup)
    }

    @Test
    fun `a typo'd address stops the deployment instead of swallowing tips`() {
        val reason = rejection(
            """
            wallets:
              - chatId: 1
                label: "Typo Chat"
                address: "${EQ.dropLast(1)}X"
            """.trimIndent()
        )

        assertTrue(reason.contains("Typo Chat"), "the operator must know which entry: $reason")
        assertTrue(reason.contains("checksum"), reason)
    }

    @Test
    fun `an address for the wrong network is refused`() {
        val reason = rejection("wallets:\n  - chatId: 1\n    address: \"$KQ\"")

        assertTrue(reason.contains("testnet"), reason)
    }

    @Test
    fun `a missing chatId names the entry and says where to find one`() {
        val reason = rejection("wallets:\n  - label: \"No Id\"\n    address: \"$EQ\"")

        assertTrue(reason.contains("No Id"), reason)
        assertTrue(reason.contains("/chatid"), "it should say how to fix it: $reason")
    }

    @Test
    fun `a chatId that is not a number is refused rather than coerced`() {
        // "-100123..." in quotes is a string. Silently parsing it would work until the day it
        // did not, and the failure would look like "the bot ignores that group".
        val reason = rejection("wallets:\n  - chatId: \"not-a-number\"\n    address: \"$EQ\"")

        assertTrue(reason.contains("chatId"), reason)
    }

    @Test
    fun `a missing address is refused`() {
        assertTrue(rejection("wallets:\n  - chatId: 1\n    label: \"Bare\"").contains("address"))
    }

    @Test
    fun `an entry listed twice is refused instead of one silently winning`() {
        val reason = rejection(
            """
            wallets:
              - chatId: 55
                address: "$EQ"
              - chatId: 55
                address: "$OTHER_EQ"
            """.trimIndent()
        )

        assertTrue(reason.contains("55"), reason)
    }

    @Test
    fun `malformed yaml is reported as such`() {
        assertTrue(rejection("wallets:\n  - chatId: 1\n   address: oops\n  bad indent").contains("YAML"))
    }

    @Test
    fun `wallets must be a list, not a single entry`() {
        assertTrue(rejection("wallets:\n  chatId: 1\n  address: \"$EQ\"").contains("list"))
    }

    @Test
    fun `applying the file writes every wallet into storage`() {
        val dataSource = Database.connect("jdbc:sqlite:${dir.resolve("test.db")}")
        try {
            val store = TipStore(dataSource)
            val directory = load(
                """
                wallets:
                  - chatId: -100777
                    address: "$EQ"
                  - chatId: 4242
                    address: "$OTHER_EQ"
                """.trimIndent()
            )

            WalletDirectory.apply(directory, store, NOW)

            assertEquals(RAW, store.findCreator(-100777L)?.rawAddress)
            assertEquals(OTHER_RAW, store.findCreator(4242L)?.rawAddress)
        } finally {
            (dataSource as? com.zaxxer.hikari.HikariDataSource)?.close()
        }
    }

    @Test
    fun `re-applying an edited file replaces the old wallet`() {
        // The file is the source of truth, so a changed address must win on the next boot.
        val dataSource = Database.connect("jdbc:sqlite:${dir.resolve("edited.db")}")
        try {
            val store = TipStore(dataSource)

            WalletDirectory.apply(load("wallets:\n  - chatId: 9\n    address: \"$EQ\""), store, NOW)
            WalletDirectory.apply(load("wallets:\n  - chatId: 9\n    address: \"$OTHER_EQ\""), store, NOW)

            assertEquals(OTHER_RAW, store.findCreator(9L)?.rawAddress)
        } finally {
            (dataSource as? com.zaxxer.hikari.HikariDataSource)?.close()
        }
    }

    private companion object {
        const val EQ = "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"
        const val UQ = "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI"
        const val KQ = "kQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqKYH"
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        const val OTHER_EQ = "EQCXJkOVvWWiVaQpsRMmyEEot9cP_teUmrrjA21Qa6OGIeng"
        const val OTHER_RAW = "0:97264395bd65a255a429b11326c84128b7d70ffed7949abae3036d506ba38621"
        const val NOW = 1_774_200_000L
    }
}
