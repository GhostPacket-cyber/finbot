package br.com.finbot.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant

/**
 * Estado do bloqueio do app (ETAPA 11), por processo. Um processo novo começa bloqueado se o recurso estiver ligado.
 * O tempo em segundo plano só conta quando o app sai de fato (rotação de tela não conta).
 */
class AppLock {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Só o FinBotTestRunner liga: os testes de interface não dependem do bloqueio configurado no aparelho. Nunca é salvo. */
    @Volatile
    var testBypass: Boolean = false

    private var backgroundAt: Instant? = null
    private var unlockedOnce = false

    /** O app foi para segundo plano (não por troca de configuração). */
    fun onBackground(now: Instant) {
        backgroundAt = now
    }

    /** Chamado ao voltar para o primeiro plano. */
    fun onForeground(enabled: Boolean, timeoutMinutes: Int, now: Instant) {
        _locked.value = !testBypass && shouldLock(enabled, timeoutMinutes, unlockedOnce, backgroundAt, now)
    }

    fun unlock() {
        unlockedOnce = true
        backgroundAt = null
        _locked.value = false
    }

    /** Volta ao estado inicial (bloqueado até autenticar). Usado em testes e ao trocar o ajuste. */
    fun lockNow() {
        unlockedOnce = false
        _locked.value = true
    }

    /** Ao ligar o recurso já autenticado, ou ao desligar: não bloqueia agora. */
    fun markUnlocked() {
        unlockedOnce = true
        _locked.value = false
    }

    companion object {
        /**
         * Bloqueia se o recurso está ligado e: ainda não houve desbloqueio neste processo (abertura a frio), ou
         * o app ficou em segundo plano por pelo menos [timeoutMinutes] (0 = sempre ao voltar).
         */
        fun shouldLock(enabled: Boolean, timeoutMinutes: Int, unlockedOnce: Boolean, backgroundAt: Instant?, now: Instant): Boolean {
            if (!enabled) return false
            if (!unlockedOnce) return true
            val since = backgroundAt ?: return false
            return Duration.between(since, now) >= Duration.ofMinutes(timeoutMinutes.toLong())
        }
    }
}
