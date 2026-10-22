package br.com.finbot.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import br.com.finbot.BuildConfig

/** Componente de código aberto incluído no APK. */
private data class OpenSourceComponent(val name: String, val license: String)

/**
 * Bibliotecas que entram no app instalado (as de teste ficam fora). Todas Apache 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
private val COMPONENTS = listOf(
    OpenSourceComponent("Kotlin (biblioteca padrão) — JetBrains", "Apache 2.0"),
    OpenSourceComponent("kotlinx.coroutines — JetBrains", "Apache 2.0"),
    OpenSourceComponent("Jetpack Compose e Material 3 — Google (AndroidX)", "Apache 2.0"),
    OpenSourceComponent("AndroidX Core, Activity, Fragment, Lifecycle — Google", "Apache 2.0"),
    OpenSourceComponent("AndroidX Room (SQLite) — Google", "Apache 2.0"),
    OpenSourceComponent("AndroidX DataStore — Google", "Apache 2.0"),
    OpenSourceComponent("AndroidX WorkManager — Google", "Apache 2.0"),
    OpenSourceComponent("AndroidX Biometric — Google", "Apache 2.0"),
    OpenSourceComponent("kotlinx.serialization (dependência do Room) — JetBrains", "Apache 2.0"),
)

/** Ajustes › Sobre (ETAPA 13): versão, privacidade local, avisos importantes e licenças. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Sobre o FinBot", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Text("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " · depuração" else "")
        Text("Assistente de finanças pessoais por chat. Uso pessoal, sem fins comerciais.", style = MaterialTheme.typography.bodySmall)

        Section("Privacidade")
        Paragraph(
            "Seus dados ficam só neste aparelho, num banco SQLite privado do app. Não existe servidor nem conta do FinBot, " +
                "e o backup automático do Android está desligado. Nada é enviado a terceiros, com uma exceção: se você ligar " +
                "o Gemini em Ajustes, o texto ou o áudio da mensagem atual (e, nos recursos opcionais, descrições de extrato ou o texto " +
                "de um alerta) sai do aparelho para o Google. Sem isso, tudo funciona sem internet.",
        )
        Paragraph(
            "Não guardamos o texto das notificações de banco, o número completo da conta dos extratos nem o nome dos arquivos importados. " +
                "Os logs técnicos não têm valores, descrições ou mensagens. O backup exportado é criptografado com a senha que você escolhe.",
        )
        Paragraph("Para apagar tudo: Ajustes › Backup e segurança › Apagar todos os dados. Desinstalar o app também apaga tudo.")

        Section("Bom saber")
        Paragraph("• Sem Open Finance: o FinBot não se conecta ao seu banco. Os dados entram por você, pelo chat, por extratos OFX/CSV ou pelas notificações do banco (opcional).")
        Paragraph("• A Gemini API gratuita tem cota e pode ficar indisponível. Nesses casos, use os comandos sem IA (ex.: /gasto 35 almoço).")
        Paragraph("• Lembretes e alertas dependem das restrições de bateria do fabricante e podem atrasar. Em Xiaomi/POCO, permita a inicialização automática e use \"Sem restrições\" na bateria.")
        Paragraph("• O saldo do app é calculado pelos lançamentos registrados, não é o saldo do banco.")

        Section("Licenças de código aberto")
        Paragraph("O FinBot usa estas bibliotecas, todas sob a Licença Apache 2.0 (apache.org/licenses/LICENSE-2.0):")
        COMPONENTS.forEach { Text("• ${it.name} — ${it.license}", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Text("Ícones e textos do app: criados para este projeto.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
