package br.com.finbot.ui.reports

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.data.dao.OccurrenceView
import br.com.finbot.data.repository.MonthlyReport
import br.com.finbot.domain.Periods
import br.com.finbot.reports.CsvExporter
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

enum class ExportScope { MONTH, ALL }

class ReportsViewModel(private val container: AppContainer) : ViewModel() {
    private val reports = container.reportRepository

    private val _month = MutableStateFlow<YearMonth?>(null)
    val month: StateFlow<YearMonth?> = _month.asStateFlow()

    private val _report = MutableStateFlow<MonthlyReport?>(null)
    val report: StateFlow<MonthlyReport?> = _report.asStateFlow()

    private val _upcoming = MutableStateFlow<List<OccurrenceView>>(emptyList())
    val upcoming: StateFlow<List<OccurrenceView>> = _upcoming.asStateFlow()

    private val _currentMonth = MutableStateFlow<YearMonth?>(null)
    val currentMonth: StateFlow<YearMonth?> = _currentMonth.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var pendingExport: ExportScope = ExportScope.MONTH

    /** Recalcula (ao abrir a aba, ao trocar de mês e ao voltar ao app: o relatório não é reativo). */
    fun refresh() {
        viewModelScope.launch {
            val zone = container.userZone()
            val today = Instant.now(container.clock).atZone(zone).toLocalDate()
            val current = YearMonth.from(today)
            _currentMonth.value = current
            val month = _month.value ?: current.also { _month.value = it }
            _report.value = reports.monthly(month, zone, today)
            _upcoming.value = reports.upcomingBills(today.plusDays(UPCOMING_DAYS)).take(5)
        }
    }

    fun shiftMonth(delta: Long) {
        val m = _month.value ?: return
        val target = m.plusMonths(delta)
        if (_currentMonth.value?.let { target.isAfter(it) } == true) return
        _month.value = target
        _report.value = null
        refresh()
    }

    /** Nome sugerido para o arquivo; o usuário escolhe onde salvar pelo seletor do sistema. */
    fun prepareExport(scope: ExportScope): String {
        pendingExport = scope
        val m = _month.value
        return if (scope == ExportScope.MONTH && m != null) "finbot-$m.csv" else "finbot-lancamentos.csv"
    }

    fun writeExport(uri: Uri, resolver: ContentResolver) {
        val scope = pendingExport
        viewModelScope.launch {
            try {
                val zone: ZoneId = container.userZone()
                val range = if (scope == ExportScope.MONTH) _month.value?.let { Periods.month(it, zone) } else null
                val rows = reports.exportRows(range)
                val csv = CsvExporter.export(rows, zone)
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        ?: error("Destino indisponível")
                }
                SafeLog.i("CSV exportado: ${rows.size} linhas")
                _message.value = "Exportado: ${rows.size} lançamento(s)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao exportar CSV", e)
                _message.value = "Não foi possível exportar."
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        const val UPCOMING_DAYS = 7L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportsViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}
