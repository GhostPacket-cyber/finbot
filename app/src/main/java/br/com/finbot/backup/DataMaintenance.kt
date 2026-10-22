package br.com.finbot.backup

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import br.com.finbot.AppContainer
import br.com.finbot.config.ConfigRepository
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.repository.AccountRepository
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.security.AppLock
import br.com.finbot.security.SecretStore
import br.com.finbot.notifications.BankNotificationListener
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

/** "Apagar tudo" e limpeza de temporários (ETAPA 11). */
object DataMaintenance {
    /** Temporários mais velhos que isso são apagados ao abrir o app (áudio, cópias de backup). */
    val TEMP_MAX_AGE: Duration = Duration.ofHours(1)

    private val TEMP_DIRS = listOf("audio", "backup")

    /** Apaga temporários esquecidos (ex.: o app foi encerrado no meio de uma gravação ou exportação). */
    fun cleanTemporaries(context: Context, now: Instant, maxAge: Duration = TEMP_MAX_AGE): Int {
        var removed = 0
        TEMP_DIRS.map { File(context.cacheDir, it) }.filter { it.isDirectory }.forEach { dir ->
            dir.listFiles().orEmpty().filter { Duration.ofMillis(now.toEpochMilli() - it.lastModified()) >= maxAge }.forEach {
                if (it.deleteRecursively()) removed++
            }
        }
        val importDb = context.getDatabasePath(BackupService.IMPORT_DB_NAME)
        listOf("", "-wal", "-shm", "-journal").map { File(importDb.path + it) }.filter { it.exists() }.forEach { if (it.delete()) removed++ }
        return removed
    }

    /**
     * Apaga tudo deste aparelho: banco (lançamentos, contas, chat…), preferências, segredos e a chave do Keystore,
     * notificações e temporários. Recria só as categorias padrão e a conta "Carteira"; o app volta ao primeiro uso.
     */
    suspend fun wipeAll(context: Context, container: AppContainer) = wipeAll(
        context = context,
        database = container.database,
        config = container.configRepository,
        secrets = container.secretStore,
        categories = container.categoryRepository,
        accounts = container.accountRepository,
        appLock = container.appLock,
        systemEffects = true,
    )

    /** [systemEffects] false só em testes: não mexe no serviço de notificações nem nas notificações do app real. */
    suspend fun wipeAll(
        context: Context,
        database: FinBotDatabase,
        config: ConfigRepository,
        secrets: SecretStore,
        categories: CategoryRepository,
        accounts: AccountRepository,
        appLock: AppLock,
        systemEffects: Boolean,
    ) {
        withContext(Dispatchers.IO) { database.clearAllTables() }
        config.clearAll()
        secrets.clearAll()
        if (systemEffects) {
            BankNotificationListener.setComponentEnabled(context, false)
            NotificationManagerCompat.from(context).cancelAll()
            TEMP_DIRS.forEach { File(context.cacheDir, it).deleteRecursively() }
            cleanTemporaries(context, Instant.now(), Duration.ZERO)
        }
        categories.ensureDefaults()
        accounts.ensureDefault()
        appLock.markUnlocked()
        SafeLog.i("Dados apagados a pedido do usuário")
    }
}
