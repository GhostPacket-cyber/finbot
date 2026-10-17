package br.com.finbot.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Configuração não secreta. Segredos ficam em [br.com.finbot.security.SecretStore]. */
data class AdminConfig(
    val authorizedUserIds: List<Long> = emptyList(),
    val timezone: String = ConfigValidator.DEFAULT_TIMEZONE,
    val aiConsentGranted: Boolean = false,
    val aiConsentAt: Instant? = null,
)

class ConfigRepository(private val dataStore: DataStore<Preferences>) {

    val config: Flow<AdminConfig> = dataStore.data.map { prefs ->
        AdminConfig(
            authorizedUserIds = listOfNotNull(prefs[USER_1_ID], prefs[USER_2_ID]),
            timezone = prefs[TIMEZONE] ?: ConfigValidator.DEFAULT_TIMEZONE,
            aiConsentGranted = prefs[AI_CONSENT] ?: false,
            aiConsentAt = prefs[AI_CONSENT_AT]?.let(Instant::ofEpochMilli),
        )
    }

    suspend fun saveUsersAndTimezone(userIds: List<Long>, timezone: String) {
        require(userIds.size in 1..ConfigValidator.MAX_AUTHORIZED_USERS) { "Quantidade de usuários inválida" }
        require(userIds.distinct().size == userIds.size) { "IDs duplicados" }
        dataStore.edit { prefs ->
            prefs[USER_1_ID] = userIds[0]
            val second = userIds.getOrNull(1)
            if (second != null) prefs[USER_2_ID] = second else prefs.remove(USER_2_ID)
            prefs[TIMEZONE] = timezone
        }
    }

    /** Registra apenas a opção e a data do consentimento, nunca conteúdo. */
    suspend fun setAiConsent(granted: Boolean, at: Instant) {
        dataStore.edit { prefs ->
            prefs[AI_CONSENT] = granted
            if (granted) prefs[AI_CONSENT_AT] = at.toEpochMilli() else prefs.remove(AI_CONSENT_AT)
        }
    }

    private companion object {
        val USER_1_ID = longPreferencesKey("authorized_user_1_id")
        val USER_2_ID = longPreferencesKey("authorized_user_2_id")
        val TIMEZONE = stringPreferencesKey("timezone")
        val AI_CONSENT = booleanPreferencesKey("ai_consent_granted")
        val AI_CONSENT_AT = longPreferencesKey("ai_consent_at_epoch_ms")
    }
}
