package dev.tipbot.spike

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * Connection setup and schema creation.
 *
 * The schema is written to run unchanged on both SQLite and Postgres, so the storage
 * decision is not load-bearing: point [connect] at a different JDBC URL and everything
 * downstream is unaffected. That matters because cloud hosts without an attached volume
 * have an ephemeral filesystem, and a SQLite file there is wiped on redeploy - taking the
 * in-flight invoices and the double-payout guard with it.
 *
 * Portability rules followed by the DDL below:
 *  - `BIGINT` for ids, amounts and timestamps. Telegram chat ids exceed 32 bits, and
 *    SQLite gives BIGINT integer affinity, so it means the same thing on both engines.
 *  - epoch seconds rather than TIMESTAMP, whose syntax and timezone handling diverge.
 *  - no AUTOINCREMENT/SERIAL. Primary keys are values we generate (the nonce), which
 *    sidesteps the biggest DDL difference between the two engines.
 */
object Database {

    /**
     * @param jdbcUrl e.g. `jdbc:sqlite:/data/tipbot.db` or `jdbc:postgresql://host/db`
     */
    fun connect(jdbcUrl: String, user: String? = null, password: String? = null): DataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            user?.let { username = it }
            password?.let { this.password = it }

            // SQLite permits only one writer at a time; a larger pool would just produce
            // SQLITE_BUSY under concurrent writes instead of waiting.
            maximumPoolSize = if (jdbcUrl.startsWith("jdbc:sqlite")) 1 else 10
        }
        val dataSource = HikariDataSource(config)
        migrate(dataSource)
        return dataSource
    }

    /** Creates the schema if absent. Safe to run on every startup. */
    fun migrate(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS tips (
                        nonce            TEXT   PRIMARY KEY,
                        origin_chat_id   BIGINT NOT NULL,
                        tipper_chat_id   BIGINT,
                        raw_address      TEXT   NOT NULL,
                        amount_nano      BIGINT NOT NULL,
                        status           TEXT   NOT NULL,
                        event_id         TEXT,
                        sender_address   TEXT,
                        created_at       BIGINT NOT NULL,
                        expires_at       BIGINT NOT NULL,
                        confirmed_at     BIGINT
                    )
                    """.trimIndent()
                )

                // The double-payout guard. If the poller sees the same on-chain event twice -
                // across a restart, a retry, or two overlapping polls - the second write is
                // refused by the database rather than by application logic that might race.
                // Both engines allow repeated NULLs here, so pending tips are unaffected.
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_tips_event_id ON tips(event_id)")

                // The poller's hot query: pending tips that have not expired.
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tips_status ON tips(status, expires_at)")

                // The flood-guard query: count active pending tips for a given tipper.
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tips_tipper ON tips(tipper_chat_id, status, expires_at)")
            }

            upgradeFromMultiWallet(conn)
        }
    }

    /**
     * Brings a database created before the bot became single-owner up to date.
     *
     * `CREATE TABLE IF NOT EXISTS` does nothing to a table that already exists, so a renamed
     * column is invisible to it - the schema silently stays stale and the first insert fails at
     * runtime with "no such column". Found exactly that way.
     *
     * `RENAME COLUMN` is supported by SQLite 3.25+ and every Postgres, so one statement covers
     * both engines. Existing tips keep their history rather than being thrown away.
     */
    private fun upgradeFromMultiWallet(conn: java.sql.Connection) {
        val columns = mutableSetOf<String>()
        conn.metaData.getColumns(null, null, "tips", null).use { rs ->
            while (rs.next()) columns += rs.getString("COLUMN_NAME").lowercase()
        }

        conn.createStatement().use { st ->
            if ("creator_chat_id" in columns && "origin_chat_id" !in columns) {
                st.executeUpdate("ALTER TABLE tips RENAME COLUMN creator_chat_id TO origin_chat_id")
            }

            // The wallet no longer lives in the database; it comes from OwnerConfig at startup.
            st.executeUpdate("DROP TABLE IF EXISTS creators")
        }
    }
}
