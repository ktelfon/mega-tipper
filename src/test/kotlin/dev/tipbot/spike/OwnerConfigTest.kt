package dev.tipbot.spike

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file is hand-edited and redeployed, so these are mostly about what a mistake in it does.
 * Every rejection here is a deployment that refuses to start rather than one that runs and
 * quietly sends tips nowhere.
 */
class OwnerConfigTest {

    private val dir = Files.createTempDirectory("owner-test")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun load(contents: String, testnet: Boolean = false): OwnerConfig {
        val file = dir.resolve("tipbot.yaml").toFile().apply { writeText(contents) }
        return OwnerConfig.load(file, testnet)
    }

    private fun rejection(contents: String): String =
        assertFailsWith<OwnerConfig.Companion.Invalid> { load(contents) }.message!!

    @Test
    fun `a wallet and a name load, and the address is stored canonically`() {
        val owner = load("name: \"@user123\"\nwallet: \"$EQ\"")

        assertEquals("@user123", owner.name)
        // Whichever spelling was pasted, matching compares against TonAPI's raw form.
        assertEquals(RAW, owner.raw)
        assertNull(owner.chatId)
    }

    @Test
    fun `the UQ spelling is the same wallet`() {
        assertEquals(RAW, load("wallet: \"$UQ\"").raw)
    }

    @Test
    fun `an optional owner chat id is picked up`() {
        assertEquals(99L, load("wallet: \"$EQ\"\nownerChatId: 99").chatId)
    }

    @Test
    fun `a missing name still runs, since only the wording depends on it`() {
        assertEquals("me", load("wallet: \"$EQ\"").name)
    }

    @Test
    fun `a missing file is refused with instructions rather than a stack trace`() {
        val error = assertFailsWith<OwnerConfig.Companion.Invalid> {
            OwnerConfig.load(dir.resolve("absent.yaml").toFile(), testnet = false)
        }

        assertTrue(error.message!!.contains("tipbot.yaml.example"), error.message!!)
    }

    @Test
    fun `a typo'd address stops the deployment instead of swallowing tips`() {
        val reason = rejection("wallet: \"${EQ.dropLast(1)}X\"")

        assertTrue(reason.contains("checksum"), reason)
    }

    @Test
    fun `an address for the wrong network is refused`() {
        assertTrue(rejection("wallet: \"$KQ\"").contains("testnet"))
    }

    @Test
    fun `a mainnet address is refused on a testnet deployment`() {
        val file = dir.resolve("tipbot.yaml").toFile().apply { writeText("wallet: \"$EQ\"") }

        val error = assertFailsWith<OwnerConfig.Companion.Invalid> { OwnerConfig.load(file, testnet = true) }

        assertTrue(error.message!!.contains("mainnet"), error.message!!)
    }

    @Test
    fun `a missing wallet says there is nowhere to send tips`() {
        assertTrue(rejection("name: \"@user123\"").contains("nowhere"))
    }

    @Test
    fun `malformed yaml is reported as such`() {
        assertTrue(rejection("wallet: \"$EQ\"\n  bad: indent\n bad").contains("YAML"))
    }

    private companion object {
        const val EQ = "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"
        const val UQ = "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI"
        const val KQ = "kQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqKYH"
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
    }
}
