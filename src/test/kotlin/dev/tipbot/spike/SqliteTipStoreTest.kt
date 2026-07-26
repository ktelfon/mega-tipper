package dev.tipbot.spike

import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

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
}
