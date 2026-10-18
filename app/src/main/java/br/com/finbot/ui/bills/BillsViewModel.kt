package br.com.finbot.ui.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.data.ShortMonthPolicy
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.OccurrenceView
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.data.entity.ReminderEntity
import br.com.finbot.data.repository.BillRepository
import br.com.finbot.domain.Money
import br.com.finbot.domain.TransactionForm
import br.com.finbot.scheduling.ReminderOffsets
import br.com.finbot.scheduling.ReminderRepeat
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

data class BillFormState(
    val billId: Long? = null,
    val title: String = "",
    val amount: String = "",
    val categoryId: Long? = null,
    val firstDue: String = "",
    val monthly: Boolean = true,
    val policy: ShortMonthPolicy = ShortMonthPolicy.LAST_DAY_OF_MONTH,
    val offsets: Set<Int> = ReminderOffsets.DEFAULT.toSet(),
    val error: String? = null,
)

data class ReminderFormState(
    val title: String = "",
    val date: String = "",
    val time: String = "09:00",
    val repeat: String = REPEAT_ONCE,
    val error: String? = null,
)

data class PayState(val occurrence: OccurrenceView, val date: String, val accountId: Long?, val error: String? = null)

data class BillsEvent(
    val message: String,
    val undoPaidOccurrenceId: Long? = null,
    /** Primeira conta ou lembrete salvo: a tela pede a permissão de notificação, se faltar. */
    val askNotificationPermission: Boolean = false,
)

const val REPEAT_ONCE = "ONCE"
const val REPEAT_DAILY = "DAILY"
const val REPEAT_WEEKLY = "WEEKLY"
const val REPEAT_MONTHLY = "MONTHLY"

@OptIn(ExperimentalCoroutinesApi::class)
class BillsViewModel(private val container: AppContainer) : ViewModel() {
    private val bills = container.billRepository
    private val reminders = container.reminderRepository

    private suspend fun zone(): ZoneId = container.userZone()
    private fun now(): Instant = Instant.now(container.clock)

    /** Pendentes até 40 dias à frente (inclui atrasadas). Recalcula a data-limite a cada inscrição. */
    val pending: StateFlow<List<OccurrenceView>?> = flow { emit(now().atZone(zone()).toLocalDate()) }
        .flatMapLatest { today -> bills.observePendingUntil(today.plusDays(BillRepository.FUTURE_WINDOW_DAYS)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val paidRecently: StateFlow<List<OccurrenceView>> = flow { emit(now().minusSeconds(30L * 86_400)) }
        .flatMapLatest { since -> bills.observePaidSince(since) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reminderList: StateFlow<List<ReminderEntity>> = reminders.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val expenseCategories: StateFlow<List<CategoryEntity>> = container.categoryRepository.observeActive(TransactionKind.EXPENSE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = container.accountRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val userZone: StateFlow<ZoneId> = flow { emit(zone()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ZoneId.systemDefault())

    private val _billForm = MutableStateFlow<BillFormState?>(null)
    val billForm: StateFlow<BillFormState?> = _billForm.asStateFlow()

    private val _reminderForm = MutableStateFlow<ReminderFormState?>(null)
    val reminderForm: StateFlow<ReminderFormState?> = _reminderForm.asStateFlow()

    private val _pay = MutableStateFlow<PayState?>(null)
    val pay: StateFlow<PayState?> = _pay.asStateFlow()

    private val _event = MutableStateFlow<BillsEvent?>(null)
    val event: StateFlow<BillsEvent?> = _event.asStateFlow()

    // Conta: criar e editar.
    fun openNewBill() {
        viewModelScope.launch {
            _billForm.value = BillFormState(firstDue = TransactionForm.formatDate(now().atZone(zone()).toLocalDate()))
        }
    }

    fun openEditBill(billId: Long) {
        viewModelScope.launch {
            val b = bills.getBill(billId) ?: return@launch
            _billForm.value = BillFormState(
                billId = b.id,
                title = b.title,
                amount = TransactionForm.formatAmount(b.amountCents),
                categoryId = b.categoryId,
                firstDue = TransactionForm.formatDate(b.firstDueDate),
                monthly = b.recurrenceRule != null,
                policy = b.shortMonthPolicy,
                offsets = ReminderOffsets.parse(b.remindOffsetsDays).toSet(),
            )
        }
    }

    fun updateBillForm(t: (BillFormState) -> BillFormState) = _billForm.update { it?.let(t) }
    fun closeBillForm() { _billForm.value = null }

    fun saveBill() {
        val f = _billForm.value ?: return
        val cents = Money.parseCents(f.amount)
        val due = parseDate(f.firstDue)
        val error = when {
            f.title.isBlank() -> "Informe um título."
            cents == null || !Money.isValidAmount(cents) -> "Valor inválido."
            due == null -> "Data de vencimento inválida (dd/mm/aaaa)."
            else -> null
        }
        if (error != null) {
            _billForm.update { it?.copy(error = error) }
            return
        }
        viewModelScope.launch {
            guard({ msg -> _billForm.update { it?.copy(error = msg) } }) {
                val first = isFirstSchedule()
                if (f.billId == null) {
                    bills.createBill(f.title, cents!!, f.categoryId, due!!, if (f.monthly) BillRepository.MONTHLY else null, f.policy, f.offsets.toList())
                } else {
                    bills.updateBill(f.billId, f.title, cents!!, f.categoryId, f.policy, f.offsets.toList())
                }
                _billForm.value = null
                container.reminderProcessor.run() // gera ocorrências e entrega avisos já devidos
                _event.value = BillsEvent(if (f.billId == null) "Conta cadastrada." else "Conta atualizada.", askNotificationPermission = first)
            }
        }
    }

    fun deactivateBill(billId: Long) {
        viewModelScope.launch {
            guard({ _event.value = BillsEvent(it) }) {
                bills.deactivate(billId)
                _billForm.value = null
                _event.value = BillsEvent("Conta desativada. Pagamentos já feitos continuam nos lançamentos.")
            }
        }
    }

    // Pagamento.
    fun openPay(occurrence: OccurrenceView) {
        viewModelScope.launch {
            val today = now().atZone(zone()).toLocalDate()
            _pay.value = PayState(occurrence, TransactionForm.formatDate(today), container.accountRepository.ensureDefault().id)
        }
    }

    fun updatePay(t: (PayState) -> PayState) = _pay.update { it?.let(t) }
    fun closePay() { _pay.value = null }

    fun confirmPay() {
        val p = _pay.value ?: return
        viewModelScope.launch {
            val z = zone()
            val today = now().atZone(z).toLocalDate()
            val date = parseDate(p.date)
            if (date == null || date.isAfter(today)) {
                _pay.update { it?.copy(error = "Data de pagamento inválida ou no futuro.") }
                return@launch
            }
            val paidAt = if (date == today) now() else date.atTime(LocalTime.NOON).atZone(z).toInstant()
            guard({ msg -> _pay.update { it?.copy(error = msg) } }) {
                bills.markPaid(p.occurrence.occurrenceId, paidAt, p.accountId)
                _pay.value = null
                _event.value = BillsEvent("${p.occurrence.title} paga: ${Money.format(p.occurrence.amountCents)}.", undoPaidOccurrenceId = p.occurrence.occurrenceId)
            }
        }
    }

    fun undoPay(occurrenceId: Long) {
        viewModelScope.launch {
            guard({ _event.value = BillsEvent(it) }) {
                bills.unmarkPaid(occurrenceId)
                _event.value = BillsEvent("Pagamento desfeito. A conta voltou para a lista a pagar.")
            }
        }
    }

    // Lembretes pessoais.
    fun openNewReminder() {
        viewModelScope.launch {
            val tomorrow = now().atZone(zone()).toLocalDate().plusDays(1)
            _reminderForm.value = ReminderFormState(date = TransactionForm.formatDate(tomorrow))
        }
    }

    fun updateReminderForm(t: (ReminderFormState) -> ReminderFormState) = _reminderForm.update { it?.let(t) }
    fun closeReminderForm() { _reminderForm.value = null }

    fun saveReminder() {
        val f = _reminderForm.value ?: return
        val date = parseDate(f.date)
        val time = try {
            LocalTime.parse(f.time.trim(), TIME)
        } catch (_: DateTimeParseException) {
            null
        }
        if (f.title.isBlank() || date == null || time == null) {
            _reminderForm.update { it?.copy(error = "Preencha o texto, a data (dd/mm/aaaa) e a hora (hh:mm).") }
            return
        }
        val repeat = when (f.repeat) {
            REPEAT_DAILY -> ReminderRepeat.Daily
            REPEAT_WEEKLY -> ReminderRepeat.Weekly
            REPEAT_MONTHLY -> ReminderRepeat.Monthly(date.dayOfMonth)
            else -> ReminderRepeat.Once
        }
        viewModelScope.launch {
            guard({ msg -> _reminderForm.update { it?.copy(error = msg) } }) {
                val first = isFirstSchedule()
                reminders.create(f.title, LocalDateTime.of(date, time), repeat, zone())
                _reminderForm.value = null
                _event.value = BillsEvent("Lembrete criado.", askNotificationPermission = first)
            }
        }
    }

    fun setReminderEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { guard({ _event.value = BillsEvent(it) }) { reminders.setEnabled(id, enabled, zone()) } }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch {
            guard({ _event.value = BillsEvent(it) }) {
                reminders.delete(id)
                _event.value = BillsEvent("Lembrete excluído.")
            }
        }
    }

    fun eventShown() { _event.value = null }

    private suspend fun isFirstSchedule(): Boolean =
        bills.observeActiveBills().first().isEmpty() && reminders.observeAll().first().isEmpty()

    private suspend fun guard(onError: (String) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            onError(e.message ?: "Dados inválidos.")
        } catch (e: IllegalStateException) {
            onError(e.message ?: "Operação inválida.")
        } catch (e: Exception) {
            SafeLog.e("Falha em contas/lembretes", e)
            onError("Não foi possível concluir.")
        }
    }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun parseDate(text: String): LocalDate? = try {
            LocalDate.parse(text.trim(), DATE)
        } catch (_: DateTimeParseException) {
            null
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BillsViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}
