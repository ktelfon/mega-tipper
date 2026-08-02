package dev.tipbot.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The amount is compared exactly on-chain, with no tolerance, so every case here is a case
 * where being one nanoTON out means a real payment can never be matched.
 */
class TipAmountTest {

    private fun nano(input: String): Long =
        (TipAmount.parse(input) as TipAmount.Result.Ok).nano

    private fun rejection(input: String): String =
        (TipAmount.parse(input) as? TipAmount.Result.Rejected)?.reason
            ?: error("\"$input\" should have been rejected, but parsed to ${nano(input)}")

    @Test
    fun `whole and fractional amounts convert exactly`() {
        assertEquals(1_000_000_000L, nano("1"))
        assertEquals(1_500_000_000L, nano("1.5"))
        assertEquals(10_000_000L, nano("0.01"))
        assertEquals(123_456_789L, nano("0.123456789"))
    }

    @Test
    fun `a value that binary floating point cannot hold survives`() {
        // 0.1 + 0.2 != 0.3 as a Double. Getting this wrong produces an unpayable invoice.
        assertEquals(300_000_000L, nano("0.3"))
        assertEquals(2_900_000_000L, nano("2.9"))
    }

    @Test
    fun `the unit people type is ignored`() {
        assertEquals(1_000_000_000L, nano("1 TON"))
        assertEquals(1_000_000_000L, nano("1ton"))
    }

    @Test
    fun `scientific notation is refused rather than silently multiplied`() {
        // BigDecimal reads "1e9" as a billion TON. Nobody typing into a chat means that.
        rejection("1e9")
    }

    @Test
    fun `a negative amount cannot be turned into an invoice`() {
        rejection("-1")
    }

    @Test
    fun `zero and dust are refused because the fee exceeds the tip`() {
        rejection("0")
        assertTrue(rejection("0.0001").contains("too small"))
    }

    @Test
    fun `sub-nanoTON precision is refused, being unpayable on-chain`() {
        assertTrue(rejection("0.1234567891").contains("9 decimal"))
    }

    @Test
    fun `an extra digit is caught instead of billing someone a fortune`() {
        assertTrue(rejection("100000").contains("limit"))
    }

    @Test
    fun `words and empty input get a reply that says what to do`() {
        assertTrue(rejection("lots").contains("amount"))
        assertTrue(rejection("").contains("amount"))
        rejection("1.2.3")
        rejection("1,5")
    }

    @Test
    fun `formatting round-trips and drops noise zeros`() {
        assertEquals("1", TipAmount.format(1_000_000_000L))
        assertEquals("1.5", TipAmount.format(1_500_000_000L))
        assertEquals("0.01", TipAmount.format(10_000_000L))
        assertEquals(1_500_000_000L, nano(TipAmount.format(1_500_000_000L)))
    }
}
