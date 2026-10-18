package br.com.finbot.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Histórico de schemas (arquivos em app/schemas/):
 * - v1: schema inicial da ETAPA 02.
 * - v2: `transactions.note` (observação opcional).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
    }
}
