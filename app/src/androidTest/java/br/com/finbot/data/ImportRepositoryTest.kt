package br.com.finbot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.finbot.data.repository.CategoryOrigin
import br.com.finbot.data.repository.ImportRepository
import br.com.finbot.domain.InvalidTransactionException
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.importing.CsvImporter
import br.com.finbot.importing.OfxParser
import br.com.finbot.importing.ParsedStatement
import br.com.finbot.importing.TextDecoding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {
    private lateinit var f: TestFinance
    private lateinit var imports: ImportRepository

    @Before
    fun setUp() = runTest {
        f = TestFinance()
        f.seed()
        imports = ImportRepository(f.db, f.clock, f.transactions, f.accounts)
    }

    @After
    fun tearDown() = f.close()

    private fun asset(name: String): String =
        TextDecoding.decode(InstrumentationRegistry.getInstrumentation().context.assets.open("importing/$name").use { it.readBytes() })

    private fun ofx(): ParsedStatement = OfxParser.parse(asset("ofx_sgml_latin1.ofx"))

    private fun itauCsv(): ParsedStatement {
        val text = asset("csv_pontovirgula_1252.csv")
        return CsvImporter.parse(text, CsvImporter.suggestMapping(text)!!)
    }

    private suspend fun importAll(statement: ParsedStatement, file: String = "extrato.ofx") =
        imports.preview(statement, f.zone).let { p -> imports.commit(p, p.candidates.filter { it.importable }.map { it.index }.toSet(), f.zone, file) }

    private fun count(sql: String): Int =
        f.db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun ofxLatin1ComAcentosEntraEmRevisaoSemAfetarSaldo() = runTest {
        val result = importAll(ofx())
        assertEquals(3, result.created)
        val batchId = requireNotNull(result.batchId)

        val items = f.db.transactionDao().listByBatch(batchId)
        assertEquals(listOf("PADARIA SÃO JOSÉ", "SALÁRIO EMPRESA FICTÍCIA", "FARMÁCIA AÇAÍ · Compra cartão débito"), items.map { it.description })
        assertTrue(items.all { it.status == TransactionStatus.PENDING_REVIEW && it.source == TransactionSource.IMPORT_OFX })
        assertEquals("TESTE0001", items[0].externalReference)
        // Meio-dia local do dia do extrato.
        assertEquals(LocalDate.of(2026, 10, 2).atTime(LocalTime.NOON).atZone(f.zone).toInstant(), items[0].occurredAt)
        assertEquals(0L, f.summaries.balanceCents())

        val batch = f.db.importBatchDao().observeAll().first().single()
        assertEquals(3, batch.batch.itemCount)
        assertEquals(3, batch.pendingCount)
        assertEquals("••••66-5", batch.batch.maskedAccount)
        assertEquals(0, count("SELECT COUNT(*) FROM import_batches WHERE masked_account LIKE '%99887766%'"))
    }

    @Test
    fun reimportarNaoDuplicaMesmoDepoisDeConfirmarOuDescartar() = runTest {
        val first = importAll(ofx())
        val ids = f.db.transactionDao().listByBatch(first.batchId!!).map { it.id }
        f.transactions.confirmAll(ids.take(2))
        f.transactions.discard(ids[2])

        val preview = imports.preview(ofx(), f.zone)
        assertEquals(0, preview.newCount)
        assertEquals(3, preview.alreadyImportedCount)
        assertFalse(preview.candidates.any { it.selectedByDefault })

        val again = imports.commit(preview, preview.candidates.map { it.index }.toSet(), f.zone, "extrato.ofx")
        assertNull(again.batchId)
        assertEquals(0, again.created)
        assertEquals(3, count("SELECT COUNT(*) FROM transactions"))
        assertEquals(1, count("SELECT COUNT(*) FROM import_batches"))

        // CSV também: mesmo arquivo duas vezes.
        importAll(itauCsv(), "itau.csv")
        val csvAgain = importAll(itauCsv(), "itau.csv")
        assertEquals(0, csvAgain.created)
        assertEquals(6, count("SELECT COUNT(*) FROM transactions"))
    }

    @Test
    fun csvComValorRealNegativoEmMilhar() = runTest {
        val result = importAll(itauCsv(), "itau.csv")
        assertEquals(3, result.created)
        val aluguel = f.db.transactionDao().listByBatch(result.batchId!!).first { it.description == "Aluguel outubro" }
        assertEquals(TransactionKind.EXPENSE, aluguel.kind)
        assertEquals(123_456L, aluguel.amountCents)
        assertEquals(TransactionSource.IMPORT_CSV, aluguel.source)
        assertEquals(f.categoryId("Moradia", TransactionKind.EXPENSE), aluguel.categoryId)
    }

    @Test
    fun desfazerLoteRemoveSomenteAqueleLote() = runTest {
        val manualId = f.transactions.create(
            TransactionDraft(TransactionKind.EXPENSE, 1_000, "manual", null, null, f.clock.instant()),
            TransactionSource.MANUAL,
        )
        val a = importAll(ofx())
        val b = importAll(itauCsv(), "itau.csv")
        val aIds = f.db.transactionDao().listByBatch(a.batchId!!).map { it.id }
        f.transactions.confirmAll(aIds) // confirmados também saem ao desfazer o lote
        val bIds = f.db.transactionDao().listByBatch(b.batchId!!).map { it.id }
        f.transactions.confirm(bIds.first())

        val removed = imports.undoBatch(a.batchId!!)
        assertEquals(3, removed)
        assertTrue(aIds.all { f.transactions.get(it)!!.deletedAt != null })
        assertTrue(bIds.all { f.transactions.get(it)!!.deletedAt == null })
        assertNull(f.transactions.get(manualId)!!.deletedAt)
        // Saldo: manual (−10,00) + item confirmado do lote B (aluguel −1.234,56).
        assertEquals(-1_000L - 123_456L, f.summaries.balanceCents())

        val rows = f.db.importBatchDao().observeAll().first()
        assertNotNull(rows.first { it.batch.id == a.batchId }.batch.undoneAt)
        assertNull(rows.first { it.batch.id == b.batchId }.batch.undoneAt)
        assertEquals(0, imports.undoBatch(a.batchId!!)) // repetir não faz nada

        // Depois de desfeito, o mesmo arquivo pode ser importado de novo.
        assertEquals(3, imports.preview(ofx(), f.zone).newCount)
        assertEquals(3, importAll(ofx()).created)
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE entity = 'import_batch'") == 1)
    }

    @Test
    fun gravacaoDoLoteEAtomica() = runTest {
        val preview = imports.preview(ofx(), f.zone)
        val expenseCategory = f.categoryId("Mercado", TransactionKind.EXPENSE)
        try {
            // Item 1 é receita: categoria de despesa é inválida e derruba o lote inteiro.
            imports.commit(preview, setOf(0, 1, 2), f.zone, "extrato.ofx", categoryOverrides = mapOf(1 to expenseCategory))
            fail("deveria rejeitar")
        } catch (_: InvalidTransactionException) {
        }
        assertEquals(0, count("SELECT COUNT(*) FROM transactions"))
        assertEquals(0, count("SELECT COUNT(*) FROM import_batches"))
    }

    @Test
    fun possivelDuplicataDeLancamentoManualVemDesmarcada() = runTest {
        f.transactions.create(
            TransactionDraft(TransactionKind.EXPENSE, 2_390, "pão", null, null, LocalDate.of(2026, 10, 3).atTime(9, 0).atZone(f.zone).toInstant()),
            TransactionSource.CHAT,
        )
        val preview = imports.preview(ofx(), f.zone)
        val padaria = preview.candidates[0]
        assertEquals("pão", padaria.possibleDuplicateOf?.description)
        assertTrue(padaria.importable)
        assertFalse(padaria.selectedByDefault)
        assertEquals(1, preview.possibleDuplicateCount)
        // O usuário pode decidir importar mesmo assim.
        val result = imports.commit(preview, setOf(0), f.zone, "extrato.ofx")
        assertEquals(1, result.created)
    }

    @Test
    fun categoriaPeloHistoricoDoUsuarioDepoisPorPalavraChave() = runTest {
        val lazer = f.categoryId("Lazer", TransactionKind.EXPENSE)
        // O usuário já classificou esta padaria como Lazer: o histórico vence a palavra-chave ("padaria" → Mercado).
        f.transactions.create(
            TransactionDraft(TransactionKind.EXPENSE, 500, "Padaria São José", lazer, null, f.clock.instant().minusSeconds(86_400 * 30)),
            TransactionSource.MANUAL,
        )
        val preview = imports.preview(ofx(), f.zone)
        assertEquals(lazer, preview.candidates[0].categoryId)
        assertEquals(CategoryOrigin.HISTORY, preview.candidates[0].categoryOrigin)
        assertEquals(f.categoryId("Salário", TransactionKind.INCOME), preview.candidates[1].categoryId)
        assertEquals(CategoryOrigin.KEYWORD, preview.candidates[1].categoryOrigin)
        assertEquals(f.categoryId("Saúde", TransactionKind.EXPENSE), preview.candidates[2].categoryId)
    }

    @Test
    fun dataFuturaNaoPodeSerImportada() = runTest {
        val csv = "data,descricao,valor\n2026-10-25,Futuro,-10.00\n2026-10-17,Hoje,-5.00\n"
        val st = CsvImporter.parse(csv, CsvImporter.suggestMapping(csv)!!)
        val preview = imports.preview(st, f.zone)
        assertEquals("data no futuro", preview.candidates[0].invalidReason)
        assertNull(preview.candidates[1].invalidReason)
        val result = imports.commit(preview, setOf(0, 1), f.zone, "x.csv")
        assertEquals(1, result.created)
        // Item de hoje: meio-dia local, limitado a "agora" (nunca no futuro).
        val hoje = f.db.transactionDao().listByBatch(result.batchId!!).single()
        assertTrue(hoje.occurredAt <= f.clock.instant())
    }

    @Test
    fun modelosDeCsvSalvosPorBanco() = runTest {
        val text = asset("csv_debito_credito.csv")
        val mapping = CsvImporter.suggestMapping(text)!!
        imports.savePreset("Banco Teste", mapping)
        imports.savePreset("banco teste", mapping.copy(skipFooterLines = 2)) // mesmo nome (sem diferenciar caixa) substitui
        val saved = imports.listPresets().single()
        assertEquals("Banco Teste", saved.name)
        assertEquals(mapping.copy(skipFooterLines = 2), ImportRepository.presetToMapping(saved))
        imports.deletePreset(saved.id)
        assertTrue(imports.listPresets().isEmpty())
    }
}
