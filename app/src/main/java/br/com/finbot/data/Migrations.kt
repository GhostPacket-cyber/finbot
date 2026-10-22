package br.com.finbot.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Histórico de schemas (arquivos em app/schemas/):
 * - v1: schema inicial da ETAPA 02.
 * - v2: `transactions.note` (observação opcional).
 * - v3: `chat_messages.quick_reply` e `quick_reply_label` (botão de resposta rápida, ex.: "Ver mais").
 * - v4: `transactions.import_batch_id`, colunas de controle em `import_batches` e tabela `csv_presets` (ETAPA 08).
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN import_batch_id INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_import_batch_id ON transactions(import_batch_id)")
        db.execSQL("ALTER TABLE import_batches ADD COLUMN account_id INTEGER")
        db.execSQL("ALTER TABLE import_batches ADD COLUMN masked_account TEXT")
        db.execSQL("ALTER TABLE import_batches ADD COLUMN skipped_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE import_batches ADD COLUMN undone_at INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS csv_presets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                "separator TEXT NOT NULL, skip_lines INTEGER NOT NULL, skip_footer_lines INTEGER NOT NULL, " +
                "date_column INTEGER NOT NULL, description_column INTEGER NOT NULL, amount_column INTEGER, " +
                "debit_column INTEGER, credit_column INTEGER, date_format TEXT NOT NULL, invert_sign INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_csv_presets_name ON csv_presets(name)")
    }
}
