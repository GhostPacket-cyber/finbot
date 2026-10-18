package br.com.finbot.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.R
import br.com.finbot.assistant.ActionCard
import br.com.finbot.assistant.ActionPayload
import br.com.finbot.assistant.CardStatus
import br.com.finbot.assistant.ChatItem
import br.com.finbot.assistant.ChatTexts
import br.com.finbot.data.ChatRole
import br.com.finbot.data.TransactionKind
import br.com.finbot.domain.Money

@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    val voice = rememberVoiceInput(aiReady = aiReady, onMaxDuration = viewModel::sendAudio)

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        viewModel.send(text)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chat",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (!items.isNullOrEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Apagar histórico") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = items
            val listState = rememberLazyListState()
            // Mensagem nova (enviada ou recebida): volta para o fim da conversa, mesmo após rolar ou girar a tela.
            val newestId = list?.firstOrNull()?.id
            LaunchedEffect(newestId) {
                if (newestId != null) listState.animateScrollToItem(0)
            }
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(name)
                else -> LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { item ->
                        MessageRow(
                            item = item,
                            busy = busy,
                            onConfirm = viewModel::confirm,
                            onCancel = viewModel::cancel,
                            onEdit = viewModel::startEdit,
                            onQuickReply = viewModel::send,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(onClick = { input = "gastei " }, label = { Text("+ Gasto") })
            AssistChip(onClick = { input = "recebi " }, label = { Text("+ Receita") })
            AssistChip(onClick = { viewModel.send("/saldo") }, enabled = !busy, label = { Text("Saldo") })
            AssistChip(onClick = { viewModel.send("/mes") }, enabled = !busy, label = { Text("Resumo do mês") })
        }

        if (busy) {
            Text(
                "FinBot está pensando…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (voice.recording) {
            RecordingBar(
                seconds = voice.elapsedSeconds,
                onCancel = voice::cancel,
                onSend = { voice.stop()?.let(viewModel::sendAudio) },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(500) },
                    placeholder = { Text("Ex.: gastei 45,90 no mercado") },
                    label = { Text("Mensagem") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                if (input.isBlank()) {
                    MicButton(
                        enabled = !busy,
                        onStart = voice::requestStart,
                        onHoldRelease = { voice.stop()?.let(viewModel::sendAudio) },
                    )
                } else {
                    IconButton(onClick = ::submit, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_send), contentDescription = "Enviar")
                    }
                }
            }
        }
    }

    if (voice.showAiNeeded) {
        AlertDialog(
            onDismissRequest = voice::dismissAiNeeded,
            title = { Text("Áudio precisa da IA") },
            text = { Text(ChatTexts.AUDIO_NEEDS_AI) },
            confirmButton = { TextButton(onClick = voice::dismissAiNeeded) { Text("Entendi") } },
        )
    }

    editing?.let { state ->
        EditTransactionDialog(state = state, onDismiss = viewModel::dismissEdit, onConfirm = viewModel::confirmEdit)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Apagar histórico do chat?") },
            text = { Text("As mensagens serão apagadas deste aparelho. Os lançamentos registrados continuam.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun EmptyState(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (name.isBlank()) "Olá!" else "Olá, $name!",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(ChatTexts.HELP, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MessageRow(
    item: ChatItem,
    busy: Boolean,
    onConfirm: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onEdit: (Long, ActionPayload.CreateTransaction) -> Unit,
    onQuickReply: (String) -> Unit,
) {
    val mine = item.role == ChatRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(
            if (mine) "Você" else "FinBot",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val card = item.card
        if (card?.payload != null) {
            ActionCardView(card, busy, onConfirm, onCancel, onEdit)
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(item.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (item.quickReply != null && item.quickReplyLabel != null) {
            OutlinedButton(onClick = { onQuickReply(item.quickReply) }, enabled = !busy) { Text(item.quickReplyLabel) }
        }
    }
}

@Composable
private fun ActionCardView(
    card: ActionCard,
    busy: Boolean,
    onConfirm: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onEdit: (Long, ActionPayload.CreateTransaction) -> Unit,
) {
    Card(
        modifier = Modifier.widthIn(max = 340.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (val p = card.payload) {
                is ActionPayload.CreateTransaction -> {
                    Text(
                        if (p.kind == TransactionKind.EXPENSE) "Nova despesa" else "Nova receita",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(Money.format(p.amountCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(p.description)
                    Text("Categoria: ${p.categoryName ?: "Sem categoria"}", style = MaterialTheme.typography.bodySmall)
                    Text("Data: ${ChatTexts.date(p.date)}", style = MaterialTheme.typography.bodySmall)
                }
                is ActionPayload.CreateBatch -> {
                    Text("${p.items.size} lançamentos", style = MaterialTheme.typography.labelLarge)
                    p.items.forEach { item ->
                        Text(
                            "${if (item.kind == TransactionKind.EXPENSE) "−" else "+"}${Money.format(item.amountCents)} " +
                                "${item.description} · ${item.categoryName ?: "Sem categoria"} · ${ChatTexts.date(item.date)}",
                        )
                    }
                    Text(ChatTexts.batchTotals(p), fontWeight = FontWeight.SemiBold)
                }
                is ActionPayload.DeleteTransaction -> {
                    Text("Excluir lançamento?", style = MaterialTheme.typography.labelLarge)
                    Text(Money.format(p.amountCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${p.description} · ${ChatTexts.date(p.date)}")
                }
                null -> Unit
            }
            when (card.status) {
                CardStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { onConfirm(card.actionId) }, enabled = !busy) {
                        Text(if (card.payload is ActionPayload.DeleteTransaction) "Excluir" else "Confirmar")
                    }
                    val payload = card.payload
                    if (payload is ActionPayload.CreateTransaction) {
                        TextButton(onClick = { onEdit(card.actionId, payload) }, enabled = !busy) { Text("Editar") }
                    }
                    TextButton(onClick = { onCancel(card.actionId) }, enabled = !busy) { Text("Cancelar") }
                }
                else -> Text(
                    when (card.status) {
                        CardStatus.CONFIRMED -> "✓ Confirmado"
                        CardStatus.CANCELLED -> "Cancelado"
                        else -> "Expirado — envie a mensagem de novo"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
