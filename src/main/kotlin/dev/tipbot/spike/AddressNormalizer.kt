package dev.tipbot.spike

import org.ton.ton4j.address.Address

/**
 * Converts whatever address form a creator pastes into the one canonical form we store
 * and compare against: raw `0:<64 hex chars>`, which is what TonAPI reports in events.
 *
 * The same wallet has four user-friendly spellings (EQ/UQ mainnet, kQ/0Q testnet) that
 * differ only in a flag byte, plus the raw form. Storing whatever the user happened to
 * paste means a real payment can arrive and still fail to match, because the stored
 * string never equals the string TonAPI returns.
 *
 * Rejection is as important as conversion here: an address that fails its checksum is a
 * typo, and tips sent to a typo'd address are gone for good. Better to refuse at /setup
 * than to silently accept a wallet nobody owns.
 *
 * Checksum and base64url handling come from ton4j rather than being hand-rolled, since
 * this code decides where money goes.
 */
object AddressNormalizer {

    sealed interface Result {
        /** @property raw canonical `0:<hex>` form, safe to store and compare against TonAPI */
        data class Ok(val raw: String, val bounceable: Boolean, val testnet: Boolean) : Result

        /** @property reason user-facing explanation, safe to show in a Telegram reply */
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param input    raw user input; surrounding whitespace is tolerated
     * @param testnet  which network the bot is running against. An address for the other
     *                 network is rejected, because watching it would never see a payment.
     */
    fun normalize(input: String, testnet: Boolean = false): Result {
        val trimmed = input.trim()

        if (trimmed.isEmpty()) {
            return Result.Rejected("That looks empty - send me your TON wallet address.")
        }

        // ton4j accepts both raw and user-friendly forms, and verifies the CRC16 on the latter.
        //
        // Note it signals bad input by throwing java.lang.Error ("Wrong crc16 hashsum",
        // "User-friendly address should contain strictly 48 characters") rather than an
        // Exception, so catching Exception alone lets malformed input escape. We catch
        // Throwable and rethrow only the genuinely fatal cases, so a real JVM problem or a
        // missing class still surfaces instead of being reported as "bad address".
        val address = try {
            Address.of(trimmed)
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is LinkageError) throw t
            return Result.Rejected(
                "That doesn't look like a valid TON address. Check for a typo - " +
                    "addresses carry a checksum, and this one didn't pass."
            )
        }

        // Masterchain (-1) is for validators and system contracts, not for receiving tips.
        if (address.wc.toInt() != 0) {
            return Result.Rejected(
                "That's a masterchain address (workchain ${address.wc}). " +
                    "Send a normal wallet address instead."
            )
        }

        if (address.isTestOnly != testnet) {
            return Result.Rejected(
                if (address.isTestOnly) {
                    "That's a testnet address, but I'm watching mainnet - it would never receive tips."
                } else {
                    "That's a mainnet address, but I'm running on testnet."
                }
            )
        }

        return Result.Ok(
            raw = address.toRaw(),
            bounceable = address.isBounceable,
            testnet = address.isTestOnly,
        )
    }

    /**
     * Renders a stored raw address back into the form a wallet expects in a payment link.
     *
     * **Non-bounceable on purpose.** A bounceable transfer to a wallet that has never sent a
     * transaction - so its contract is not yet deployed - is returned to the sender, minus
     * fees. That is exactly the creator who just installed a wallet to collect tips, so the
     * bounceable form would fail precisely for new users. Non-bounceable lands either way.
     *
     * @param raw canonical `0:<hex>` form as produced by [normalize] and stored
     */
    fun toUserFriendly(raw: String, testnet: Boolean = false): String {
        val address = Address.of(raw)
        return if (testnet) address.toNonBounceableTestnet() else address.toNonBounceable()
    }
}
