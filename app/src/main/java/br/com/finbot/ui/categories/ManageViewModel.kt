package br.com.finbot.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.data.AccountType
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.CategoryWithUsage
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManageViewModel(private val container: AppContainer) : ViewModel() {
    private val categories = container.categoryRepository
    private val accounts = container.accountRepository

    val categoryList: StateFlow<List<CategoryWithUsage>> = categories.observeAllWithUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accountList: StateFlow<List<AccountEntity>> = accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun createCategory(name: String, kind: TransactionKind) = run("Categoria criada.") { categories.create(name, kind) }
    fun renameCategory(id: Long, name: String) = run("Categoria renomeada.") { categories.rename(id, name) }
    fun setCategoryArchived(id: Long, archived: Boolean) =
        run(if (archived) "Categoria arquivada." else "Categoria reativada.") {
            if (archived) categories.archive(id) else categories.unarchive(id)
        }

    fun mergeCategory(sourceId: Long, targetId: Long) {
        viewModelScope.launch {
            guard {
                val moved = categories.merge(sourceId, targetId)
                _message.value = "Categorias mescladas: $moved lançamento(s) movido(s)."
            }
        }
    }

    fun createAccount(name: String, type: AccountType) = run("Conta criada.") { accounts.create(name, type) }
    fun renameAccount(id: Long, name: String) = run("Conta renomeada.") { accounts.rename(id, name) }
    fun setAccountArchived(id: Long, archived: Boolean) =
        run(if (archived) "Conta arquivada." else "Conta reativada.") { accounts.setArchived(id, archived) }

    fun messageShown() {
        _message.value = null
    }

    private fun run(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            guard {
                block()
                _message.value = success
            }
        }
    }

    /** Erros de regra (nome duplicado, última conta, tipos diferentes) chegam como IllegalArgumentException/IllegalStateException. */
    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            _message.value = e.message ?: "Operação inválida."
        } catch (e: IllegalStateException) {
            _message.value = e.message ?: "Operação inválida."
        } catch (e: Exception) {
            SafeLog.e("Falha em categorias/contas", e)
            _message.value = "Não foi possível concluir."
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ManageViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}
