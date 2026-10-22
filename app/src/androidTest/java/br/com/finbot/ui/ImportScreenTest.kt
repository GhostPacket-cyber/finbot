package br.com.finbot.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import br.com.finbot.importing.CsvImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tela de importação no app real. O seletor de arquivos do sistema não é automatizável aqui:
 * o lote é criado pelo repositório com um CSV sintético e a tela é usada para conferir e desfazer.
 * Remove do banco do app tudo o que o teste criou.
 */
@RunWith(AndroidJUnit4::class)
class ImportScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<FinBotApp>().container
    private var batchId: Long? = null

    @After
    fun cleanUp() = runBlocking {
        val id = batchId ?: return@runBlocking
        val db = container.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM audit_log WHERE entity_uuid IN (SELECT uuid FROM transactions WHERE import_batch_id = ?)", arrayOf(id))
        db.execSQL("DELETE FROM transactions WHERE import_batch_id = ?", arrayOf(id))
        db.execSQL("DELETE FROM audit_log WHERE entity_uuid IN (SELECT uuid FROM import_batches WHERE id = ?)", arrayOf(id))
        db.execSQL("DELETE FROM import_batches WHERE id = ?", arrayOf(id))
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun openImport() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Lançamentos") }
        rule.onNode(hasText("Lançamentos") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { exists("Importar") }
        rule.onNode(hasText("Importar") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { exists("Escolher arquivo OFX ou CSV") }
    }

    @Test
    fun abrirImportacaoEVoltar() {
        openImport()
        assertTrue(exists("Importações anteriores"))
        rule.onNodeWithText("← Voltar").performClick()
        rule.waitUntil(5_000) { exists("+ Novo lançamento") }
    }

    @Test
    fun loteAparecenoHistoricoEPodeSerDesfeito() {
        val csv = "data;descricao;valor\n01/10/2026;Teste de importação UI;-7,77\n"
        runBlocking {
            val statement = CsvImporter.parse(csv, CsvImporter.suggestMapping(csv)!!)
            val zone = container.userZone()
            val preview = container.importRepository.preview(statement, zone)
            batchId = container.importRepository.commit(preview, setOf(0), zone, "ui-test.csv").batchId
        }
        openImport()
        rule.waitUntil(5_000) { exists("1 em revisão", substring = true) }
        rule.onAllNodes(hasText("Desfazer lote") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(5_000) { exists("Desfazer esta importação?") }
        rule.onNodeWithText("Sim, desfazer").performClick()
        rule.waitUntil(5_000) { exists("Lote desfeito: 1 lançamento(s) removido(s).") }
        rule.waitUntil(5_000) { exists("Desfeito em", substring = true) }
    }
}
