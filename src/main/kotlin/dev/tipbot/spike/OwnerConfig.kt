package dev.tipbot.spike

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

/**
 * Who this bot collects for. **One bot, one person, one wallet.**
 *
 * The bot is personalised: `@tipping_bot_for_user123` exists to collect for user123 and nobody
 * else. That is why there is no wallet list, no per-chat mapping, and no `/setup` - the wallet
 * is baked in at deploy time and nothing said in Telegram can point it somewhere else. Wherever
 * the bot is, whoever asks, the money goes to the same address.
 *
 * Parsed by hand from a [com.fasterxml.jackson.databind.JsonNode] rather than data-bound,
 * because a hand-edited file's errors should read as "wallet: that address failed its checksum"
 * rather than as a Jackson stack trace.
 */
data class OwnerConfig(
    /** Display name, used wherever a reply names who is being tipped. */
    val name: String,
    /** Canonical raw address, already normalized and checksum-verified. */
    val raw: String,
    /**
     * Optional. If set, the owner is also told privately whenever a tip lands - useful when
     * tips arrive in a group the owner does not read, or in a stranger's private chat.
     */
    val chatId: Long?,
) {
    companion object {
        /** Thrown at startup. A deployment with a broken wallet must not run at all. */
        class Invalid(message: String) : Exception(message)

        private val mapper = ObjectMapper(YAMLFactory())

        fun load(file: File, testnet: Boolean): OwnerConfig {
            if (!file.exists()) {
                throw Invalid(
                    "${file.name} not found. Copy tipbot.yaml.example to ${file.name} and set the " +
                        "wallet this bot collects for."
                )
            }

            val root = try {
                mapper.readTree(file)
            } catch (e: Exception) {
                throw Invalid("${file.name} is not valid YAML: ${e.message}")
            } ?: throw Invalid("${file.name} is empty")

            val address = root.path("wallet").asText("").trim()
            if (address.isEmpty()) {
                throw Invalid("${file.name}: 'wallet' is missing - there is nowhere to send tips.")
            }

            // Validated at boot, not on first payment. A typo'd TON address is a valid-looking
            // string that silently swallows every tip sent to it, and tips sent to one are gone
            // for good - so a deployment that would do that must refuse to start.
            val raw = when (val result = AddressNormalizer.normalize(address, testnet)) {
                is AddressNormalizer.Result.Ok -> result.raw
                is AddressNormalizer.Result.Rejected -> throw Invalid("${file.name}, wallet: ${result.reason}")
            }

            val chatId = root.path("ownerChatId").takeIf { it.isNumber }?.asLong()

            return OwnerConfig(
                name = root.path("name").asText("").trim().ifBlank { "me" },
                raw = raw,
                chatId = chatId,
            )
        }
    }
}
