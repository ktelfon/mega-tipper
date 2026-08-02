package dev.tipbot.spike

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

/**
 * The operator's wallet file: which chats this deployment collects tips for, and where the
 * money goes. Edited by hand and applied on restart.
 *
 * This exists because the bot is run *for* people, not signed up *by* them. A group owner asks
 * for a tip bot; the operator adds an entry and deploys. Nobody in the group is asked to paste
 * an address, and nobody in the group can change one.
 *
 * The file is authoritative. Every entry is re-applied to the database on each boot, so the
 * file is the thing you edit and the database is just where the poller reads from.
 *
 * Parsed by hand from a [com.fasterxml.jackson.databind.JsonNode] rather than data-bound,
 * because a hand-edited file's errors need to name the offending entry - "entry 2 (Bob's
 * Chat): missing address" beats a Jackson stack trace.
 */
object WalletDirectory {

    /**
     * @property chatId  the group (negative) or user (positive) whose tips these are
     * @property label   operator-facing name, for logs and error messages only
     * @property raw     canonical address, already normalized and checksum-verified
     */
    data class Entry(val chatId: Long, val label: String, val raw: String)

    data class Directory(
        val entries: List<Entry>,
        /** Whether `/setup` in a chat may change a wallet. Off by default: the file decides. */
        val allowSelfSetup: Boolean,
    )

    /** Thrown at startup. Running with a broken wallet file sends tips nowhere. */
    class InvalidConfig(message: String) : Exception(message)

    private val mapper = ObjectMapper(YAMLFactory())

    /** An absent file is not an error - it just means nothing is configured yet. */
    fun load(file: File, testnet: Boolean): Directory {
        if (!file.exists()) return Directory(emptyList(), allowSelfSetup = false)

        val root = try {
            mapper.readTree(file)
        } catch (e: Exception) {
            throw InvalidConfig("${file.name} is not valid YAML: ${e.message}")
        } ?: return Directory(emptyList(), allowSelfSetup = false)

        val allowSelfSetup = root.path("allowSelfSetup").asBoolean(false)

        val walletsNode = root.path("wallets")
        if (walletsNode.isMissingNode || walletsNode.isNull) {
            return Directory(emptyList(), allowSelfSetup)
        }
        if (!walletsNode.isArray) {
            throw InvalidConfig("${file.name}: 'wallets' must be a list of entries")
        }

        val entries = walletsNode.mapIndexed { index, node ->
            // 1-based, matching how a person counts entries when looking at the file.
            val position = index + 1
            val label = node.path("label").asText("").ifBlank { "entry $position" }

            val chatId = node.path("chatId").takeIf { it.isNumber }?.asLong()
                ?: throw InvalidConfig(
                    "${file.name}, entry $position ($label): 'chatId' is missing or not a number. " +
                        "Send /chatid in the group to find it."
                )

            val address = node.path("address").asText("").trim()
            if (address.isEmpty()) {
                throw InvalidConfig("${file.name}, entry $position ($label): 'address' is missing")
            }

            // Validated here rather than on first payment. A typo'd address is a valid-looking
            // string that silently swallows every tip, so the deployment must refuse to start.
            when (val result = AddressNormalizer.normalize(address, testnet)) {
                is AddressNormalizer.Result.Ok -> Entry(chatId, label, result.raw)
                is AddressNormalizer.Result.Rejected ->
                    throw InvalidConfig("${file.name}, entry $position ($label): ${result.reason}")
            }
        }

        val duplicates = entries.groupBy { it.chatId }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw InvalidConfig(
                "${file.name}: chat id(s) ${duplicates.joinToString()} appear more than once. " +
                    "The last one would silently win, so fix the file instead."
            )
        }

        return Directory(entries, allowSelfSetup)
    }

    /** Writes the file's wallets into storage, so the file is what a restart applies. */
    fun apply(directory: Directory, store: TipStore, now: Long) {
        directory.entries.forEach { store.upsertCreator(it.chatId, it.raw, now) }
    }
}
