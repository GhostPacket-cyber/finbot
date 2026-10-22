package br.com.finbot.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** Leitura de notificações de banco (ETAPA 09). Desligada por padrão. */
    val bankNotificationsEnabled: Boolean = false,
    /** Pacotes de apps de banco escolhidos pelo usuário. Os demais são descartados sem leitura. */
    val bankNotificationPackages: Set<String> = emptySet(),
    /** Alertas inteligentes (ETAPA 10). */
    val alerts: AlertSettings = AlertSettings(),
    /** Segurança e backup (ETAPA 11). */
    val security: SecuritySettings = SecuritySettings(),
)

data class SecuritySettings(
    /** Pede biometria ou a credencial do aparelho ao abrir e após [lockTimeoutMinutes] em segundo plano. */
    val appLock: Boolean = false,
    /** 0 = sempre que voltar ao app. */
    val lockTimeoutMinutes: Int = 1,
    /** FLAG_SECURE: bloqueia capturas, gravação de tela e a miniatura em Recentes. */
    val secureScreen: Boolean = true,
    /** Lembrete para exportar a cada N dias (0 = desligado). */
    val backupReminderDays: Int = 30,
    val lastExportAt: Instant? = null,
)

/** Cada alerta liga e desliga separadamente. Limites validados em [AlertSettings.sanitized]. */
data class AlertSettings(
    val budget: Boolean = true,
    val duplicate: Boolean = true,
    val duplicateWindowHours: Int = 24,
    val subscription: Boolean = true,
    val unusual: Boolean = true,
    val unusualThresholdPercent: Int = 50,
    val weekly: Boolean = true,
    val weeklyDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
    val weeklyMinuteOfDay: Int = 9 * 60,
    /** Também mostrar como notificação do sistema (o chat sempre recebe). */
    val notify: Boolean = true,
    /** Gemini só reescreve o texto (exige o consentimento de IA e a chave). */
    val aiRewrite: Boolean = false,
) {
    fun sanitized() = copy(
        duplicateWindowHours = duplicateWindowHours.coerceIn(1, 168),
        unusualThresholdPercent = unusualThresholdPercent.coerceIn(10, 500),
        weeklyMinuteOfDay = weeklyMinuteOfDay.coerceIn(0, 24 * 60 - 1),
    )
}

class ConfigRepository(private val dataStore: DataStore<Preferences>) {

    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            displayName = prefs[DISPLAY_NAME].orEmpty(),
            timezone = prefs[TIMEZONE] ?: ConfigValidator.DEFAULT_TIMEZONE,
            onboardingCompleted = prefs[ONBOARDING_DONE] ?: false,
            aiConsentGranted = prefs[AI_CONSENT] ?: false,
            aiConsentAt = prefs[AI_CONSENT_AT]?.let(Instant::ofEpochMilli),
            geminiModel = prefs[GEMINI_MODEL] ?: ConfigValidator.DEFAULT_GEMINI_MODEL,
            bankNotificationsEnabled = prefs[BANK_NOTIFICATIONS] ?: false,
            bankNotificationPackages = prefs[BANK_PACKAGES].orEmpty(),
            alerts = AlertSettings(
                budget = prefs[ALERT_BUDGET] ?: true,
                duplicate = prefs[ALERT_DUPLICATE] ?: true,
                duplicateWindowHours = prefs[ALERT_DUPLICATE_HOURS] ?: 24,
                subscription = prefs[ALERT_SUBSCRIPTION] ?: true,
                unusual = prefs[ALERT_UNUSUAL] ?: true,
                unusualThresholdPercent = prefs[ALERT_UNUSUAL_PERCENT] ?: 50,
                weekly = prefs[ALERT_WEEKLY] ?: true,
                weeklyDay = prefs[ALERT_WEEKLY_DAY]?.let { runCatching { java.time.DayOfWeek.of(it) }.getOrNull() } ?: java.time.DayOfWeek.MONDAY,
                weeklyMinuteOfDay = prefs[ALERT_WEEKLY_MINUTE] ?: (9 * 60),
                notify = prefs[ALERT_NOTIFY] ?: true,
                aiRewrite = prefs[ALERT_AI] ?: false,
            ).sanitized(),
            security = SecuritySettings(
                appLock = prefs[SEC_LOCK] ?: false,
                lockTimeoutMinutes = (prefs[SEC_LOCK_MINUTES] ?: 1).coerceIn(0, 60),
                secureScreen = prefs[SEC_SECURE_SCREEN] ?: true,
                backupReminderDays = (prefs[SEC_BACKUP_DAYS] ?: 30).coerceIn(0, 365),
                lastExportAt = prefs[SEC_LAST_EXPORT]?.let(Instant::ofEpochMilli),
            ),
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

    suspend fun setBankNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[BANK_NOTIFICATIONS] = enabled }
    }

    suspend fun setBankNotificationPackages(packages: Set<String>) {
        dataStore.edit { it[BANK_PACKAGES] = packages }
    }

    suspend fun setAlertSettings(settings: AlertSettings) {
        val s = settings.sanitized()
        dataStore.edit {
            it[ALERT_BUDGET] = s.budget
            it[ALERT_DUPLICATE] = s.duplicate
            it[ALERT_DUPLICATE_HOURS] = s.duplicateWindowHours
            it[ALERT_SUBSCRIPTION] = s.subscription
            it[ALERT_UNUSUAL] = s.unusual
            it[ALERT_UNUSUAL_PERCENT] = s.unusualThresholdPercent
            it[ALERT_WEEKLY] = s.weekly
            it[ALERT_WEEKLY_DAY] = s.weeklyDay.value
            it[ALERT_WEEKLY_MINUTE] = s.weeklyMinuteOfDay
            it[ALERT_NOTIFY] = s.notify
            it[ALERT_AI] = s.aiRewrite
        }
    }

    suspend fun setSecurity(block: (SecuritySettings) -> SecuritySettings) {
        dataStore.edit { prefs ->
            val current = SecuritySettings(
                appLock = prefs[SEC_LOCK] ?: false,
                lockTimeoutMinutes = prefs[SEC_LOCK_MINUTES] ?: 1,
                secureScreen = prefs[SEC_SECURE_SCREEN] ?: true,
                backupReminderDays = prefs[SEC_BACKUP_DAYS] ?: 30,
                lastExportAt = prefs[SEC_LAST_EXPORT]?.let(Instant::ofEpochMilli),
            )
            val s = block(current)
            prefs[SEC_LOCK] = s.appLock
            prefs[SEC_LOCK_MINUTES] = s.lockTimeoutMinutes.coerceIn(0, 60)
            prefs[SEC_SECURE_SCREEN] = s.secureScreen
            prefs[SEC_BACKUP_DAYS] = s.backupReminderDays.coerceIn(0, 365)
            if (s.lastExportAt == null) prefs.remove(SEC_LAST_EXPORT) else prefs[SEC_LAST_EXPORT] = s.lastExportAt.toEpochMilli()
        }
    }

    /** "Apagar tudo": remove todas as preferências (o app volta ao primeiro uso). */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
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
        val BANK_NOTIFICATIONS = booleanPreferencesKey("bank_notifications_enabled")
        val BANK_PACKAGES = stringSetPreferencesKey("bank_notification_packages")
        val ALERT_BUDGET = booleanPreferencesKey("alert_budget")
        val ALERT_DUPLICATE = booleanPreferencesKey("alert_duplicate")
        val ALERT_DUPLICATE_HOURS = intPreferencesKey("alert_duplicate_hours")
        val ALERT_SUBSCRIPTION = booleanPreferencesKey("alert_subscription")
        val ALERT_UNUSUAL = booleanPreferencesKey("alert_unusual")
        val ALERT_UNUSUAL_PERCENT = intPreferencesKey("alert_unusual_percent")
        val ALERT_WEEKLY = booleanPreferencesKey("alert_weekly")
        val ALERT_WEEKLY_DAY = intPreferencesKey("alert_weekly_day")
        val ALERT_WEEKLY_MINUTE = intPreferencesKey("alert_weekly_minute")
        val ALERT_NOTIFY = booleanPreferencesKey("alert_notify")
        val ALERT_AI = booleanPreferencesKey("alert_ai_rewrite")
        val SEC_LOCK = booleanPreferencesKey("sec_app_lock")
        val SEC_LOCK_MINUTES = intPreferencesKey("sec_lock_minutes")
        val SEC_SECURE_SCREEN = booleanPreferencesKey("sec_secure_screen")
        val SEC_BACKUP_DAYS = intPreferencesKey("sec_backup_reminder_days")
        val SEC_LAST_EXPORT = longPreferencesKey("sec_last_export_ms")
        val LEGACY_KEYS = listOf(
            longPreferencesKey("authorized_user_1_id"),
            longPreferencesKey("authorized_user_2_id"),
        )
    }
}
