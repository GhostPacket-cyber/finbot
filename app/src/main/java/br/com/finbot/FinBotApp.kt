package br.com.finbot

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import br.com.finbot.config.ConfigRepository
import br.com.finbot.security.KeystoreSecretCipher
import br.com.finbot.security.SafeLog
import br.com.finbot.security.SecretStore
import br.com.finbot.service.BotController
import br.com.finbot.service.BotStateRepository
import br.com.finbot.service.DeviceDiagnostics
import br.com.finbot.service.NotImplementedBotController
import br.com.finbot.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock

// Arquivos em /data/data/br.com.finbot/files/datastore/, excluídos de backup.
private val Context.configDataStore by preferencesDataStore(name = "finbot_config")
private val Context.secretsDataStore by preferencesDataStore(name = "finbot_secrets")
private val Context.stateDataStore by preferencesDataStore(name = "finbot_state")

/** Dependências do app, criadas manualmente para evitar framework de DI nesta fase. */
class AppContainer(context: Context) {
    val clock: Clock = Clock.systemUTC()
    val configRepository = ConfigRepository(context.configDataStore)
    val secretStore = SecretStore(context.secretsDataStore, KeystoreSecretCipher())
    val botStateRepository = BotStateRepository(context.stateDataStore)
    val botController: BotController = NotImplementedBotController(botStateRepository)
    val diagnostics = DeviceDiagnostics(context)
}

class FinBotApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        appScope.launch { container.botStateRepository.reconcileAfterProcessStart() }
        SafeLog.i("Aplicativo iniciado")
    }
}
