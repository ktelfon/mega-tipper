package dev.tipbot.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddressNormalizerTest {

    /**
     * All four spellings of one real mainnet wallet, plus its raw form as reported by
     * TonAPI. Verified against tonapi.io: EQCD39... resolves to exactly this raw address.
     */
    @Test
    fun `rendering back for a payment link gives the non-bounceable form`() {
        // Bounceable would be returned to the sender if the creator's wallet is not yet
        // deployed - which is the state of every wallet installed to collect tips.
        assertEquals(UQ, AddressNormalizer.toUserFriendly(RAW))
        assertEquals(ZQ, AddressNormalizer.toUserFriendly(RAW, testnet = true))
    }

    @Test
    fun `a rendered address normalizes back to the same raw form`() {
        // The round trip is what the payment link relies on: what the tipper's wallet sends
        // to must be the address TonAPI later reports, or the tip never matches.
        assertEquals(RAW, ok(AddressNormalizer.toUserFriendly(RAW)).raw)
    }

    private companion object {
        const val RAW = "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        const val EQ = "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N" // bounceable mainnet
        const val UQ = "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI" // non-bounceable mainnet
        const val KQ = "kQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqKYH" // bounceable testnet
        const val ZQ = "0QCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqPvC" // non-bounceable testnet
    }

    private fun ok(input: String, testnet: Boolean = false): AddressNormalizer.Result.Ok {
        val result = AddressNormalizer.normalize(input, testnet)
        assertIs<AddressNormalizer.Result.Ok>(result, "expected $input to be accepted, got $result")
        return result
    }

    private fun rejected(input: String, testnet: Boolean = false): AddressNormalizer.Result.Rejected {
        val result = AddressNormalizer.normalize(input, testnet)
        assertIs<AddressNormalizer.Result.Rejected>(result, "expected $input to be rejected, got $result")
        return result
    }

    @Test
    fun `mainnet spellings all normalize to the same raw form TonAPI reports`() {
        // The whole point: whichever form the creator pastes, we store one string.
        assertEquals(RAW, ok(EQ).raw)
        assertEquals(RAW, ok(UQ).raw)
        assertEquals(RAW, ok(RAW).raw)
    }

    @Test
    fun `testnet spellings normalize to the same raw form`() {
        assertEquals(RAW, ok(KQ, testnet = true).raw)
        assertEquals(RAW, ok(ZQ, testnet = true).raw)
    }

    @Test
    fun `bounceable flag is reported but does not change identity`() {
        // EQ vs UQ is a display preference, not a different wallet.
        assertTrue(ok(EQ).bounceable)
        assertTrue(!ok(UQ).bounceable)
        assertEquals(ok(EQ).raw, ok(UQ).raw)
    }

    @Test
    fun `rejects a typo that breaks the checksum`() {
        // Single character changed. Still looks like an address; the CRC16 catches it.
        // Without this check we would store a wallet nobody owns and lose every tip sent to it.
        val typo = EQ.dropLast(1) + "X"
        assertTrue(rejected(typo).reason.contains("checksum"))
    }

    @Test
    fun `rejects obvious rubbish`() {
        rejected("hello world")
        rejected("0x1234567890abcdef") // an Ethereum-style address
        rejected("")
        rejected("   ")
    }

    @Test
    fun `tolerates surrounding whitespace from a paste`() {
        assertEquals(RAW, ok("  $EQ\n").raw)
    }

    @Test
    fun `rejects a testnet address when running on mainnet`() {
        val reason = rejected(KQ, testnet = false).reason
        assertTrue(reason.contains("testnet"), "reason should explain the network mismatch: $reason")
    }

    @Test
    fun `rejects a mainnet address when running on testnet`() {
        val reason = rejected(EQ, testnet = true).reason
        assertTrue(reason.contains("mainnet"), "reason should explain the network mismatch: $reason")
    }

    @Test
    fun `normalizing is idempotent`() {
        // Feeding our own stored form back in must be stable, or re-running /setup drifts.
        assertEquals(RAW, ok(ok(EQ).raw).raw)
    }
}
