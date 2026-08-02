package dev.tipbot.spike

import java.math.BigDecimal

/**
 * Parses and renders tip amounts.
 *
 * All arithmetic is [BigDecimal], never [Double]. `0.1 + 0.2` in binary floating point is
 * not `0.3`, and [TipMatcher] compares the expected amount exactly, with no tolerance - so a
 * single bit of rounding error here produces an invoice that a correct payment can never
 * satisfy. The user's money would be gone and the tip would never confirm.
 */
object TipAmount {

    const val NANO_PER_TON = 1_000_000_000L

    /**
     * Below this a tip costs more in gas than it delivers - a TON transfer burns roughly
     * 0.005 TON in fees, so a 0.001 TON tip is a rounding error the creator pays for.
     */
    const val MIN_NANO = 10_000_000L // 0.01 TON

    /** A fat-fingered extra digit should be refused, not turned into an invoice. */
    const val MAX_NANO = 10_000L * NANO_PER_TON

    sealed interface Result {
        data class Ok(val nano: Long) : Result

        /** @property reason user-facing text, safe to send straight back as a reply */
        data class Rejected(val reason: String) : Result
    }

    fun parse(input: String): Result {
        // "1 TON", "1ton" - people include the unit, and it carries no information we need.
        val cleaned = input.trim().removeSuffix("TON").removeSuffix("ton").removeSuffix("Ton").trim()

        if (cleaned.isEmpty()) {
            return Result.Rejected("How much? Send an amount in TON, like 1 or 0.5.")
        }

        // BigDecimal accepts scientific notation ("1e9") and a leading +, which no one types
        // by accident. Restricting the alphabet up front keeps the surprising parses out.
        if (!cleaned.matches(Regex("""\d*\.?\d+"""))) {
            return Result.Rejected(
                "I couldn't read \"$cleaned\" as an amount. Use plain digits with a dot, like 1 or 0.5."
            )
        }

        val ton = BigDecimal(cleaned)

        // Sub-nanoTON cannot be represented on-chain, so it cannot be paid exactly.
        if (ton.scale() > 9) {
            return Result.Rejected("TON only goes to 9 decimal places. Try a rounder number.")
        }

        val nano = ton.movePointRight(9).toBigIntegerExact()

        return when {
            nano < BigDecimal(MIN_NANO).toBigInteger() -> Result.Rejected(
                "That's too small - the network fee would cost more than the tip. " +
                    "Minimum is ${format(MIN_NANO)} TON."
            )

            nano > BigDecimal(MAX_NANO).toBigInteger() -> Result.Rejected(
                "That's over the ${format(MAX_NANO)} TON limit. Check for an extra digit."
            )

            else -> Result.Ok(nano.toLong())
        }
    }

    /** nanoTON to a human string: 1_500_000_000 -> "1.5", 1_000_000_000 -> "1". */
    fun format(nano: Long): String =
        BigDecimal(nano).movePointLeft(9).stripTrailingZeros().toPlainString()
}
