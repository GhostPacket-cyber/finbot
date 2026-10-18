package br.com.finbot.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fluxo real na tela de Lançamentos: criar, editar, excluir e desfazer. Limpa o que criou no banco do app. */
@RunWith(AndroidJUnit4::class)
class TransactionsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun cleanUp() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<FinBotApp>().container
        c.database.transactionDao().listRecentConfirmed(500, 0)
            .filter { it.transaction.description == DESCRIPTION }
            .forEach { c.transactionRepository.softDelete(it.transaction.id) }
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun openTab() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Lançamentos") }
        rule.onNode(hasText("Lançamentos") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { exists("+ Novo lançamento") }
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test
    fun criarEditarExcluirEDesfazer() {
        openTab()

        // Criar
        rule.onNodeWithText("+ Novo lançamento").performClick()
        rule.waitUntil(5_000) { exists("Valor (R$)") }
        field("Valor (R$)").performTextInput("23,45")
        field("Descrição").performTextInput(DESCRIPTION)
        rule.onNodeWithText("Salvar").performClick()
        rule.waitUntil(5_000) { exists(DESCRIPTION) && exists("−R$ 23,45") }

        // Editar o valor
        rule.onNodeWithText(DESCRIPTION).performClick()
        rule.waitUntil(5_000) { exists("Editar lançamento") }
        field("Valor (R$)").performTextReplacement("30,00")
        rule.onNodeWithText("Salvar").performClick()
        rule.waitUntil(5_000) { exists("−R$ 30,00") && !exists("Editar lançamento") }

        // Excluir (com confirmação) e desfazer pelo snackbar
        rule.onNodeWithText(DESCRIPTION).performClick()
        rule.waitUntil(5_000) { exists("Excluir lançamento") }
        rule.onNodeWithText("Excluir lançamento").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Excluir lançamento?") }
        rule.onNodeWithText("Excluir").performClick()
        rule.waitUntil(5_000) { !exists(DESCRIPTION) && exists("Desfazer") }
        rule.onNodeWithText("Desfazer").performClick()
        rule.waitUntil(5_000) { exists(DESCRIPTION) && exists("−R$ 30,00") }
    }

    @Test
    fun valorInvalidoMostraErroSemSalvar() {
        openTab()
        rule.onNodeWithText("+ Novo lançamento").performClick()
        rule.waitUntil(5_000) { exists("Valor (R$)") }
        field("Valor (R$)").performTextInput("12,345")
        field("Descrição").performTextInput(DESCRIPTION)
        rule.onNodeWithText("Salvar").performClick()
        rule.waitUntil(5_000) { exists("Valor inválido", substring = true) }
        rule.onNodeWithText("Cancelar").performClick()
        rule.waitUntil(5_000) { !exists("Valor (R$)") }
        check(!exists(DESCRIPTION)) { "não deveria ter salvo" }
    }

    @Test
    fun filtroSemResultadoMostraEstadoVazio() {
        openTab()
        rule.onNode(hasSetTextAction() and hasText("Buscar", substring = true)).performTextInput("zzz-nao-existe-zzz")
        rule.waitUntil(5_000) { exists("Nenhum lançamento com esses filtros.") }
        // O do estado vazio (o da barra de chips pode estar fora da área visível).
        rule.onAllNodes(hasText("Limpar filtros")).onLast().performClick()
        rule.waitUntil(5_000) { !exists("Nenhum lançamento com esses filtros.") }
    }

    private companion object {
        const val DESCRIPTION = "Teste UI lançamentos"
    }
}
