package dev.tipbot.spike

import java.security.SecureRandom
import java.sql.SQLException
import javax.sql.DataSource

enum class TipStatus { PENDING, CONFIRMED, EXPIRED }

data class Tip(
    val nonce: String,
    val originChatId: Long,
    val tipperChatId: Long?,
    val rawAddress: String,
    val amountNano: Long,
    val status: TipStatus,
    val eventId: String?,
    val senderAddress: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val confirmedAt: Long?,
) {
    /** Hands this tip to [TipMatcher] as the invoice to look for on-chain. */
    fun toInvoice() = TipInvoice(
        commentNonce = nonce,
        recipientAddress = rawAddress,
        expectedNanoTon = amountNano,
        createdAtEpoch = createdAt,
        expiresAtEpoch = expiresAt,
    )
}

/**
 * Storage for tips. Works against SQLite or Postgres unchanged - see [Database].
 *
 * There is no wallet table: this bot collects for exactly one person, whose address comes from
 * [OwnerConfig] at startup. Each tip still records the address it was issued against, so
 * changing the configured wallet cannot silently redirect an invoice already in flight.
 */
class TipStore(private val dataSource: DataSource) {

    /**
     * Issues a pending tip and returns it, with a freshly generated nonce.
     *
     * @param ttlSeconds how long the invoice stays valid. Keep this short: the window is
     *   what stops an old transfer carrying the same comment being replayed as a new tip.
     */
    fun createTip(
        originChatId: Long,
        tipperChatId: Long?,
        rawAddress: String,
        amountNano: Long,
        now: Long,
        ttlSeconds: Long = 900,
    ): Tip {
        val tip = Tip(
            nonce = generateNonce(),
            originChatId = originChatId,
            tipperChatId = tipperChatId,
            rawAddress = rawAddress,
            amountNano = amountNano,
            status = TipStatus.PENDING,
            eventId = null,
            senderAddress = null,
            createdAt = now,
            expiresAt = now + ttlSeconds,
            confirmedAt = null,
        )

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tips (nonce, origin_chat_id, tipper_chat_id, raw_address,
                                  amount_nano, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { st ->
                st.setString(1, tip.nonce)
                st.setLong(2, tip.originChatId)
                if (tip.tipperChatId != null) st.setLong(3, tip.tipperChatId) else st.setNull(3, java.sql.Types.BIGINT)
                st.setString(4, tip.rawAddress)
                st.setLong(5, tip.amountNano)
                st.setString(6, tip.status.name)
                st.setLong(7, tip.createdAt)
                st.setLong(8, tip.expiresAt)
                st.executeUpdate()
            }
        }
        return tip
    }

    /**
     * How many invoices this tipper has open and unexpired.
     *
     * The flood guard, and deliberately a query rather than a counter in memory: an in-process
     * limiter resets on every redeploy, so anyone wanting to fill the table only has to wait for
     * a restart. Asking the database means the limit means the same thing before and after one.
     */
    fun countLivePending(tipperChatId: Long, now: Long): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM tips WHERE tipper_chat_id = ? AND status = 'PENDING' AND expires_at >= ?"
            ).use { st ->
                st.setLong(1, tipperChatId)
                st.setLong(2, now)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    fun findTip(nonce: String): Tip? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_TIP WHERE nonce = ?").use { st ->
                st.setString(1, nonce)
                st.executeQuery().use { rs -> if (rs.next()) rs.toTip() else null }
            }
        }

    /** Pending tips still inside their window - exactly what the poller should be watching. */
    fun pendingTips(now: Long): List<Tip> =
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_TIP WHERE status = 'PENDING' AND expires_at >= ?").use { st ->
                st.setLong(1, now)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toTip()) }
                }
            }
        }

    /**
     * Marks a tip paid. Returns false if it was already settled, or if [eventId] has
     * already been credited to any tip.
     *
     * That second case is the double-payout guard, and it is enforced by a unique index
     * rather than by a check-then-write in application code, which could interleave badly
     * between two poll cycles. A false return means "do not notify, do not deliver".
     */
    fun confirm(nonce: String, eventId: String, senderAddress: String, now: Long): Boolean =
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    UPDATE tips
                       SET status = 'CONFIRMED', event_id = ?, sender_address = ?, confirmed_at = ?
                     WHERE nonce = ? AND status = 'PENDING'
                    """.trimIndent()
                ).use { st ->
                    st.setString(1, eventId)
                    st.setString(2, senderAddress)
                    st.setLong(3, now)
                    st.setString(4, nonce)
                    st.executeUpdate() == 1
                }
            }
        } catch (e: SQLException) {
            // Unique violation on event_id: this on-chain event already paid out a tip.
            false
        }

    /** Sweeps invoices past their window so the poller stops watching them. */
    fun expireStale(now: Long): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE tips SET status = 'EXPIRED' WHERE status = 'PENDING' AND expires_at < ?"
            ).use { st ->
                st.setLong(1, now)
                st.executeUpdate()
            }
        }

    private fun generateNonce(): String {
        val bytes = ByteArray(8)
        RANDOM.nextBytes(bytes)
        // Lowercase hex keeps the comment unambiguous to retype, and TipMatcher compares
        // comments case-sensitively.
        return "tip_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun java.sql.ResultSet.toTip() = Tip(
        nonce = getString("nonce"),
        originChatId = getLong("origin_chat_id"),
        tipperChatId = getLong("tipper_chat_id").takeIf { !wasNull() },
        rawAddress = getString("raw_address"),
        amountNano = getLong("amount_nano"),
        status = TipStatus.valueOf(getString("status")),
        eventId = getString("event_id"),
        senderAddress = getString("sender_address"),
        createdAt = getLong("created_at"),
        expiresAt = getLong("expires_at"),
        confirmedAt = getLong("confirmed_at").takeIf { !wasNull() },
    )

    private companion object {
        val RANDOM = SecureRandom()

        const val SELECT_TIP = """
            SELECT nonce, origin_chat_id, tipper_chat_id, raw_address, amount_nano,
                   status, event_id, sender_address, created_at, expires_at, confirmed_at
              FROM tips
        """
    }
}
