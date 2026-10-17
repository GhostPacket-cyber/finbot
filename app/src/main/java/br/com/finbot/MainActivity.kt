package br.com.finbot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.com.finbot.ui.AdminScreen
import br.com.finbot.ui.theme.FinBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tela recebe tokens e chaves: bloqueia screenshots, gravação de tela e miniatura em "Recentes".
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            FinBotTheme {
                AdminScreen()
            }
        }
    }
}
