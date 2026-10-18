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
data class AppConfig(
    val displayName: String = "",
    val timezone: String = ConfigValidator.DEFAULT_TIMEZONE,
    val onboardingCompleted: Boolean = false,
    val aiConsentGranted: Boolean = false,
    val aiConsentAt: Instant? = null,
    val geminiModel: String = ConfigValidator.DEFAULT_GEMINI_MODEL,
)

class ConfigRepository(private val dataStore: DataStore<Preferences>) {

    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            displayName = prefs[DISPLAY_NAME].orEmpty(),
            timezone = prefs[TIMEZONE] ?: ConfigValidator.DEFAULT_TIMEZONE,
            onboardingCompleted = prefs[ONBOARDING_DONE] ?: false,
            aiConsentGranted = prefs[AI_CONSENT] ?: false,
            aiConsentAt = prefs[AI_CONSENT_AT]?.let(Instant::ofEpochMilli),
            geminiModel = prefs[GEMINI_MODEL] ?: ConfigValidator.DEFAULT_GEMINI_MODEL,
        )
    }

    suspend fun setGeminiModel(model: String) {
        dataStore.edit { it[GEMINI_MODEL] = model.trim() }
    }

    suspend fun saveProfile(displayName: String, timezone: String) {
        dataStore.edit { prefs ->
            val name = displayName.trim()
            if (name.isEmpty()) prefs.remove(DISPLAY_NAME) else prefs[DISPLAY_NAME] = name
            prefs[TIMEZONE] = timezone.trim()
        }
    }

    suspend fun completeOnboarding(displayName: String, timezone: String) {
        saveProfile(displayName, timezone)
        dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    /** Registra apenas a opção e a data do consentimento, nunca conteúdo. */
    suspend fun setAiConsent(granted: Boolean, at: Instant) {
        dataStore.edit { prefs ->
            prefs[AI_CONSENT] = granted
            if (granted) prefs[AI_CONSENT_AT] = at.toEpochMilli() else prefs.remove(AI_CONSENT_AT)
        }
    }

    /** Remove chaves de versões anteriores (v1, bot de mensagens) que não têm mais uso. */
    suspend fun removeLegacyKeys() {
        dataStore.edit { prefs -> LEGACY_KEYS.forEach { prefs.remove(it) } }
    }

    private companion object {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val TIMEZONE = stringPreferencesKey("timezone")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val AI_CONSENT = booleanPreferencesKey("ai_consent_granted")
        val AI_CONSENT_AT = longPreferencesKey("ai_consent_at_epoch_ms")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val LEGACY_KEYS = listOf(
            longPreferencesKey("authorized_user_1_id"),
            longPreferencesKey("authorized_user_2_id"),
        )
    }
}
