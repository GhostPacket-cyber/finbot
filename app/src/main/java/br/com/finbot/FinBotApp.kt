package br.com.finbot

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import br.com.finbot.assistant.ChatAssistant
import br.com.finbot.config.ConfigRepository
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.alerts.AlertProcessor
import br.com.finbot.alerts.AlertRewriter
import br.com.finbot.alerts.AndroidAlertNotifier
import br.com.finbot.data.repository.AccountRepository
import br.com.finbot.data.repository.BillRepository
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.data.repository.ImportRepository
import br.com.finbot.data.repository.PendingActionRepository
import br.com.finbot.data.repository.ReminderRepository
import br.com.finbot.data.repository.ReportRepository
import br.com.finbot.scheduling.AndroidNotifier
import br.com.finbot.scheduling.ReminderProcessor
import br.com.finbot.scheduling.ReminderScheduler
import br.com.finbot.data.repository.SummaryRepository
import br.com.finbot.data.repository.TransactionRepository
import br.com.finbot.diagnostics.DeviceDiagnostics
import br.com.finbot.security.KeystoreSecretCipher
import br.com.finbot.gemini.AiAvailability
import br.com.finbot.gemini.AiCategorizer
import br.com.finbot.gemini.AiInterpreter
import br.com.finbot.gemini.GeminiApi
import br.com.finbot.gemini.HttpGeminiClient
import br.com.finbot.notifications.AndroidSuggestionNotifier
import br.com.finbot.notifications.BankNotificationIngestor
import br.com.finbot.security.SafeLog
import br.com.finbot.security.SecretKind
import kotlinx.coroutines.flow.first
import br.com.finbot.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock

// Arquivos em /data/data/br.com.finbot/files/datastore/, excluídos de backup.
private val Context.configDataStore by preferencesDataStore(name = "finbot_config")
private val Context.secretsDataStore by preferencesDataStore(name = "finbot_secrets")

/** Dependências do app, criadas manualmente para evitar framework de DI nesta fase. */
class AppContainer(context: Context) {
    val clock: Clock = Clock.systemUTC()
    val configRepository = ConfigRepository(context.configDataStore)
    val secretStore = SecretStore(context.secretsDataStore, KeystoreSecretCipher())
    val diagnostics = DeviceDiagnostics(context)
    /** Bloqueio do app (ETAPA 11): estado por processo. */
    val appLock = br.com.finbot.security.AppLock()

    val database: FinBotDatabase by lazy { FinBotDatabase.build(context) }
    val categoryRepository by lazy { CategoryRepository(database, clock) }
    val accountRepository by lazy { AccountRepository(database) }
    val transactionRepository by lazy { TransactionRepository(database, clock, accountRepository) }
    val importRepository by lazy { ImportRepository(database, clock, transactionRepository, accountRepository) }
    val summaryRepository by lazy { SummaryRepository(database) }
    val billRepository by lazy { BillRepository(database, clock, transactionRepository) }
    val reportRepository by lazy { ReportRepository(database) }
    val pendingActionRepository by lazy { PendingActionRepository(database, clock) }
    val geminiApi: GeminiApi = HttpGeminiClient()
    private suspend fun aiAvailability(): AiAvailability {
        val cfg = configRepository.config.first()
        if (!cfg.aiConsentGranted) return AiAvailability.NoConsent
        return secretStore.get(SecretKind.GEMINI_API_KEY)?.let { AiAvailability.Ready(it, cfg.geminiModel) } ?: AiAvailability.NoKey
    }
    val aiInterpreter by lazy { AiInterpreter(geminiApi, availability = { aiAvailability() }) }
    val aiCategorizer by lazy { AiCategorizer(geminiApi, availability = { aiAvailability() }) }
    val reminderRepository by lazy { ReminderRepository(database, clock) }
    val notifier by lazy { AndroidNotifier(context) }
    val reminderProcessor by lazy {
        ReminderProcessor(database, billRepository, reminderRepository, notifier, zone = { userZone() }, clock = clock)
    }

    suspend fun userZone(): java.time.ZoneId = try {
        java.time.ZoneId.of(configRepository.config.first().timezone)
    } catch (_: java.time.DateTimeException) {
        java.time.ZoneId.of("America/Sao_Paulo")
    }

    val suggestionNotifier by lazy { AndroidSuggestionNotifier(context) }
    val bankNotificationIngestor by lazy {
        BankNotificationIngestor(
            config = { configRepository.config.first() },
            zone = { userZone() },
            transactions = transactionRepository,
            imports = importRepository,
            notifier = suggestionNotifier,
            ownPackage = context.packageName,
        )
    }

    val backupService by lazy {
        br.com.finbot.backup.BackupService(context, database, context.getDatabasePath(FinBotDatabase.FILE_NAME), clock, BuildConfig.VERSION_NAME)
    }
    val alertNotifier by lazy { AndroidAlertNotifier(context) }
    val alertProcessor by lazy {
        AlertProcessor(
            db = database,
            clock = clock,
            zone = { userZone() },
            config = { configRepository.config.first() },
            notifier = alertNotifier,
            rewriter = AlertRewriter(geminiApi, availability = { aiAvailability() }),
        )
    }

    val chatAssistant by lazy {
        ChatAssistant(
            database, transactionRepository, summaryRepository, categoryRepository, pendingActionRepository, configRepository, clock, aiInterpreter,
        )
    }
}

/**
 * WorkManager com inicialização sob demanda (ETAPA 12): o inicializador automático foi removido do manifesto,
 * o que permite aos testes instalar o WorkManager de teste antes do [onCreate].
 */
class FinBotApp : Application(), androidx.work.Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { removeLegacyData() }
        appScope.launch { prepareDatabase() }
        appScope.launch { br.com.finbot.backup.DataMaintenance.cleanTemporaries(this@FinBotApp, java.time.Instant.now()) }
        container.notifier.createChannel()
        container.suggestionNotifier.createChannel()
        container.alertNotifier.createChannel()
        ReminderScheduler.schedule(this)
        SafeLog.i("Aplicativo iniciado")
    }

    /** Categorias padrão, conta "Carteira" e limpeza de ações pendentes vencidas. Idempotente. */
    private suspend fun prepareDatabase() {
        try {
            val created = container.categoryRepository.ensureDefaults()
            container.accountRepository.ensureDefault()
            container.pendingActionRepository.purge()
            if (created > 0) SafeLog.i("Categorias padrão criadas: $created")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("Falha ao preparar o banco", e)
        }
    }

    /** Apaga dados da v1 (bot de mensagens) que podem ter ficado em instalações atualizadas. */
    private suspend fun removeLegacyData() {
        try {
            container.configRepository.removeLegacyKeys()
            container.secretStore.removeLegacy()
            File(filesDir, "datastore/finbot_state.preferences_pb").delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.w("Falha ao remover dados legados", e)
        }
    }
}
