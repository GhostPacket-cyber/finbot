package br.com.finbot.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.finbot.config.ConfigRepository
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.MutableClock
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.repository.AccountRepository
import br.com.finbot.data.repository.BillRepository
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.data.repository.ReminderRepository
import br.com.finbot.data.repository.TransactionRepository
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.importing.DedupKeys
import br.com.finbot.security.AppLock
import br.com.finbot.security.KeystoreSecretCipher
import br.com.finbot.security.SecretKind
import br.com.finbot.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Exportar/importar `.finbot` com bancos reais em arquivo (aparelhos "A", "B" e "C"). Nada toca o banco do app. */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = MutableClock(Instant.parse("2026-10-17T15:00:00Z"))
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val password = "senha-forte-de-teste".toCharArray()
    private val opened = mutableListOf<FinBotDatabase>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FinBotDatabase::class.java)

    private class Device(val db: FinBotDatabase, val file: File, val service: BackupService, clock: java.time.Clock) {
        val categories = CategoryRepository(db, clock)
        val accounts = AccountRepository(db)
        val transactions = TransactionRepository(db, clock, accounts)
        val bills = BillRepository(db, clock, transactions)
        val reminders = ReminderRepository(db, clock)
        fun stats() = BackupStats.compute(BackupService.reader(db.openHelper.writableDatabase))
    }

    private suspend fun device(name: String): Device {
        val fileName = "backup-test-$name.db"
        context.deleteDatabase(fileName)
        val db = Room.databaseBuilder(context, FinBotDatabase::class.java, fileName).addMigrations(*FinBotDatabase.MIGRATIONS).build()
        opened += db
        val d = Device(db, context.getDatabasePath(fileName), BackupService(context, db, context.getDatabasePath(fileName), clock, "teste"), clock)
        d.categories.ensureDefaults()
        d.accounts.ensureDefault()
        return d
    }

    private fun at(m: Int, day: Int) = LocalDate.of(2026, m, day).atTime(12, 0).atZone(zone).toInstant()

    /** Dados variados: lançamentos, conta paga (vínculo), orçamento, lembrete e importação. */
    private suspend fun seed(d: Device, prefix: String) {
        val mercado = d.categories.findByName("Mercado", TransactionKind.EXPENSE)!!.id
        d.transactions.create(TransactionDraft(TransactionKind.INCOME, 500_000, "$prefix salário", null, null, at(10, 5)), TransactionSource.MANUAL)
        d.transactions.create(TransactionDraft(TransactionKind.EXPENSE, 12_345, "$prefix mercado", mercado, null, at(10, 6)), TransactionSource.CHAT)
        d.transactions.create(
            TransactionDraft(TransactionKind.EXPENSE, 4_590, "$prefix padaria", mercado, null, at(10, 7)),
            TransactionSource.IMPORT_OFX, TransactionStatus.PENDING_REVIEW, dedupKey = "ofx:$prefix-1",
        )
        val gone = d.transactions.create(TransactionDraft(TransactionKind.EXPENSE, 999, "$prefix excluído", null, null, at(10, 8)), TransactionSource.MANUAL)
        d.transactions.softDelete(gone)
        val bill = d.bills.createBill("$prefix Internet", 9_990, null, LocalDate.of(2026, 10, 10), recurrenceRule = "MONTHLY")
        d.bills.ensureOccurrencesAround(LocalDate.of(2026, 10, 17))
        val occurrence = d.bills.listPendingUntil(LocalDate.of(2026, 10, 31)).first { it.billId == bill }
        d.bills.markPaid(occurrence.occurrenceId, at(10, 10))
        d.categories.setMonthlyBudget(mercado, 80_000, YearMonth.of(2026, 10))
        d.reminders.create("$prefix lembrete", java.time.LocalDateTime.of(2026, 10, 20, 9, 0), br.com.finbot.scheduling.ReminderRepeat.Once, zone)
        d.db.importBatchDao().insert(br.com.finbot.data.entity.ImportBatchEntity(uuid = "$prefix-batch", source = br.com.finbot.data.ImportSource.OFX, fileNameHash = "h", importedAt = clock.instant(), itemCount = 1))
        d.db.csvPresetDao().insert(br.com.finbot.data.entity.CsvPresetEntity(name = "$prefix banco", separator = ";", skipLines = 1, skipFooterLines = 0, dateColumn = 0, descriptionColumn = 1, amountColumn = 2, debitColumn = null, creditColumn = null, dateFormat = "DMY", invertSign = false, updatedAt = clock.instant()))
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        listOf("a", "b", "c").forEach { context.deleteDatabase("backup-test-$it.db") }
        context.deleteDatabase(BackupService.IMPORT_DB_NAME)
    }

    @Test
    fun exportarEmAImportarEmBReproduzOsTotais() = runTest {
        val a = device("a")
        seed(a, "A")
        val (file, manifest) = a.service.export(password)
        assertTrue(a.stats().let { manifest.sameTotals(it) })
        assertTrue((manifest.tables["transactions"]?.rows ?: 0) == 5L)
        assertEquals(500_000L - 12_345 - 9_990, manifest.balanceCents) // pendente e excluído fora; pagamento da conta conta

        val b = device("b")
        b.transactions.create(TransactionDraft(TransactionKind.EXPENSE, 1, "só em B", null, null, at(10, 1)), TransactionSource.MANUAL)
        val backup = b.service.open(password, file)
        b.service.replace(backup)
        b.service.discard(backup)
        val after = b.stats()
        assertTrue("totais de B = A", manifest.sameTotals(after))
        assertEquals(a.stats(), after)
        assertEquals(0, b.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM transactions WHERE description = 'só em B'").use { it.moveToFirst(); it.getInt(0) })
        // O vínculo conta paga → lançamento sobreviveu (ids preservados na substituição).
        assertEquals(1, b.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM bill_occurrences WHERE linked_transaction_id IS NOT NULL").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun exportacaoIncluiEscritasRecentesDoWal() = runTest {
        val a = device("a")
        seed(a, "A")
        // Escrita logo antes de exportar: ainda está só no WAL.
        a.transactions.create(TransactionDraft(TransactionKind.INCOME, 777, "recém-gravado", null, null, at(10, 9)), TransactionSource.MANUAL)
        val (_, manifest) = a.service.export(password)
        assertEquals(6L, manifest.tables["transactions"]?.rows)
        assertTrue(manifest.sameTotals(a.stats()))
    }

    @Test
    fun senhaErradaOuArquivoAdulteradoNaoAlteraNada() = runTest {
        val a = device("a")
        seed(a, "A")
        val (file, _) = a.service.export(password)
        val b = device("b")
        seed(b, "B")
        val before = b.stats()
        try {
            b.service.open("senha-errada-123".toCharArray(), file)
            fail("deveria recusar")
        } catch (_: BackupAuthException) {
        }
        val tampered = file.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        try {
            b.service.open(password, tampered)
            fail("deveria recusar")
        } catch (_: BackupAuthException) {
        }
        assertEquals(before, b.stats())
        assertFalse(context.getDatabasePath(BackupService.IMPORT_DB_NAME).exists())
    }

    @Test
    fun manifestoQueNaoConfereEVersaoMaisNovaSaoRejeitados() = runTest {
        val a = device("a")
        seed(a, "A")
        val (file, manifest) = a.service.export(password)
        val db = BackupCrypto.decrypt(password, file).db
        // Arquivo cifrado corretamente, mas com totais que não batem (ex.: manifesto forjado).
        val wrongTotals = BackupCrypto.encrypt(password, manifest.copy(balanceCents = 1).toJson(), db, iterations = 100_000)
        assertRejected(a, wrongTotals, "Totais")
        val newer = BackupCrypto.encrypt(password, manifest.copy(schemaVersion = 99).toJson(), db, iterations = 100_000)
        assertRejected(a, newer, "versão mais nova")
        val wrongHash = BackupCrypto.encrypt(password, manifest.copy(sha256 = "00").toJson(), db, iterations = 100_000)
        assertRejected(a, wrongHash, "não confere")
    }

    private suspend fun assertRejected(d: Device, file: ByteArray, reason: String) {
        try {
            d.service.open(password, file)
            fail("deveria rejeitar: $reason")
        } catch (e: BackupIntegrityException) {
            assertTrue("${e.message} ~ $reason", e.message!!.contains(reason))
        }
    }

    @Test
    fun mesclarDuasVezesNaoDuplica() = runTest {
        val a = device("a")
        seed(a, "A")
        val (file, _) = a.service.export(password)
        val c = device("c")
        seed(c, "C")
        val before = c.stats()

        val backup = c.service.open(password, file)
        val preview = c.service.previewMerge(backup)
        assertEquals(before, c.stats()) // prévia não grava
        val first = c.service.merge(backup)
        assertEquals(preview, first)
        assertEquals(5, first.added["transactions"])
        assertEquals(0, first.added["categories"]) // mesmas categorias padrão (nome + tipo) unificadas
        assertEquals(0, first.added["accounts"]) // "Carteira" unificada
        assertEquals(1, first.added["bills"])
        assertEquals(1, first.added["csv_presets"])
        val second = c.service.merge(backup)
        assertEquals(0, second.totalAdded)
        c.service.discard(backup)

        val after = c.stats()
        assertEquals(before.tables["transactions"]!!.rows + 5, after.tables["transactions"]!!.rows)
        assertEquals(before.balanceCents + (500_000L - 12_345 - 9_990), after.balanceCents)
        // Os lançamentos importados apontam para a categoria local "Mercado".
        val mercadoC = c.categories.findByName("Mercado", TransactionKind.EXPENSE)!!.id
        assertEquals(1, c.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM transactions WHERE description = 'A mercado' AND category_id = $mercadoC").use { it.moveToFirst(); it.getInt(0) })
        // Conta paga mesclada continua ligada ao lançamento (remapeado).
        assertEquals(2, c.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM bill_occurrences WHERE linked_transaction_id IS NOT NULL").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun backupDeSchemaAntigoEMigradoAoImportar() = runTest {
        // "Aparelho antigo" no schema v3, criado a partir do schema exportado.
        val oldName = "backup-test-old.db"
        context.deleteDatabase(oldName)
        helper.createDatabase(oldName, 3).apply {
            insert("categories", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("id", 1); put("uuid", "c1"); put("name", "Mercado"); put("kind", "EXPENSE"); put("archived", 0) })
            insert("accounts", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("id", 1); put("uuid", "a1"); put("name", "Carteira"); put("type", "CASH"); put("archived", 0) })
            insert("transactions", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("uuid", "t-old"); put("kind", "EXPENSE"); put("amount_cents", 2_500); put("currency", "BRL"); put("description", "antigo")
                put("category_id", 1); put("account_id", 1); put("occurred_at", at(9, 1).toEpochMilli()); put("created_at", at(9, 1).toEpochMilli())
                put("updated_at", at(9, 1).toEpochMilli()); put("source", "MANUAL"); put("status", "CONFIRMED")
            })
            close()
        }
        val oldFile = context.getDatabasePath(oldName)
        val bytes = oldFile.readBytes()
        val stats = SQLiteDatabase.openDatabase(oldFile.path, null, SQLiteDatabase.OPEN_READONLY).use { BackupStats.compute(BackupService.reader(it)) }
        val manifest = BackupManifest(schemaVersion = 3, createdAtEpochMs = 0, appVersion = "0.3", sha256 = DedupKeys.sha256Bytes(bytes), tables = stats.tables, balanceCents = stats.balanceCents)
        val file = BackupCrypto.encrypt(password, manifest.toJson(), bytes, iterations = 100_000)
        context.deleteDatabase(oldName)

        val b = device("b")
        val backup = b.service.open(password, file)
        b.service.replace(backup)
        b.service.discard(backup)
        val t = b.db.transactionDao().listRecentConfirmed(10, 0).single().transaction
        assertEquals("antigo", t.description)
        assertNull(t.importBatchId) // coluna da v4 criada pela migração
        assertEquals(-2_500L, b.stats().balanceCents)
    }

    @Test
    fun apagarTudoLimpaBancoAjustesSegredosEChave() = runTest {
        val a = device("a")
        seed(a, "A")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val config = ConfigRepository(PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "wipe-cfg-${System.nanoTime()}.preferences_pb") })
        val alias = "finbot_wipe_test_${System.nanoTime()}"
        val secrets = SecretStore(PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "wipe-sec-${System.nanoTime()}.preferences_pb") }, KeystoreSecretCipher(alias))
        config.completeOnboarding("Teste", "America/Sao_Paulo")
        secrets.put(SecretKind.GEMINI_API_KEY, "chave-de-teste-ficticia-1234567890")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias(alias))

        DataMaintenance.wipeAll(context, a.db, config, secrets, a.categories, a.accounts, AppLock(), systemEffects = false)

        assertEquals(0L, a.stats().tables["transactions"]!!.rows)
        assertEquals(0L, a.stats().tables["chat_messages"]!!.rows)
        assertTrue(a.stats().tables["categories"]!!.rows > 0) // padrões recriados
        assertFalse(config.config.first().onboardingCompleted)
        assertNull(secrets.get(SecretKind.GEMINI_API_KEY))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        scope.cancel()
    }
}
