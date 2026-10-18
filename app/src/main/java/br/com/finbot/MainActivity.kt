package br.com.finbot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.com.finbot.ui.FinBotRoot
import br.com.finbot.ui.theme.FinBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Telas com dados financeiros e chave de API: bloqueia screenshots, gravação e miniatura em "Recentes".
        // Tornar configurável na ETAPA 11.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            FinBotTheme {
                FinBotRoot()
            }
        }
    }
}
