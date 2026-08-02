package dev.tipbot.spike

import java.io.File

/**
 * Runtime configuration, read from the environment with a `.env` file as fallback.
 *
 * Real environment variables win over the file, so a cloud host's injected secrets override
 * whatever is checked out on disk. The `.env` file is gitignored - the bot token is a bearer
 * credential with no second factor, so it must never reach the repository.
 */
data class Config(
    val telegramBotToken: String,
    val tonApiBaseUrl: String,
    val tonApiKey: String?,
    val jdbcUrl: String,
    val jdbcUser: String?,
    val jdbcPassword: String?,
    val pollSeconds: Long,
) {
    val testnet: Boolean get() = tonApiBaseUrl.contains("testnet")

    companion object {
        fun load(envFile: File = File(".env")): Config {
            val fromFile = parseEnvFile(envFile)

            fun get(key: String): String? =
                System.getenv(key)?.takeIf { it.isNotBlank() } ?: fromFile[key]?.takeIf { it.isNotBlank() }

            val token = get("TELEGRAM_BOT_TOKEN")
                ?: error(
                    "TELEGRAM_BOT_TOKEN is not set. Copy .env.example to .env and add the token " +
                        "from @BotFather, or set the environment variable."
                )

            return Config(
                telegramBotToken = token,
                tonApiBaseUrl = get("TONAPI_BASE_URL") ?: "https://tonapi.io",
                tonApiKey = get("TONAPI_KEY"),
                jdbcUrl = get("TIPBOT_JDBC_URL") ?: "jdbc:sqlite:tipbot.db",
                jdbcUser = get("TIPBOT_JDBC_USER"),
                jdbcPassword = get("TIPBOT_JDBC_PASSWORD"),
                // 10s keeps confirmation feeling immediate while staying inside TonAPI's
                // anonymous rate limit for a handful of creators. Raise it, or set TONAPI_KEY,
                // before pointing many creators at one deployment.
                pollSeconds = get("TIP_POLL_SEC")?.toLongOrNull()?.coerceAtLeast(1) ?: 10L,
            )
        }

        /** Minimal `KEY=VALUE` reader. Ignores blanks and `#` comments; tolerates quotes. */
        internal fun parseEnvFile(file: File): Map<String, String> {
            if (!file.exists()) return emptyMap()

            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { line ->
                    val key = line.substringBefore('=').trim()
                    val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                    key to value
                }
        }
    }

    /** Keeps the token out of logs and crash reports. */
    override fun toString(): String =
        "Config(tonApiBaseUrl=$tonApiBaseUrl, testnet=$testnet, jdbcUrl=$jdbcUrl, " +
            "pollSeconds=$pollSeconds, tonApiKey=${if (tonApiKey != null) "set" else "unset"}, " +
            "telegramBotToken=***)"
}
