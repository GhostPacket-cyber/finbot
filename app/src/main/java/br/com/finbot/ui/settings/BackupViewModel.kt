package br.com.finbot.ui.settings

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.FinBotApp
import br.com.finbot.backup.BackupAuthException
import br.com.finbot.backup.BackupCrypto
import br.com.finbot.backup.BackupFormatException
import br.com.finbot.backup.BackupIntegrityException
import br.com.finbot.backup.BackupManifest
import br.com.finbot.backup.BackupService
import br.com.finbot.backup.DataMaintenance
import br.com.finbot.backup.MergeReport
import br.com.finbot.backup.OpenedBackup
import br.com.finbot.config.SecuritySettings
import br.com.finbot.security.SafeLog
import br.com.finbot.ui.importing.ImportViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Etapa da importação de backup. */
sealed interface RestoreStep {
    data object Idle : RestoreStep
    data class NeedPassword(val uri: Uri) : RestoreStep
    data class Ready(val backup: OpenedBackup, val manifest: BackupManifest, val preview: MergeReport) : RestoreStep
}

class BackupViewModel(private val app: Application) : ViewModel() {
    private val container = (app as FinBotApp).container
    private val service = container.backupService

    val security: StateFlow<SecuritySettings?> = container.configRepository.config.map { it.security }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _restore = MutableStateFlow<RestoreStep>(RestoreStep.Idle)
    val restore: StateFlow<RestoreStep> = _restore.asStateFlow()

    /** Senha guardada só entre o diálogo e a escolha do arquivo de destino; zerada depois. */
    private var pendingExportPassword: CharArray? = null

    fun setSecurity(block: (SecuritySettings) -> SecuritySettings) {
        viewModelScope.launch { container.configRepository.setSecurity(block) }
    }

    fun lockEnabled(enabled: Boolean) {
        if (enabled) container.appLock.markUnlocked()
        setSecurity { it.copy(appLock = enabled) }
    }

    /** Valida a senha antes de abrir o seletor de destino. Retorna a mensagem de erro, ou null se ok. */
    fun prepareExport(password: String, confirm: String): String? {
        if (password.length < BackupCrypto.MIN_PASSWORD) return "Use pelo menos ${BackupCrypto.MIN_PASSWORD} caracteres."
        if (password != confirm) return "As senhas não conferem."
        pendingExportPassword = password.toCharArray()
        return null
    }

    fun cancelExport() {
        pendingExportPassword?.fill(' ')
        pendingExportPassword = null
    }

    fun export(uri: Uri, resolver: ContentResolver) {
        val password = pendingExportPassword ?: return
        pendingExportPassword = null
        run("Exportando… (pode levar alguns segundos)") {
            try {
                val (bytes, manifest) = service.export(password)
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Destino indisponível")
                }
                container.configRepository.setSecurity { it.copy(lastExportAt = Instant.ofEpochMilli(manifest.createdAtEpochMs)) }
                val rows = manifest.tables["transactions"]?.rows ?: 0
                _message.value = "Backup exportado ($rows lançamento(s)). Guarde o arquivo e a senha: sem a senha não há como abrir."
            } finally {
                password.fill(' ')
            }
        }
    }

    fun pickedRestoreFile(uri: Uri) {
        _restore.value = RestoreStep.NeedPassword(uri)
    }

    fun openRestore(password: String, resolver: ContentResolver) {
        val step = _restore.value as? RestoreStep.NeedPassword ?: return
        val chars = password.toCharArray()
        run("Conferindo o backup…") {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(step.uri)?.use { ImportViewModel.readLimited(it, BackupService.MAX_FILE_BYTES) } ?: error("Arquivo indisponível")
                } ?: throw BackupFormatException("Arquivo maior que 50 MB.")
                val backup = service.open(chars, bytes)
                _restore.value = RestoreStep.Ready(backup, backup.manifest, service.previewMerge(backup))
            } catch (e: BackupAuthException) {
                _message.value = e.message
            } catch (e: BackupFormatException) {
                _message.value = e.message
                _restore.value = RestoreStep.Idle
            } catch (e: BackupIntegrityException) {
                _message.value = "Backup rejeitado: ${e.message}"
                _restore.value = RestoreStep.Idle
            } finally {
                chars.fill(' ')
            }
        }
    }

    fun merge() {
        val step = _restore.value as? RestoreStep.Ready ?: return
        run("Mesclando…") {
            val report = service.merge(step.backup)
            service.discard(step.backup)
            _restore.value = RestoreStep.Idle
            _message.value = "Mesclado: ${report.totalAdded} item(ns) adicionado(s), ${report.totalIgnored} já existiam."
        }
    }

    fun replace() {
        val step = _restore.value as? RestoreStep.Ready ?: return
        run("Restaurando…") {
            service.replace(step.backup)
            service.discard(step.backup)
            _restore.value = RestoreStep.Idle
            _message.value = "Backup restaurado. Os dados deste aparelho foram substituídos."
        }
    }

    fun cancelRestore() {
        (_restore.value as? RestoreStep.Ready)?.let { service.discard(it.backup) }
        _restore.value = RestoreStep.Idle
    }

    fun wipeAll() {
        run("Apagando…") {
            DataMaintenance.wipeAll(app, container)
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun say(text: String) {
        _message.value = text
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        _busy.value = label
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha em backup/segurança", e)
                _message.value = (e as? BackupIntegrityException)?.message ?: "Não foi possível concluir. Nada foi alterado."
            } finally {
                _busy.value = null
            }
        }
    }

    override fun onCleared() {
        cancelExport()
        (_restore.value as? RestoreStep.Ready)?.let { service.discard(it.backup) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BackupViewModel(this[APPLICATION_KEY] as Application) }
        }
    }
}
