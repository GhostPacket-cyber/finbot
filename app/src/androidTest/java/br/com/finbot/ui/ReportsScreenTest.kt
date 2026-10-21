package br.com.finbot.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun relatorioMostraResumoTabelaENavegacaoDeMes() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Relatórios") }
        rule.onNode(hasText("Relatórios") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { exists("Resumo do mês") }
        rule.onNodeWithText("Contas do mês").assertExists()
        rule.onNodeWithText("Exportar mês").performScrollTo().assertExists()

        // Alternativa em tabela do gráfico de evolução.
        rule.onAllNodes(hasText("Ver tabela")).onFirst().performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Ver gráfico") }

        // Mês anterior e volta; o próximo mês além do atual fica desabilitado.
        rule.onNodeWithContentDescription("Mês anterior").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Resumo do mês") }
        rule.onNodeWithContentDescription("Próximo mês").performClick()
        rule.waitUntil(5_000) { exists("Resumo do mês") }
    }
}
