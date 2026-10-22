package br.com.finbot.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import br.com.finbot.config.ConfigErrors
import br.com.finbot.config.ConfigValidator
import br.com.finbot.ui.settings.ProfileFields
import java.time.ZoneId

@Composable
fun OnboardingScreen(errors: ConfigErrors, onComplete: (displayName: String, timezone: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var timezone by rememberSaveable { mutableStateOf(ConfigValidator.DEFAULT_TIMEZONE) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Bem-vindo ao FinBot",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Seu assistente de finanças pessoais. Registre gastos e receitas conversando, " +
                    "acompanhe contas a vencer e veja relatórios do mês.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Privacidade", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "• Seus dados financeiros ficam somente neste celular. Não há conta online nem nuvem.\n" +
                    "• A interpretação por IA (Gemini) é opcional e só funciona se você autorizar em Ajustes. " +
                    "Nesse caso, as mensagens enviadas ao assistente são processadas pelo Google.\n" +
                    "• Desinstalar o app ou limpar seus dados apaga tudo. Faça backups criptografados em " +
                    "Ajustes › Backup e segurança.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ProfileFields(
                displayName = name,
                onDisplayNameChange = { name = it },
                timezone = timezone,
                onTimezoneChange = { timezone = it },
                errors = errors,
            )
            TextButton(onClick = { timezone = ZoneId.systemDefault().id }) { Text("Usar fuso do aparelho") }
            Button(onClick = { onComplete(name, timezone) }, modifier = Modifier.fillMaxWidth()) {
                Text("Começar")
            }
        }
    }
}
