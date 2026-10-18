package br.com.finbot

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import br.com.finbot.assistant.ChatAssistant
import br.com.finbot.config.ConfigRepository
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.repository.AccountRepository
import br.com.finbot.data.repository.BillRepository
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.data.repository.PendingActionRepository
import br.com.finbot.data.repository.SummaryRepository
import br.com.finbot.data.repository.TransactionRepository
import br.com.finbot.diagnostics.DeviceDiagnostics
import br.com.finbot.security.KeystoreSecretCipher
import br.com.finbot.security.SafeLog
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

    val database: FinBotDatabase by lazy { FinBotDatabase.build(context) }
    val categoryRepository by lazy { CategoryRepository(database, clock) }
    val accountRepository by lazy { AccountRepository(database) }
    val transactionRepository by lazy { TransactionRepository(database, clock, accountRepository) }
    val summaryRepository by lazy { SummaryRepository(database) }
    val billRepository by lazy { BillRepository(database, clock, transactionRepository) }
    val pendingActionRepository by lazy { PendingActionRepository(database, clock) }
    val chatAssistant by lazy {
        ChatAssistant(database, transactionRepository, summaryRepository, categoryRepository, pendingActionRepository, configRepository, clock)
    }
}

class FinBotApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { removeLegacyData() }
        appScope.launch { prepareDatabase() }
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
