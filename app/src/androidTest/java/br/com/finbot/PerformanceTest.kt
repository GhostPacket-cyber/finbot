package br.com.finbot

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.TestFinance
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.entity.TransactionEntity
import br.com.finbot.data.repository.AccountRepository
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.data.repository.ReportRepository
import br.com.finbot.data.repository.SummaryRepository
import br.com.finbot.domain.FilterQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * ETAPA 12 — medições reais (não inventar números): banco com 10 mil lançamentos e latência do chat sem IA.
 * Os números saem no logcat (tag FinBotPerf) e no status da instrumentação; os limites são só contra regressão grosseira.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val dbName = "perf-test.db"

    private fun report(name: String, value: String) {
        Log.i("FinBotPerf", "$name=$value")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("perf", "$name=$value") })
    }

    private inline fun <T> timed(name: String, block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        report(name, "$ms ms")
        return result to ms
    }

    /** Lançamentos sintéticos espalhados por 12 meses, 3 categorias, receitas e despesas. */
    private suspend fun fill(db: FinBotDatabase, count: Int, now: Instant) {
        val categories = CategoryRepository(db, java.time.Clock.systemUTC())
        val accounts = AccountRepository(db)
        categories.ensureDefaults()
        val account = accounts.ensureDefault().id
        val cats = listOf("Mercado", "Transporte", "Lazer").map { categories.findByName(it, TransactionKind.EXPENSE)!!.id }
        db.withTransaction {
            repeat(count) { i ->
                val at = now.minusSeconds(60L * 60 * (i % (24 * 365)))
                db.transactionDao().insert(
                    TransactionEntity(
                        uuid = UUID.randomUUID().toString(),
                        kind = if (i % 10 == 0) TransactionKind.INCOME else TransactionKind.EXPENSE,
                        amountCents = 100L + (i * 37) % 50_000,
                        description = "Lançamento sintético $i",
                        categoryId = if (i % 10 == 0) null else cats[i % 3],
                        accountId = account,
                        occurredAt = at,
                        createdAt = at,
                        updatedAt = at,
                        source = TransactionSource.MANUAL,
                        status = TransactionStatus.CONFIRMED,
                    ),
                )
            }
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun bancoCom10MilLancamentos() = runBlocking {
        context.deleteDatabase(dbName)
        val db = Room.databaseBuilder(context, FinBotDatabase::class.java, dbName).addMigrations(*FinBotDatabase.MIGRATIONS).build()
        val now = Instant.parse("2026-10-22T15:00:00Z")
        try {
            timed("insert_10k_ms") { runBlocking { fill(db, 10_000, now) } }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val bytes = context.getDatabasePath(dbName).length()
            report("db_size_10k", "${bytes / 1024} KB")
            assertTrue("banco de ${bytes / 1024} KB", bytes < 20L * 1024 * 1024)

            val summary = SummaryRepository(db)
            val (_, balanceMs) = timed("balance_ms") { runBlocking { summary.balanceCents() } }
            val (_, monthMs) = timed("monthly_report_ms") { runBlocking { ReportRepository(db).monthly(YearMonth.of(2026, 10), zone, LocalDate.of(2026, 10, 22)) } }
            val (_, recentMs) = timed("recent_20_ms") { runBlocking { db.transactionDao().listRecentConfirmed(20, 0) } }
            val (_, filterMs) = timed("filter_like_50_ms") {
                runBlocking {
                    val q = FilterQuery(start = now.minusSeconds(86_400L * 365), end = now.plusSeconds(1), kind = null, categoryId = null, accountId = null, source = null, likePattern = "%sintético 99%")
                    db.transactionDao().observeFiltered(q.start, q.end, null, null, null, null, q.likePattern, 50).first()
                }
            }
            listOf(balanceMs, monthMs, recentMs, filterMs).forEach { assertTrue("consulta de $it ms", it < 2_000) }
        } finally {
            db.close()
        }
    }

    @Test
    fun latenciaDoChatSemIa() = runBlocking {
        val f = TestFinance()
        try {
            f.seed()
            f.config.completeOnboarding("", "America/Sao_Paulo")
            fill(f.db, 10_000, f.clock.instant())
            // Aquece (primeira consulta compila os statements).
            f.assistant.send("/saldo")
            val times = (1..20).map { i ->
                val input = if (i % 2 == 0) "gastei 45,90 no mercado" else "/saldo"
                val start = System.nanoTime()
                f.assistant.send(input)
                (System.nanoTime() - start) / 1_000_000
            }.sorted()
            report("chat_sem_ia_mediana_ms", "${times[times.size / 2]}")
            report("chat_sem_ia_p95_ms", "${times[(times.size * 95) / 100 - 1]}")
            assertTrue("mediana ${times[times.size / 2]} ms", times[times.size / 2] < 1_000)
        } finally {
            f.close()
        }
    }
}
