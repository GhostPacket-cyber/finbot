package br.com.finbot.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

enum class BotStatus(val label: String) {
    DESLIGADO("Desligado"),
    CONECTANDO("Conectando"),
    CONECTADO("Conectado"),
    ERRO("Erro"),
}

/** Estado persistente exibido no diagnóstico. [lastError] deve vir já sanitizado e sem dados de usuário. */
data class BotState(
    val status: BotStatus = BotStatus.DESLIGADO,
    val lastActivityAt: Instant? = null,
    val lastError: String? = null,
    val failureCount: Int = 0,
)

class BotStateRepository(private val dataStore: DataStore<Preferences>) {

    val state: Flow<BotState> = dataStore.data.map { prefs ->
        BotState(
            status = prefs[STATUS]?.let { name -> BotStatus.entries.firstOrNull { it.name == name } }
                ?: BotStatus.DESLIGADO,
            lastActivityAt = prefs[LAST_ACTIVITY]?.let(Instant::ofEpochMilli),
            lastError = prefs[LAST_ERROR],
            failureCount = prefs[FAILURES] ?: 0,
        )
    }

    suspend fun setStatus(status: BotStatus) {
        dataStore.edit { prefs ->
            prefs[STATUS] = status.name
            if (status != BotStatus.ERRO) prefs.remove(LAST_ERROR)
        }
    }

    suspend fun recordError(message: String) {
        dataStore.edit { prefs ->
            prefs[STATUS] = BotStatus.ERRO.name
            prefs[LAST_ERROR] = message.take(MAX_ERROR_LENGTH)
            prefs[FAILURES] = (prefs[FAILURES] ?: 0) + 1
        }
    }

    suspend fun recordActivity(at: Instant) {
        dataStore.edit { it[LAST_ACTIVITY] = at.toEpochMilli() }
    }

    /**
     * Na abertura do processo nenhum serviço está ativo: um estado "Conectando/Conectado" persistido
     * é resquício de execução anterior interrompida e não pode ser exibido como verdadeiro.
     */
    suspend fun reconcileAfterProcessStart() {
        dataStore.edit { prefs ->
            val current = prefs[STATUS]
            if (current == BotStatus.CONECTANDO.name || current == BotStatus.CONECTADO.name) {
                prefs[STATUS] = BotStatus.DESLIGADO.name
            }
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 200
        val STATUS = stringPreferencesKey("bot_status")
        val LAST_ACTIVITY = longPreferencesKey("last_activity_epoch_ms")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val FAILURES = intPreferencesKey("failure_count")
    }
}
