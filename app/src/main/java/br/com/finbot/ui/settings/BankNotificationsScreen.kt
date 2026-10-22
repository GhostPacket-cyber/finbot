package br.com.finbot.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.BuildConfig
import br.com.finbot.FinBotApp
import br.com.finbot.domain.Money
import br.com.finbot.notifications.BankApp
import br.com.finbot.notifications.BankApps
import br.com.finbot.notifications.BankEventType
import br.com.finbot.notifications.BankNotificationListener
import br.com.finbot.notifications.BankNotificationParser
import br.com.finbot.ui.zoneOrDefault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

data class BankNotificationsState(
    val enabled: Boolean = false,
    val packages: Set<String> = emptySet(),
)

/** Estado do sistema (fora do DataStore): lido de novo a cada retorno à tela. */
data class SystemAccess(val componentEnabled: Boolean = false, val hasAccess: Boolean = false, val installed: List<BankApp> = emptyList())

class BankNotificationsViewModel(private val app: Application) : ViewModel() {
    private val container = (app as FinBotApp).container
    private val config = container.configRepository

    val state: StateFlow<BankNotificationsState> = config.config
        .map { BankNotificationsState(it.bankNotificationsEnabled, it.bankNotificationPackages) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BankNotificationsState())

    private val _system = MutableStateFlow(SystemAccess())
    val system: StateFlow<SystemAccess> = _system.asStateFlow()

    fun refresh() {
        val pm = app.packageManager
        val installed = BankApps.CATALOG.filter { bank ->
            try {
                pm.getApplicationInfo(bank.packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        } + if (BuildConfig.DEBUG) listOf(BankApp(BankApps.ADB_TEST_PACKAGE, "Teste via ADB (só depuração)")) else emptyList()
        _system.value = SystemAccess(
            componentEnabled = BankNotificationListener.isComponentEnabled(app),
            hasAccess = BankNotificationListener.hasAccess(app),
            installed = installed,
        )
    }

    /** Desligar desativa o serviço no sistema: nenhuma notificação chega ao app, mesmo com o acesso concedido. */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            config.setBankNotificationsEnabled(enabled)
            BankNotificationListener.setComponentEnabled(app, enabled)
            refresh()
        }
    }

    fun togglePackage(packageName: String, on: Boolean) {
        viewModelScope.launch {
            val current = config.config.first().bankNotificationPackages
            config.setBankNotificationPackages(if (on) current + packageName else current - packageName)
        }
    }

    /** Testa um texto colado: mostra o que seria extraído. Não grava nada. */
    suspend fun tryText(text: String): String {
        val zone = zoneOrDefault(config.config.first().timezone)
        val event = BankNotificationParser.parse(listOf(text), Instant.now(container.clock), zone)
            ?: return "Nenhum padrão reconhecido. Esta notificação seria ignorada."
        val type = when (event.type) {
            BankEventType.PURCHASE_CREDIT -> "Compra (crédito)"
            BankEventType.PURCHASE_DEBIT -> "Compra (débito)"
            BankEventType.PIX_SENT -> "Pix enviado"
            BankEventType.PAYMENT_SENT -> "Pagamento"
            BankEventType.PIX_RECEIVED -> "Pix recebido"
            BankEventType.TRANSFER_RECEIVED -> "Transferência recebida"
        }
        val whenText = event.occurredAt.atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        return "$type · ${Money.format(event.amountCents)} · ${event.description} · $whenText"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BankNotificationsViewModel(this[APPLICATION_KEY] as Application) }
        }
    }
}

@Composable
fun BankNotificationsScreen(onBack: () -> Unit, viewModel: BankNotificationsViewModel = viewModel(factory = BankNotificationsViewModel.Factory)) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sample by rememberSaveable { mutableStateOf("") }
    var sampleResult by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Notificações de banco", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Text(
            "O FinBot pode ler as notificações de compra, Pix e transferência dos apps de banco que você escolher e sugerir " +
                "lançamentos para revisar. Nada vira lançamento sem a sua confirmação.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("O que é lido e o que não sai do aparelho", style = MaterialTheme.typography.titleSmall)
                Text("• Só notificações dos apps marcados abaixo. As de outros apps são descartadas sem ler o conteúdo.", style = MaterialTheme.typography.bodySmall)
                Text("• Do texto, guardamos só valor, estabelecimento e data. O texto da notificação não é salvo nem registrado.", style = MaterialTheme.typography.bodySmall)
                Text("• Nada é enviado para fora do aparelho (nem para a IA).", style = MaterialTheme.typography.bodySmall)
                Text("• Para revogar: desligue a chave abaixo ou retire o acesso em Configurações do Android › Acesso a notificações.", style = MaterialTheme.typography.bodySmall)
                Text("• Códigos de verificação ficam ocultos para o FinBot no Android 15 ou superior.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ler notificações de banco", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
        }

        if (state.enabled) {
            val accessText = if (system.hasAccess) "Acesso a notificações: concedido" else "Acesso a notificações: falta conceder no Android"
            Text(accessText, color = if (system.hasAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                try {
                    context.startActivity(BankNotificationListener.accessSettingsIntent(context))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (system.hasAccess) "Revogar no Android" else "Conceder acesso no Android") }
            if (!system.hasAccess) {
                Text(
                    "No Android 13 ou superior, se a opção aparecer bloqueada (\"configuração restrita\"), abra Informações do app › ⋮ › Permitir configurações restritas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Apps de banco", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (system.installed.isEmpty()) {
                Text("Nenhum app de banco conhecido está instalado.", style = MaterialTheme.typography.bodySmall)
            }
            system.installed.forEach { bank ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bank.packageName in state.packages, onCheckedChange = { viewModel.togglePackage(bank.packageName, it) })
                    Text(bank.name, modifier = Modifier.weight(1f))
                }
            }
            Text(
                "Aparecem só os apps instalados. Se o texto de um banco mudar, a notificação é ignorada até atualizarmos o padrão.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("Testar um texto", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it.take(500); sampleResult = null },
            label = { Text("Cole o texto de uma notificação") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { scope.launch { sampleResult = viewModel.tryText(sample) } }, enabled = sample.isNotBlank()) {
            Text("Ver o que seria extraído")
        }
        sampleResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("O teste não grava nada.", style = MaterialTheme.typography.bodySmall)
    }
}
