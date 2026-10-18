package br.com.finbot.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Histórico de schemas (arquivos em app/schemas/):
 * - v1: schema inicial da ETAPA 02.
 * - v2: `transactions.note` (observação opcional).
 * - v3: `chat_messages.quick_reply` e `quick_reply_label` (botão de resposta rápida, ex.: "Ver mais").
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN quick_reply TEXT")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN quick_reply_label TEXT")
    }
}
