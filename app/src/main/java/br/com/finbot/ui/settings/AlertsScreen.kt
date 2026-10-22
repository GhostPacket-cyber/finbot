package br.com.finbot.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.config.AlertSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek

class AlertsViewModel(private val container: AppContainer) : ViewModel() {
    private val config = container.configRepository

    val settings: StateFlow<AlertSettings?> = config.config.map { it.alerts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A reescrita só funciona com consentimento e chave; mostramos o motivo quando falta. */
    val aiHint: StateFlow<String?> = config.config.map { if (it.aiConsentGranted) null else "Requer \"Envio ao Gemini\" ligado e a chave salva em Ajustes." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(block: (AlertSettings) -> AlertSettings) {
        viewModelScope.launch {
            config.setAlertSettings(block(config.config.first().alerts))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AlertsViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}

private val DAYS = listOf(
    DayOfWeek.MONDAY to "segunda", DayOfWeek.TUESDAY to "terça", DayOfWeek.WEDNESDAY to "quarta", DayOfWeek.THURSDAY to "quinta",
    DayOfWeek.FRIDAY to "sexta", DayOfWeek.SATURDAY to "sábado", DayOfWeek.SUNDAY to "domingo",
)

@Composable
fun AlertsScreen(onBack: () -> Unit, viewModel: AlertsViewModel = viewModel(factory = AlertsViewModel.Factory)) {
    BackHandler(onBack = onBack)
    val loaded by viewModel.settings.collectAsStateWithLifecycle()
    val aiHint by viewModel.aiHint.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Alertas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Text(
            "Os alertas são calculados por regras neste aparelho e aparecem no chat como mensagens do FinBot. " +
                "Cada um é enviado uma única vez.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val s = loaded ?: return@Column

        ToggleRow("Orçamento por categoria", "Avisa em 80% e 100% do limite mensal. Defina os limites em Lançamentos › Categorias › ⋮ › Orçamento mensal.", s.budget) {
            viewModel.update { a -> a.copy(budget = it) }
        }
        HorizontalDivider()
        ToggleRow("Possível cobrança duplicada", "Mesmo valor e estabelecimento parecido em pouco tempo.", s.duplicate) {
            viewModel.update { a -> a.copy(duplicate = it) }
        }
        if (s.duplicate) {
            Choice("Janela", listOf(6, 12, 24, 48, 72).map { it to "$it horas" }, s.duplicateWindowHours) { v -> viewModel.update { it.copy(duplicateWindowHours = v) } }
        }
        HorizontalDivider()
        ToggleRow("Nova assinatura recorrente", "Mesmo valor e estabelecimento em meses seguidos; sugere cadastrar como conta.", s.subscription) {
            viewModel.update { a -> a.copy(subscription = it) }
        }
        HorizontalDivider()
        ToggleRow("Gasto fora do padrão", "Categoria com gasto do mês acima da média dos 3 meses anteriores.", s.unusual) {
            viewModel.update { a -> a.copy(unusual = it) }
        }
        if (s.unusual) {
            Choice("Acima da média em", listOf(25, 50, 75, 100, 150).map { it to "$it%" }, s.unusualThresholdPercent) { v ->
                viewModel.update { it.copy(unusualThresholdPercent = v) }
            }
        }
        HorizontalDivider()
        ToggleRow("Resumo semanal", "Despesas, receitas, maior gasto e contas da semana seguinte.", s.weekly) {
            viewModel.update { a -> a.copy(weekly = it) }
        }
        if (s.weekly) {
            Choice("Dia", DAYS, s.weeklyDay) { v -> viewModel.update { it.copy(weeklyDay = v) } }
            Choice("Horário", (0..23).map { it * 60 to "%02d:00".format(it) }, s.weeklyMinuteOfDay - s.weeklyMinuteOfDay % 60) { v ->
                viewModel.update { it.copy(weeklyMinuteOfDay = v) }
            }
        }
        HorizontalDivider()
        ToggleRow("Também como notificação", "Além do chat, mostra uma notificação (na tela bloqueada, só \"Novo alerta do FinBot\").", s.notify) {
            viewModel.update { a -> a.copy(notify = it) }
        }
        ToggleRow(
            "Texto mais amigável com IA",
            "O Gemini só reescreve o texto do alerta, que inclui valores e nomes de categoria ou estabelecimento. " +
                "Os números são conferidos: se mudarem, vale o texto original." + (aiHint?.let { " $it" } ?: ""),
            s.aiRewrite,
        ) { viewModel.update { a -> a.copy(aiRewrite = it) } }
    }
}

/** Linha inteira clicável com papel de chave: o leitor de tela anuncia o rótulo e o estado. */
@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: "—"
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $current", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}
