package dev.tipbot.spike

import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract against SQLite, using a real file rather than an in-memory database so the
 * `state survives a restart` case genuinely reopens something from disk.
 */
class SqliteTipStoreTest : TipStoreContractTest() {

    private val dir: Path = Files.createTempDirectory("tipstore-sqlite")

    override fun connect(): DataSource =
        Database.connect("jdbc:sqlite:${dir.resolve("test.db")}")

    @kotlin.test.AfterTest
    fun cleanUpFiles() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a database from the multi-wallet era is upgraded, not broken`() {
        // CREATE TABLE IF NOT EXISTS does nothing to an existing table, so the renamed column
        // was invisible to it and the first insert failed with "no such column" at runtime.
        val legacy = java.nio.file.Files.createTempDirectory("legacy-db").resolve("old.db")
        val url = "jdbc:sqlite:$legacy"

        java.sql.DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE creators (telegram_chat_id BIGINT PRIMARY KEY, raw_address TEXT, created_at BIGINT)")
                st.executeUpdate(
                    """
                    CREATE TABLE tips (
                        nonce TEXT PRIMARY KEY, creator_chat_id BIGINT NOT NULL, tipper_chat_id BIGINT,
                        raw_address TEXT NOT NULL, amount_nano BIGINT NOT NULL, status TEXT NOT NULL,
                        event_id TEXT, sender_address TEXT, created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL, confirmed_at BIGINT
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    "INSERT INTO tips VALUES ('tip_old', 42, 99, '$RAW', $ONE_TON, 'PENDING', NULL, NULL, $NOW, ${NOW + 900}, NULL)"
                )
            }
        }

        val upgraded = Database.connect(url)
        try {
            val store = TipStore(upgraded)

            // The old row survived, and its creator_chat_id is now readable as origin_chat_id.
            assertEquals(42L, store.findTip("tip_old")?.originChatId)
            // And new writes work against the upgraded schema.
            val fresh = store.createTip(7, 8, RAW, ONE_TON, NOW)
            assertEquals(7L, store.findTip(fresh.nonce)?.originChatId)
        } finally {
            (upgraded as? HikariDataSource)?.close()
            legacy.parent.toFile().deleteRecursively()
        }
    }
}
