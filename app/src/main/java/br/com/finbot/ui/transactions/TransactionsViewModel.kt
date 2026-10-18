package br.com.finbot.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.dao.KindTotals
import br.com.finbot.data.dao.TransactionRow
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.domain.FormResult
import br.com.finbot.domain.InvalidTransactionException
import br.com.finbot.domain.PeriodPreset
import br.com.finbot.domain.TransactionFilter
import br.com.finbot.domain.TransactionForm
import br.com.finbot.domain.TransactionFormErrors
import br.com.finbot.domain.TransactionFormInput
import br.com.finbot.security.SafeLog
import br.com.finbot.ui.zoneOrDefault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Formulário aberto: [transactionId] null = novo lançamento. */
data class FormState(
    val transactionId: Long?,
    val input: TransactionFormInput,
    val errors: TransactionFormErrors = TransactionFormErrors(),
    val originalOccurredAt: Instant? = null,
    val saving: Boolean = false,
    val isPendingReview: Boolean = false,
)

/** Mensagem única para o snackbar; [undoDeletedId] habilita o botão "Desfazer". */
data class UiEvent(val message: String, val undoDeletedId: Long? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(private val container: AppContainer) : ViewModel() {
    private val transactions = container.transactionRepository

    private val _filter = MutableStateFlow(TransactionFilter())
    val filter: StateFlow<TransactionFilter> = _filter.asStateFlow()

    private val limit = MutableStateFlow(PAGE_SIZE)

    private val zone = container.configRepository.config.map { zoneOrDefault(it.timezone) }

    /** Fuso do usuário para exibir datas (o banco guarda UTC). */
    val userZone: StateFlow<ZoneId> = zone.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ZoneId.systemDefault())

    private val query = combine(_filter, zone) { f, z -> f.toQuery(Instant.now(container.clock).atZone(z).toLocalDate(), z) }

    /** Lista reativa: muda com filtros, paginação e qualquer gravação no banco (inclusive pelo chat). */
    val rows: StateFlow<List<TransactionRow>?> = combine(query, limit) { q, l -> q to l }
        .flatMapLatest { (q, l) -> transactions.observeFiltered(q, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totals: StateFlow<KindTotals?> = query.flatMapLatest { transactions.observeFilteredTotals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasMore: StateFlow<Boolean> = combine(rows, limit) { r, l -> r != null && r.size >= l }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val categories: StateFlow<List<CategoryEntity>> = container.categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = container.accountRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingReview: StateFlow<List<TransactionRow>> = transactions.observePendingReview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow<FormState?>(null)
    val form: StateFlow<FormState?> = _form.asStateFlow()

    private val _event = MutableStateFlow<UiEvent?>(null)
    val event: StateFlow<UiEvent?> = _event.asStateFlow()

    // Filtros. Mudar qualquer filtro volta para a primeira página.
    private fun updateFilter(transform: (TransactionFilter) -> TransactionFilter) {
        _filter.update(transform)
        limit.value = PAGE_SIZE
    }

    fun setPeriod(p: PeriodPreset) = updateFilter { it.copy(period = p) }
    fun setKind(k: TransactionKind?) = updateFilter {
        // Categoria de outro tipo deixa de fazer sentido.
        val keepCategory = it.categoryId?.let { id -> categories.value.firstOrNull { c -> c.id == id }?.kind == k || k == null } ?: true
        it.copy(kind = k, categoryId = if (keepCategory) it.categoryId else null)
    }
    fun setCategory(id: Long?) = updateFilter { it.copy(categoryId = id) }
    fun setAccount(id: Long?) = updateFilter { it.copy(accountId = id) }
    fun setSource(s: TransactionSource?) = updateFilter { it.copy(source = s) }
    fun setSearch(text: String) = updateFilter { it.copy(search = text) }
    fun clearFilters() = updateFilter { TransactionFilter() }

    fun loadMore() {
        if (hasMore.value) limit.update { it + PAGE_SIZE }
    }

    // Formulário.
    fun openNew(kind: TransactionKind = TransactionKind.EXPENSE) {
        viewModelScope.launch {
            val z = zone.first()
            val today = Instant.now(container.clock).atZone(z).toLocalDate()
            val account = container.accountRepository.ensureDefault()
            _form.value = FormState(null, TransactionFormInput(kind = kind, accountId = account.id, date = TransactionForm.formatDate(today)))
        }
    }

    fun openEdit(row: TransactionRow) {
        viewModelScope.launch {
            val t = row.transaction
            val z = zone.first()
            _form.value = FormState(
                transactionId = t.id,
                input = TransactionFormInput(
                    kind = t.kind,
                    amount = TransactionForm.formatAmount(t.amountCents),
                    description = t.description,
                    categoryId = t.categoryId,
                    accountId = t.accountId,
                    date = TransactionForm.formatDate(t.occurredAt.atZone(z).toLocalDate()),
                    note = t.note.orEmpty(),
                ),
                originalOccurredAt = t.occurredAt,
                isPendingReview = t.status == TransactionStatus.PENDING_REVIEW,
            )
        }
    }

    fun updateForm(transform: (TransactionFormInput) -> TransactionFormInput) {
        _form.update { it?.copy(input = transform(it.input)) }
    }

    fun closeForm() {
        _form.value = null
    }

    fun saveForm() {
        val state = _form.value ?: return
        if (state.saving) return
        viewModelScope.launch {
            val z = zone.first()
            when (val result = TransactionForm.parse(state.input, z, Instant.now(container.clock), state.originalOccurredAt)) {
                is FormResult.Invalid -> _form.update { it?.copy(errors = result.errors) }
                is FormResult.Valid -> {
                    _form.update { it?.copy(saving = true, errors = TransactionFormErrors()) }
                    try {
                        if (state.transactionId == null) {
                            transactions.create(result.draft, TransactionSource.MANUAL)
                            _event.value = UiEvent("Lançamento registrado.")
                        } else {
                            transactions.update(state.transactionId, result.draft)
                            _event.value = UiEvent("Lançamento atualizado.")
                        }
                        _form.value = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InvalidTransactionException) {
                        _form.update { it?.copy(saving = false, errors = TransactionFormErrors(general = e.errors)) }
                    } catch (e: Exception) {
                        SafeLog.e("Falha ao salvar lançamento", e)
                        _form.update { it?.copy(saving = false, errors = TransactionFormErrors(general = listOf("Não foi possível salvar."))) }
                    }
                }
            }
        }
    }

    /** Exclusão lógica (já confirmada no diálogo); o snackbar oferece desfazer. */
    fun delete(id: Long) {
        viewModelScope.launch {
            runCatchingUser("Não foi possível excluir.") {
                transactions.softDelete(id)
                _form.value = null
                _event.value = UiEvent("Lançamento excluído.", undoDeletedId = id)
            }
        }
    }

    fun undoDelete(id: Long) {
        viewModelScope.launch {
            runCatchingUser("Não foi possível desfazer.") {
                transactions.restore(id)
                _event.value = UiEvent("Exclusão desfeita.")
            }
        }
    }

    // Revisão em lote.
    fun confirmReview(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatchingUser("Não foi possível confirmar. Nada foi alterado.") {
                transactions.confirmAll(ids)
                _event.value = UiEvent("${ids.size} lançamento(s) confirmado(s).")
            }
        }
    }

    fun discardReview(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatchingUser("Não foi possível descartar. Nada foi alterado.") {
                transactions.discardAll(ids)
                _event.value = UiEvent("${ids.size} lançamento(s) descartado(s).")
            }
        }
    }

    fun eventShown() {
        _event.value = null
    }

    private suspend fun runCatchingUser(failure: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("Falha em lançamentos", e)
            _event.value = UiEvent(failure)
        }
    }

    companion object {
        const val PAGE_SIZE = 30

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TransactionsViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}

