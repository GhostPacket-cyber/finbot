package br.com.finbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Tela de bloqueio: não mostra nenhum dado. O desbloqueio usa a biometria ou a credencial do aparelho. */
@Composable
fun LockScreen(onUnlockRequest: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("FinBot bloqueado", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Use a biometria ou o bloqueio de tela do aparelho para ver suas finanças.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onUnlockRequest) { Text("Desbloquear") }
        }
    }
}
