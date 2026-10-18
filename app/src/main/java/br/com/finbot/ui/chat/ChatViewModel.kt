package br.com.finbot.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.FinBotApp
import br.com.finbot.assistant.ActionPayload
import br.com.finbot.assistant.ChatAssistant
import br.com.finbot.assistant.ChatItem
import br.com.finbot.config.ConfigRepository
import br.com.finbot.data.entity.CategoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class EditState(
    val actionId: Long,
    val payload: ActionPayload.CreateTransaction,
    val categories: List<CategoryEntity>,
    val today: LocalDate,
)

class ChatViewModel(private val assistant: ChatAssistant, config: ConfigRepository) : ViewModel() {

    /** null enquanto o histórico ainda não carregou. */
    val items: StateFlow<List<ChatItem>?> = assistant.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val displayName: StateFlow<String> = config.config.map { it.displayName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _editing = MutableStateFlow<EditState?>(null)
    val editing: StateFlow<EditState?> = _editing.asStateFlow()

    // Operações em fila: dois toques rápidos em Confirmar não rodam em paralelo.
    private val mutex = Mutex()

    private fun enqueue(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                _busy.value = true
                try {
                    block()
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    fun send(text: String) = enqueue { assistant.send(text) }

    fun confirm(actionId: Long) = enqueue { assistant.confirm(actionId) }

    fun cancel(actionId: Long) = enqueue { assistant.cancel(actionId) }

    fun startEdit(actionId: Long, payload: ActionPayload.CreateTransaction) {
        viewModelScope.launch {
            _editing.value = EditState(actionId, payload, assistant.categoriesFor(payload.kind), assistant.today())
        }
    }

    fun dismissEdit() {
        _editing.value = null
    }

    fun confirmEdit(edited: ActionPayload.CreateTransaction) {
        val state = _editing.value ?: return
        _editing.value = null
        enqueue { assistant.confirmEdited(state.actionId, edited) }
    }

    fun clearHistory() = enqueue { assistant.clearHistory() }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as FinBotApp).container
                ChatViewModel(container.chatAssistant, container.configRepository)
            }
        }
    }
}
