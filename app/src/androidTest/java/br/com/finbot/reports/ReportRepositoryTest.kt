package br.com.finbot.reports

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.data.MutableClock
import br.com.finbot.data.TestFinance
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.repository.ReportRepository
import br.com.finbot.domain.Periods
import br.com.finbot.domain.TransactionDraft
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ReportRepositoryTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    // "Hoje" em novembro: outubro inteiro já passou (inclui o caso de borda 31/10 23:59 local).
    private val today = LocalDate.of(2026, 11, 2)
    private lateinit var f: TestFinance
    private lateinit var reports: ReportRepository

    private fun sp(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    @Before
    fun setUp() = runTest {
        f = TestFinance(MutableClock(sp("2026-11-02T12:00")))
        f.seed()
        reports = ReportRepository(f.db)
    }

    @After
    fun tearDown() = f.close()

    private suspend fun add(kind: TransactionKind, cents: Long, desc: String, category: String?, at: String, status: TransactionStatus = TransactionStatus.CONFIRMED) =
        f.transactions.create(TransactionDraft(kind, cents, desc, category?.let { f.categoryId(it, kind) }, occurredAt = sp(at)), TransactionSource.MANUAL, status)

    /** Consulta SQL independente dos DAOs, para conferência. */
    private fun sqlSum(kind: String, month: YearMonth): Long {
        val r = Periods.month(month, zone)
        f.db.openHelper.readableDatabase.query(
            "SELECT COALESCE(SUM(amount_cents), 0) FROM transactions WHERE kind = ? AND status = 'CONFIRMED' " +
                "AND deleted_at IS NULL AND occurred_at >= ? AND occurred_at < ?",
            arrayOf<Any>(kind, r.start.toEpochMilli(), r.end.toEpochMilli()),
        ).use { c -> c.moveToFirst(); return c.getLong(0) }
    }

    private fun sqlCount(month: YearMonth?): Int {
        val where = if (month == null) "" else Periods.month(month, zone).let { " AND occurred_at >= ${it.start.toEpochMilli()} AND occurred_at < ${it.end.toEpochMilli()}" }
        f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transactions WHERE status = 'CONFIRMED' AND deleted_at IS NULL$where")
            .use { c -> c.moveToFirst(); return c.getInt(0) }
    }

    private suspend fun seedData() {
        add(TransactionKind.INCOME, 500_000, "Salário", "Salário", "2026-10-05T10:00")
        add(TransactionKind.EXPENSE, 4_590, "Mercado", "Mercado", "2026-10-02T10:00")
        add(TransactionKind.EXPENSE, 12_000, "Feira", "Mercado", "2026-10-15T10:00")
        add(TransactionKind.EXPENSE, 3_333, "Uber", "Transporte", "2026-10-20T23:59")
        add(TransactionKind.EXPENSE, 99_999, "Show (31/10 23:59 local, já é novembro em UTC)", "Lazer", "2026-10-31T23:59")
        add(TransactionKind.EXPENSE, 10_000, "Setembro", "Lazer", "2026-09-30T23:59")
        add(TransactionKind.INCOME, 400_000, "Salário set", "Salário", "2026-09-05T10:00")
        add(TransactionKind.EXPENSE, 777, "Sugestão", null, "2026-10-10T10:00", TransactionStatus.PENDING_REVIEW)
        val deleted = add(TransactionKind.EXPENSE, 555, "Apagado", null, "2026-10-10T10:00")
        f.transactions.softDelete(deleted)
        // Conta paga (entra 1 vez, pelo lançamento) e conta pendente (só previsão).
        val luz = f.bills.createBill("Luz", 18_990, f.categoryId("Moradia", TransactionKind.EXPENSE), LocalDate.of(2026, 10, 10))
        f.bills.markPaid(f.bills.ensureOccurrence(luz, LocalDate.of(2026, 10, 10)).id, sp("2026-10-11T09:00"))
        val agua = f.bills.createBill("Água", 7_000, null, LocalDate.of(2026, 10, 25))
        f.bills.ensureOccurrence(agua, LocalDate.of(2026, 10, 25))
    }

    @Test
    fun relatorioBateComConsultaSql() = runTest {
        seedData()
        val october = YearMonth.of(2026, 10)
        val r = reports.monthly(october, zone, today)

        assertEquals(sqlSum("INCOME", october), r.totals.incomeCents)
        assertEquals(sqlSum("EXPENSE", october), r.totals.expenseCents)
        assertEquals(4_590L + 12_000 + 3_333 + 99_999 + 18_990, r.totals.expenseCents) // inclui a conta paga, uma vez
        assertEquals(sqlSum("EXPENSE", october.minusMonths(1)), r.previous.expenseCents)
        assertEquals(sqlCount(october), r.transactionCount)
        assertEquals(r.totals.expenseCents, r.categories.sumOf { it.cents })
        assertEquals("Lazer", r.categories.first().label)
        // Previsto x realizado
        assertEquals(1, r.billsPaid.count)
        assertEquals(18_990L, r.billsPaid.cents)
        assertEquals(1, r.billsPending.count)
        assertEquals(7_000L, r.billsPending.cents)
        // Variação e média
        assertEquals(ReportMath.variationPercent(r.totals.expenseCents, 10_000), r.expenseVariation)
        assertEquals(Math.round(r.totals.expenseCents / 31.0), r.dailyAverageExpense)
        // Mês passado: série completa de 31 dias terminando no total de despesas
        assertEquals(31, r.cumulative.size)
        assertEquals(r.totals.expenseCents, r.cumulative.last().cumulativeCents)
        assertEquals(r.totals.expenseCents - 99_999, r.cumulative[29].cumulativeCents) // até 30/10
        assertEquals(10_000L, r.previousCumulative.last().cumulativeCents)
        // Evolução de 6 meses termina no mês do relatório e bate com SQL mês a mês
        assertEquals((5L downTo 0L).map { october.minusMonths(it) }, r.evolution.map { it.month })
        r.evolution.forEach { m -> assertEquals(sqlSum("EXPENSE", m.month), m.expenseCents) }
    }

    @Test
    fun mesSemDados() = runTest {
        val r = reports.monthly(YearMonth.of(2026, 3), zone, today)
        assertTrue(r.isEmpty)
        assertEquals(0L, r.totals.expenseCents)
        assertNull(r.expenseVariation)
        assertEquals(emptyList<Slice>(), r.categories)
        assertEquals(31, r.cumulative.size)
        assertTrue(r.cumulative.all { it.cumulativeCents == 0L })
        assertEquals(6, r.evolution.size)
    }

    @Test
    fun exportacaoCsvContemExatamenteOsLancamentosConfirmados() = runTest {
        seedData()
        val october = YearMonth.of(2026, 10)
        val monthRows = reports.exportRows(Periods.month(october, zone))
        assertEquals(sqlCount(october), monthRows.size)
        assertEquals(sqlCount(null), reports.exportRows(null).size)

        // Grava num arquivo pelo mesmo caminho do app (ContentResolver) e relê.
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "teste-export.csv")
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        resolver.openOutputStream(Uri.fromFile(file), "wt")!!.use { it.write(CsvExporter.export(monthRows, zone).toByteArray(Charsets.UTF_8)) }
        val bytes = file.readBytes()
        assertEquals(0xEF.toByte(), bytes[0]) // BOM UTF-8
        val lines = CsvExporter.parse(bytes.toString(Charsets.UTF_8))
        assertEquals(CsvExporter.HEADER, lines.first())
        assertEquals(monthRows.size + 1, lines.size)
        val total = lines.drop(1).filter { it[1] == "Despesa" }.sumOf { cell ->
            val (reais, cents) = cell[5].split(',')
            reais.toLong() * 100 + cents.toLong()
        }
        assertEquals(sqlSum("EXPENSE", october), total)
        assertTrue(lines.drop(1).none { it[2] == "Sugestão" || it[2] == "Apagado" })
        file.delete()
    }
}
