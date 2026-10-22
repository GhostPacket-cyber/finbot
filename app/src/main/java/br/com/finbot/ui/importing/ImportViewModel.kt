package br.com.finbot.ui.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.ImportBatchRow
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.data.entity.CsvPresetEntity
import br.com.finbot.data.repository.CategoryOrigin
import br.com.finbot.data.repository.ImportPreview
import br.com.finbot.data.repository.ImportRepository
import br.com.finbot.gemini.AllowedCategories
import br.com.finbot.gemini.CategorizeInput
import br.com.finbot.gemini.CategorizeOutcome
import br.com.finbot.importing.CsvImporter
import br.com.finbot.importing.CsvMapping
import br.com.finbot.importing.CsvReader
import br.com.finbot.importing.OfxParser
import br.com.finbot.importing.StatementFormat
import br.com.finbot.importing.TextDecoding
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Etapas da importação. O texto do arquivo fica só em memória e é descartado ao sair. */
sealed interface ImportStep {
    data object Pick : ImportStep
    data object Loading : ImportStep

    /** CSV: o usuário ajusta as colunas. [rows] são as primeiras linhas para conferência. */
    data class Mapping(val text: String, val fileName: String, val mapping: CsvMapping, val rows: List<List<String>>, val recognized: Boolean) : ImportStep

    data class Preview(
        val preview: ImportPreview,
        val fileName: String,
        val selected: Set<Int>,
        /** Índice → categoria escolhida (sobrepõe a sugestão local). */
        val overrides: Map<Int, Long?> = emptyMap(),
        val aiOrigin: Set<Int> = emptySet(),
        val aiBusy: Boolean = false,
        val saving: Boolean = false,
        /** Mapeamento usado (CSV), para voltar e ajustar. */
        val csv: Mapping? = null,
    ) : ImportStep {
        fun categoryOf(index: Int): Long? =
            if (index in overrides) overrides[index] else preview.candidates[index].categoryId

        fun originOf(index: Int): CategoryOrigin? = when {
            index in aiOrigin -> CategoryOrigin.AI
            index in overrides -> null
            else -> preview.candidates[index].categoryOrigin
        }
    }
}

class ImportViewModel(private val container: AppContainer) : ViewModel() {
    private val imports = container.importRepository

    private val _step = MutableStateFlow<ImportStep>(ImportStep.Pick)
    val step: StateFlow<ImportStep> = _step.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Sinal para abrir a caixa de revisão depois de importar. */
    private val _imported = MutableStateFlow(false)
    val imported: StateFlow<Boolean> = _imported.asStateFlow()

    val batches: StateFlow<List<ImportBatchRow>> = imports.observeBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val presets: StateFlow<List<CsvPresetEntity>> = imports.observePresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = combine(
        container.categoryRepository.observeActive(TransactionKind.EXPENSE),
        container.categoryRepository.observeActive(TransactionKind.INCOME),
    ) { e, i -> e + i }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = container.accountRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _accountId = MutableStateFlow<Long?>(null)
    val accountId: StateFlow<Long?> = _accountId.asStateFlow()

    fun selectAccount(id: Long?) {
        _accountId.value = id
    }

    /** Lê o arquivo escolhido no seletor do sistema (SAF), com limite de 5 MB lido em blocos. */
    fun open(uri: Uri, resolver: ContentResolver) {
        _step.value = ImportStep.Loading
        viewModelScope.launch {
            try {
                val (name, declaredSize) = withContext(Dispatchers.IO) { describe(uri, resolver) }
                if (declaredSize != null && declaredSize > TextDecoding.MAX_BYTES) {
                    fail(TOO_BIG)
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { readLimited(it, TextDecoding.MAX_BYTES) } ?: error("indisponível")
                }
                if (bytes == null) {
                    fail(TOO_BIG)
                    return@launch
                }
                val text = withContext(Dispatchers.Default) { TextDecoding.decode(bytes) }
                SafeLog.i("Extrato lido: ${bytes.size} bytes")
                when (TextDecoding.detectFormat(text)) {
                    StatementFormat.OFX -> {
                        val statement = withContext(Dispatchers.Default) { OfxParser.parse(text) }
                        if (statement.items.isEmpty()) {
                            fail("Nenhum lançamento encontrado neste OFX.${errorSummary(statement.errors.size)}")
                        } else {
                            showPreview(statement, name, null)
                        }
                    }
                    StatementFormat.CSV -> openCsv(text, name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao ler extrato", e)
                fail("Não foi possível ler o arquivo. Use um extrato OFX ou CSV exportado pelo banco.")
            }
        }
    }

    private suspend fun openCsv(text: String, name: String) {
        val presetsNow = imports.listPresets()
        val suggested = CsvImporter.suggestMapping(text)
        val mapping = suggested ?: CsvMapping(CsvReader.detectSeparator(text), skipLines = 1, dateColumn = 0, descriptionColumn = 1, amountColumn = 2)
        val rows = withContext(Dispatchers.Default) { CsvReader.read(text, mapping.separator).take(PREVIEW_ROWS) }
        if (rows.isEmpty()) {
            fail("O arquivo está vazio.")
            return
        }
        _step.value = ImportStep.Mapping(text, name, mapping, rows, recognized = suggested != null)
        if (suggested == null && presetsNow.isNotEmpty()) {
            _message.value = "Não reconheci o cabeçalho. Escolha um modelo salvo ou ajuste as colunas."
        }
    }

    fun updateMapping(mapping: CsvMapping) {
        val current = _step.value as? ImportStep.Mapping ?: return
        val rows = if (mapping.separator != current.mapping.separator) CsvReader.read(current.text, mapping.separator).take(PREVIEW_ROWS) else current.rows
        _step.value = current.copy(mapping = mapping, rows = rows)
    }

    fun applyPreset(preset: CsvPresetEntity) = updateMapping(ImportRepository.presetToMapping(preset))

    fun savePreset(name: String) {
        val current = _step.value as? ImportStep.Mapping ?: return
        viewModelScope.launch {
            try {
                imports.savePreset(name, current.mapping)
                _message.value = "Modelo \"${name.trim()}\" salvo."
            } catch (e: IllegalArgumentException) {
                _message.value = e.message
            }
        }
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch { imports.deletePreset(id) }
    }

    fun previewCsv() {
        val current = _step.value as? ImportStep.Mapping ?: return
        if (!current.mapping.isValid) {
            _message.value = "Escolha as colunas de data, descrição e valor."
            return
        }
        viewModelScope.launch {
            val statement = withContext(Dispatchers.Default) { CsvImporter.parse(current.text, current.mapping) }
            if (statement.items.isEmpty()) {
                _message.value = "Nenhuma linha válida com esse mapeamento.${errorSummary(statement.errors.size)} Confira colunas, formato da data e linhas de cabeçalho."
                return@launch
            }
            showPreview(statement, current.fileName, current)
        }
    }

    private suspend fun showPreview(statement: br.com.finbot.importing.ParsedStatement, name: String, csv: ImportStep.Mapping?) {
        val preview = imports.preview(statement, container.userZone())
        _step.value = ImportStep.Preview(
            preview = preview,
            fileName = name,
            selected = preview.candidates.filter { it.selectedByDefault }.map { it.index }.toSet(),
            csv = csv,
        )
    }

    fun toggle(index: Int, on: Boolean) = updatePreview { p ->
        if (!p.preview.candidates[index].importable) p else p.copy(selected = if (on) p.selected + index else p.selected - index)
    }

    fun toggleAll(on: Boolean) = updatePreview { p ->
        p.copy(selected = if (on) p.preview.candidates.filter { it.importable }.map { it.index }.toSet() else emptySet())
    }

    fun setCategory(index: Int, categoryId: Long?) = updatePreview { p ->
        p.copy(overrides = p.overrides + (index to categoryId), aiOrigin = p.aiOrigin - index)
    }

    fun backToMapping() {
        val p = _step.value as? ImportStep.Preview ?: return
        p.csv?.let { _step.value = it }
    }

    /** Envia ao Gemini só as descrições dos itens importáveis ainda sem categoria (botão explícito). */
    fun suggestWithAi() {
        val p = _step.value as? ImportStep.Preview ?: return
        val pending = p.preview.candidates.filter { it.importable && p.categoryOf(it.index) == null }
        if (pending.isEmpty()) {
            _message.value = "Todos os itens já têm categoria."
            return
        }
        updatePreview { it.copy(aiBusy = true) }
        viewModelScope.launch {
            val cats = categories.value
            val allowed = AllowedCategories(
                cats.filter { it.kind == TransactionKind.EXPENSE }.map { it.name },
                cats.filter { it.kind == TransactionKind.INCOME }.map { it.name },
            )
            val inputs = pending.map { CategorizeInput(it.index, it.item.kind, it.item.description) }
            when (val outcome = container.aiCategorizer.suggest(inputs, allowed)) {
                is CategorizeOutcome.Failed -> {
                    updatePreview { it.copy(aiBusy = false) }
                    _message.value = outcome.message
                }
                is CategorizeOutcome.Suggested -> {
                    val ids = outcome.categories.mapNotNull { (index, name) ->
                        val kind = p.preview.candidates[index].item.kind
                        cats.firstOrNull { it.kind == kind && it.name == name }?.let { index to it.id }
                    }.toMap()
                    updatePreview { it.copy(aiBusy = false, overrides = it.overrides + ids, aiOrigin = it.aiOrigin + ids.keys) }
                    val rest = if (inputs.size > ids.size) " ${inputs.size - ids.size} ficaram sem sugestão." else ""
                    _message.value = "IA sugeriu ${ids.size} categoria(s). Confira antes de importar.$rest"
                }
            }
        }
    }

    fun commit() {
        val p = _step.value as? ImportStep.Preview ?: return
        if (p.selected.isEmpty() || p.saving) return
        updatePreview { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val result = imports.commit(p.preview, p.selected, container.userZone(), p.fileName, _accountId.value, p.overrides)
                SafeLog.i("Importação: ${result.created} novos, ${result.skipped} ignorados")
                _message.value = if (result.created == 0) {
                    "Nada novo para importar: tudo já estava no app."
                } else {
                    "${result.created} lançamento(s) enviados para revisão. Nada conta no saldo até você confirmar." +
                        if (result.skipped > 0) " ${result.skipped} já existiam e foram ignorados." else ""
                }
                _step.value = ImportStep.Pick
                if (result.created > 0) _imported.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao gravar importação", e)
                updatePreview { it.copy(saving = false) }
                _message.value = "Não foi possível importar. Nada foi gravado."
            }
        }
    }

    fun undoBatch(batchId: Long) {
        viewModelScope.launch {
            try {
                val removed = imports.undoBatch(batchId)
                _message.value = "Lote desfeito: $removed lançamento(s) removido(s)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao desfazer lote", e)
                _message.value = "Não foi possível desfazer o lote."
            }
        }
    }

    fun cancel() {
        _step.value = ImportStep.Pick
    }

    fun messageShown() {
        _message.value = null
    }

    fun importedHandled() {
        _imported.value = false
    }

    private fun updatePreview(block: (ImportStep.Preview) -> ImportStep.Preview) {
        _step.update { if (it is ImportStep.Preview) block(it) else it }
    }

    private fun fail(text: String) {
        _step.value = ImportStep.Pick
        _message.value = text
    }

    private fun errorSummary(errors: Int) = if (errors > 0) " $errors linha(s) com problema." else ""

    companion object {
        const val PREVIEW_ROWS = 12
        const val TOO_BIG = "Arquivo maior que 5 MB. Exporte um período menor no app do banco."

        /** Nome exibido e tamanho declarado pelo provedor (pode faltar). O nome nunca vai para log. */
        fun describe(uri: Uri, resolver: ContentResolver): Pair<String, Long?> {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                    val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                    return (name ?: "extrato") to size
                }
            }
            return "extrato" to null
        }

        /** Lê em blocos até [max] bytes; retorna null se o arquivo passar do limite (sem carregar o resto). */
        fun readLimited(input: InputStream, max: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ImportViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}
