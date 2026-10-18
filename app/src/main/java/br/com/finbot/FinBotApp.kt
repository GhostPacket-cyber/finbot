package br.com.finbot

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import br.com.finbot.config.ConfigRepository
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
}

class FinBotApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { removeLegacyData() }
        SafeLog.i("Aplicativo iniciado")
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
