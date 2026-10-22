package br.com.finbot

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import br.com.finbot.notifications.BankNotificationListener
import br.com.finbot.security.SafeLog
import br.com.finbot.ui.FinBotRoot
import br.com.finbot.ui.MainTab
import br.com.finbot.ui.theme.FinBotTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Aba pedida por uma notificação; consumida pela UI. */
    private val tabRequest = mutableStateOf<MainTab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Telas com dados financeiros e chave de API: bloqueia screenshots, gravação e miniatura em "Recentes".
        // Tornar configurável na ETAPA 11.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (savedInstanceState == null) readTabRequest(intent)
        setContent {
            FinBotTheme {
                FinBotRoot(tabRequest = tabRequest.value, onTabRequestHandled = { tabRequest.value = null })
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
        val container = (application as FinBotApp).container
        BankNotificationListener.requestRebindIfNeeded(this)
        lifecycleScope.launch {
            try {
                container.reminderProcessor.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao verificar avisos na abertura", e)
            }
        }
    }

    private fun readTabRequest(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: return
        tabRequest.value = MainTab.entries.firstOrNull { it.name == name }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "br.com.finbot.OPEN_TAB"
    }
}
