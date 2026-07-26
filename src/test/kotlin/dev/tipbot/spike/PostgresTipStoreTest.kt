package dev.tipbot.spike

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import javax.sql.DataSource

/**
 * The same contract against Postgres, proving the schema and the double-payout guard behave
 * identically on the engine you would actually deploy to in the cloud.
 *
 * Skipped unless a server is reachable, so the build stays green on a machine without one.
 * To run it:
 *
 *   docker run -d --rm --name tipbot-pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=tipbot \
 *     -p 55432:5432 postgres:16-alpine
 *
 * Override the target with TIPBOT_TEST_PG_URL / TIPBOT_TEST_PG_USER / TIPBOT_TEST_PG_PASSWORD.
 */
class PostgresTipStoreTest : TipStoreContractTest() {

    override fun connect(): DataSource = Database.connect(URL, USER, PASSWORD)

    companion object {
        private val URL: String =
            System.getenv("TIPBOT_TEST_PG_URL") ?: "jdbc:postgresql://localhost:55432/tipbot"
        private val USER: String = System.getenv("TIPBOT_TEST_PG_USER") ?: "postgres"
        private val PASSWORD: String = System.getenv("TIPBOT_TEST_PG_PASSWORD") ?: "test"

        @JvmStatic
        @BeforeAll
        fun requirePostgres() {
            val reachable = try {
                java.sql.DriverManager.getConnection(URL, USER, PASSWORD).use { true }
            } catch (e: Exception) {
                false
            }
            assumeTrue(reachable, "No Postgres at $URL - skipping Postgres contract tests")
        }
    }
}
