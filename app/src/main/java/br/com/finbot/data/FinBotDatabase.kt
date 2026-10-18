package br.com.finbot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import br.com.finbot.data.dao.AccountDao
import br.com.finbot.data.dao.AuditLogDao
import br.com.finbot.data.dao.BillDao
import br.com.finbot.data.dao.BudgetDao
import br.com.finbot.data.dao.CategoryDao
import br.com.finbot.data.dao.ChatMessageDao
import br.com.finbot.data.dao.ImportBatchDao
import br.com.finbot.data.dao.NotificationLogDao
import br.com.finbot.data.dao.PendingActionDao
import br.com.finbot.data.dao.ReminderDao
import br.com.finbot.data.dao.TransactionDao
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.AuditLogEntity
import br.com.finbot.data.entity.BillEntity
import br.com.finbot.data.entity.BillOccurrenceEntity
import br.com.finbot.data.entity.BudgetEntity
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.data.entity.ChatMessageEntity
import br.com.finbot.data.entity.ImportBatchEntity
import br.com.finbot.data.entity.NotificationLogEntity
import br.com.finbot.data.entity.PendingActionEntity
import br.com.finbot.data.entity.ReminderEntity
import br.com.finbot.data.entity.TransactionEntity

@Database(
    version = 2,
    exportSchema = true,
    entities = [
        CategoryEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        BillEntity::class,
        BillOccurrenceEntity::class,
        ReminderEntity::class,
        NotificationLogEntity::class,
        PendingActionEntity::class,
        BudgetEntity::class,
        ImportBatchEntity::class,
        AuditLogEntity::class,
        ChatMessageEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class FinBotDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun billDao(): BillDao
    abstract fun reminderDao(): ReminderDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        const val FILE_NAME = "finbot.db"

        /** Migrações explícitas; nunca usar fallbackToDestructiveMigration (apagaria dados financeiros). */
        val MIGRATIONS = arrayOf(MIGRATION_1_2)

        fun build(context: Context): FinBotDatabase =
            Room.databaseBuilder(context, FinBotDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
