package br.com.finbot

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.com.finbot.notifications.BankNotificationListener
import br.com.finbot.security.SafeLog
import br.com.finbot.ui.FinBotRoot
import br.com.finbot.ui.MainTab
import br.com.finbot.ui.theme.FinBotTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant

/** FragmentActivity: o BiometricPrompt precisa dela. */
class MainActivity : FragmentActivity() {
    /** Aba pedida por uma notificação; consumida pela UI. */
    private val tabRequest = mutableStateOf<MainTab?>(null)
    private val container get() = (application as FinBotApp).container
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Padrão seguro antes de qualquer conteúdo; depois segue o ajuste do usuário (ETAPA 11).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.configRepository.config.map { it.security.secureScreen }.distinctUntilChanged().collect { secure ->
                    if (secure) {
                        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) readTabRequest(intent)
        setContent {
            val locked by container.appLock.locked.collectAsStateWithLifecycle()
            FinBotTheme {
                FinBotRoot(
                    tabRequest = tabRequest.value,
                    onTabRequestHandled = { tabRequest.value = null },
                    locked = locked,
                    onUnlockRequest = { authenticate() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readTabRequest(intent)
    }

    /** Abrir o app também verifica avisos pendentes (o WorkManager pode ter atrasado). */
    override fun onStart() {
        super.onStart()
        BankNotificationListener.requestRebindIfNeeded(this)
        lifecycleScope.launch {
            val security = container.configRepository.config.first().security
            container.appLock.onForeground(security.appLock, security.lockTimeoutMinutes, Instant.now(container.clock))
            if (container.appLock.locked.value && security.appLock) authenticate()
            try {
                container.reminderProcessor.run()
                container.alertProcessor.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao verificar avisos na abertura", e)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Rotação recria a Activity: não conta como sair do app.
        if (!isChangingConfigurations) container.appLock.onBackground(Instant.now(container.clock))
    }

    /** Biometria (classe fraca ou forte) ou PIN/padrão/senha do aparelho. */
    fun authenticate(onSuccess: () -> Unit = {}) {
        if (prompting) return
        if (!canAuthenticate()) {
            // Sem bloqueio de tela no aparelho: o bloqueio do app não tem como funcionar; não prende o usuário fora.
            SafeLog.w("Bloqueio do app sem credencial no aparelho")
            container.appLock.unlock()
            onSuccess()
            return
        }
        prompting = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    container.appLock.unlock()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear o FinBot")
                .setSubtitle("Use a biometria ou o bloqueio de tela")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    fun canAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun readTabRequest(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: return
        tabRequest.value = MainTab.entries.firstOrNull { it.name == name }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "br.com.finbot.OPEN_TAB"

        /** BIOMETRIC_WEAK | DEVICE_CREDENTIAL é a combinação aceita do Android 8 ao 16 (a STRONG + credencial não funciona no 9 e 10). */
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
