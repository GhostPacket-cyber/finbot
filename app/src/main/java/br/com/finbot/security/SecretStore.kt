package br.com.finbot.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Base64

enum class SecretKind(val storageKey: String) {
    GEMINI_API_KEY("gemini_api_key"),
}

sealed interface SecretStatus {
    data object Missing : SecretStatus
    data class Configured(val hint: String) : SecretStatus
    /** O valor existe mas não pode ser decifrado (ex.: chave do Keystore apagada). Deve ser substituído. */
    data object Unreadable : SecretStatus
}

object SecretMask {
    /** Nunca exibe o segredo inteiro: só os 4 últimos caracteres quando o valor é longo o bastante. */
    fun hint(secret: String): String =
        if (secret.length >= 16) "•••• ${secret.takeLast(4)}" else "••••"
}

/** Persiste segredos cifrados por [SecretCipher] em um DataStore privado, excluído de backups. */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {

    val statuses: Flow<Map<SecretKind, SecretStatus>> = dataStore.data
        .map { prefs -> SecretKind.entries.associateWith { statusOf(prefs[keyOf(it)]) } }
        .flowOn(Dispatchers.Default)

    suspend fun put(kind: SecretKind, value: String) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Segredo vazio" }
        val encoded = Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
        dataStore.edit { it[keyOf(kind)] = encoded }
    }

    suspend fun remove(kind: SecretKind) {
        dataStore.edit { it.remove(keyOf(kind)) }
    }

    /** Apaga segredos de versões anteriores (v1) que não existem mais em [SecretKind]. */
    suspend fun removeLegacy() {
        dataStore.edit { prefs -> LEGACY_KEYS.forEach { prefs.remove(stringPreferencesKey(it)) } }
    }

    /** Valor em claro para uso interno (clientes HTTP). Retorna null se ausente ou ilegível. */
    suspend fun get(kind: SecretKind): String? =
        dataStore.data.first()[keyOf(kind)]?.let { runCatching { decode(it) }.getOrNull() }

    private fun statusOf(encoded: String?): SecretStatus {
        if (encoded == null) return SecretStatus.Missing
        return runCatching { decode(encoded) }
            .fold(onSuccess = { SecretStatus.Configured(SecretMask.hint(it)) }, onFailure = { SecretStatus.Unreadable })
    }

    private fun decode(encoded: String): String =
        String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)

    private fun keyOf(kind: SecretKind) = stringPreferencesKey(kind.storageKey)

    private companion object {
        val LEGACY_KEYS = listOf("telegram_bot_token")
    }
}
