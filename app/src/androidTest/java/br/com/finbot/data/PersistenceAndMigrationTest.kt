package br.com.finbot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.finbot.data.repository.AccountRepository
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.data.repository.SummaryRepository
import br.com.finbot.data.repository.TransactionRepository
import br.com.finbot.domain.TransactionDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PersistenceAndMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = MutableClock(Instant.parse("2026-10-17T15:00:00Z"))

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FinBotDatabase::class.java)

    @After
    fun cleanUp() {
        context.deleteDatabase(FILE_PERSIST)
        context.deleteDatabase(FILE_MIGRATE)
    }

    private fun open(name: String) =
        Room.databaseBuilder(context, FinBotDatabase::class.java, name).addMigrations(*FinBotDatabase.MIGRATIONS).build()

    @Test
    fun dadosPersistemAoFecharEReabrirOBanco() = runTest {
        val first = open(FILE_PERSIST)
        CategoryRepository(first, clock).ensureDefaults()
        val accounts = AccountRepository(first)
        val mercado = CategoryRepository(first, clock).findByName("Mercado", TransactionKind.EXPENSE)!!.id
        TransactionRepository(first, clock, accounts).create(
            TransactionDraft(TransactionKind.EXPENSE, 12_345, "compras", mercado, occurredAt = clock.current, note = "feira"),
            TransactionSource.MANUAL,
        )
        first.close()

        val second = open(FILE_PERSIST)
        assertEquals(-12_345L, SummaryRepository(second).balanceCents())
        assertEquals(0, CategoryRepository(second, clock).ensureDefaults())
        second.close()
    }

    @Test
    fun migracaoDeV1ParaV2PreservaDados() = runTest {
        // Cria o banco exatamente no schema v1 exportado (app/schemas/.../1.json) e insere linhas cruas.
        helper.createDatabase(FILE_MIGRATE, 1).apply {
            insert("categories", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("uuid", "c-1"); put("name", "Mercado"); put("kind", "EXPENSE"); put("archived", 0)
            })
            insert("accounts", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("uuid", "a-1"); put("name", "Carteira"); put("type", "CASH"); put("archived", 0)
            })
            insert("transactions", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("uuid", "t-1"); put("kind", "EXPENSE"); put("amount_cents", 4_590L)
                put("currency", "BRL"); put("description", "almoço"); put("category_id", 1); put("account_id", 1)
                put("occurred_at", clock.current.toEpochMilli()); put("created_at", clock.current.toEpochMilli())
                put("updated_at", clock.current.toEpochMilli()); put("source", "MANUAL"); put("status", "CONFIRMED")
            })
            close()
        }

        // Aplica MIGRATION_1_2 e valida contra o schema v2 exportado.
        helper.runMigrationsAndValidate(FILE_MIGRATE, 2, true, MIGRATION_1_2).close()

        val migrated = open(FILE_MIGRATE)
        val row = migrated.transactionDao().getById(1)!!
        assertEquals(4_590L, row.amountCents)
        assertEquals("almoço", row.description)
        assertNull(row.note)
        assertEquals(-4_590L, SummaryRepository(migrated).balanceCents())
        migrated.close()
    }

    @Test
    fun migracaoDeV2ParaV3PreservaHistoricoDoChat() = runTest {
        helper.createDatabase(FILE_MIGRATE, 2).apply {
            insert("chat_messages", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("role", "USER"); put("text", "gastei 10 no café"); put("created_at", clock.current.toEpochMilli())
            })
            close()
        }

        helper.runMigrationsAndValidate(FILE_MIGRATE, 3, true, MIGRATION_2_3).close()

        val migrated = open(FILE_MIGRATE)
        val rows = migrated.chatMessageDao().observeRecentWithActions(10).first()
        assertEquals("gastei 10 no café", rows.single().message.text)
        assertNull(rows.single().message.quickReply)
        migrated.close()
    }

    @Test
    fun cadeiaCompletaDeV1ParaAtual() = runTest {
        helper.createDatabase(FILE_MIGRATE, 1).close()
        helper.runMigrationsAndValidate(FILE_MIGRATE, 4, true, *FinBotDatabase.MIGRATIONS).close()
    }

    @Test
    fun migracaoDeV3ParaV4PreservaLancamentosELotes() = runTest {
        helper.createDatabase(FILE_MIGRATE, 3).apply {
            insert("accounts", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("uuid", "a1"); put("name", "Conta"); put("type", "CHECKING"); put("archived", 0)
            })
            insert("transactions", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("uuid", "t1"); put("kind", "EXPENSE"); put("amount_cents", 990); put("currency", "BRL")
                put("description", "café"); put("account_id", 1); put("occurred_at", clock.current.toEpochMilli())
                put("created_at", clock.current.toEpochMilli()); put("updated_at", clock.current.toEpochMilli())
                put("source", "IMPORT_OFX"); put("status", "PENDING_REVIEW"); put("dedup_key", "ofx:abc")
            })
            insert("import_batches", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("uuid", "b1"); put("source", "OFX"); put("file_name_hash", "h")
                put("imported_at", clock.current.toEpochMilli()); put("item_count", 1)
            })
            close()
        }

        helper.runMigrationsAndValidate(FILE_MIGRATE, 4, true, MIGRATION_3_4).close()

        val migrated = open(FILE_MIGRATE)
        val t = migrated.transactionDao().getById(1)!!
        assertEquals("café", t.description)
        assertNull(t.importBatchId)
        assertEquals(listOf("ofx:abc"), migrated.transactionDao().existingDedupKeys(listOf("ofx:abc", "x")))
        val batch = migrated.importBatchDao().getById(1)!!
        assertEquals(0, batch.skippedCount)
        assertNull(batch.undoneAt)
        assertTrue(migrated.csvPresetDao().listAll().isEmpty())
        migrated.close()
    }

    private companion object {
        const val FILE_PERSIST = "finbot-persist-test.db"
        const val FILE_MIGRATE = "finbot-migrate-test.db"
    }
}
