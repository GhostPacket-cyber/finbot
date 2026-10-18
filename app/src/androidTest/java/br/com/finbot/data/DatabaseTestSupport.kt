package br.com.finbot.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import br.com.finbot.assistant.ChatAssistant
import br.com.finbot.config.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.io.File
import androidx.test.core.app.ApplicationProvider
import br.com.finbot.data.repository.AccountRepository
import br.com.finbot.data.repository.BillRepository
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.data.repository.PendingActionRepository
import br.com.finbot.data.repository.SummaryRepository
import br.com.finbot.data.repository.TransactionRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Relógio controlável para testar TTL e datas. */
class MutableClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = current
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

/** Banco em memória com todos os repositórios, como no AppContainer. */
class TestFinance(val clock: MutableClock = MutableClock(Instant.parse("2026-10-17T15:00:00Z"))) {
    val db: FinBotDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        FinBotDatabase::class.java,
    ).build()
    val categories = CategoryRepository(db, clock)
    val accounts = AccountRepository(db)
    val transactions = TransactionRepository(db, clock, accounts)
    val summaries = SummaryRepository(db)
    val bills = BillRepository(db, clock, transactions)
    val pending = PendingActionRepository(db, clock)

    private val configScope = CoroutineScope(Dispatchers.IO + Job())
    val config = ConfigRepository(
        PreferenceDataStoreFactory.create(scope = configScope) {
            File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "cfg-${System.nanoTime()}.preferences_pb")
        },
    )
    val assistant = ChatAssistant(db, transactions, summaries, categories, pending, config, clock)

    suspend fun seed() {
        categories.ensureDefaults()
        accounts.ensureDefault()
    }

    suspend fun categoryId(name: String, kind: TransactionKind): Long =
        requireNotNull(categories.findByName(name, kind)) { "categoria $name" }.id

    fun close() {
        db.close()
        configScope.cancel()
    }
}
