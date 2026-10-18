package br.com.finbot.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import br.com.finbot.assistant.ChatAssistant
import br.com.finbot.config.ConfigRepository
import br.com.finbot.gemini.AiAvailability
import br.com.finbot.gemini.AiInterpreter
import br.com.finbot.gemini.GeminiApi
import br.com.finbot.gemini.GeminiOutcome
import br.com.finbot.gemini.GeminiRequest
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
import br.com.finbot.data.repository.ReminderRepository
import br.com.finbot.scheduling.DueEvent
import br.com.finbot.scheduling.Notifier
import br.com.finbot.scheduling.ReminderProcessor
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
    val reminders = ReminderRepository(db, clock)
    val notifier = FakeNotifier()
    var zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    fun newProcessor() = ReminderProcessor(db, bills, reminders, notifier, zone = { zone }, clock = clock)
    val processor = newProcessor()
    val pending = PendingActionRepository(db, clock)

    private val configScope = CoroutineScope(Dispatchers.IO + Job())
    val config = ConfigRepository(
        PreferenceDataStoreFactory.create(scope = configScope) {
            File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "cfg-${System.nanoTime()}.preferences_pb")
        },
    )
    val gemini = ScriptedGemini()
    var aiAvailability: AiAvailability = AiAvailability.NoConsent
    val ai = AiInterpreter(gemini, availability = { aiAvailability }, backoff = {})
    val assistant = ChatAssistant(db, transactions, summaries, categories, pending, config, clock, ai)

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

/** Substitui a API real: devolve respostas roteirizadas e registra o que seria enviado. */
class ScriptedGemini : GeminiApi {
    val responses = ArrayDeque<GeminiOutcome>()
    val requests = mutableListOf<GeminiRequest>()

    override suspend fun generate(apiKey: String, request: GeminiRequest): GeminiOutcome {
        requests += request
        return responses.removeFirstOrNull() ?: error("Nenhuma resposta roteirizada")
    }
}

/** Registra o que seria notificado; [available] = false simula permissão negada. */
class FakeNotifier : Notifier {
    val shown = mutableListOf<DueEvent>()
    var available = true
    override fun show(event: DueEvent): Boolean {
        if (!available) return false
        shown += event
        return true
    }
}
