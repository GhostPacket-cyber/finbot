package br.com.finbot.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Telas de alertas e de orçamento no app real. Abre e confere sem alterar ajustes do aparelho. */
@RunWith(AndroidJUnit4::class)
class AlertsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun start() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Ajustes") }
    }

    @Test
    fun telaDeAlertasTemChavesRotuladas() {
        val container = ApplicationProvider.getApplicationContext<FinBotApp>().container
        val before = runBlocking { container.configRepository.config.first().alerts }
        start()
        rule.onNode(hasText("Ajustes") and hasClickAction()).performClick()
        rule.onNodeWithText("Configurar alertas").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Orçamento por categoria") }
        listOf("Possível cobrança duplicada", "Nova assinatura recorrente", "Gasto fora do padrão", "Resumo semanal", "Texto mais amigável com IA")
            .forEach { assertTrue(it, exists(it)) }
        // Cada chave é um nó com papel de Switch que contém o próprio rótulo (acessível ao leitor de tela).
        val switches = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).fetchSemanticsNodes()
        assertTrue("chaves: ${switches.size}", switches.size >= 7)
        rule.onNodeWithText("← Voltar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Configurar alertas") }
        assertEquals(before, runBlocking { container.configRepository.config.first().alerts })
    }

    @Test
    fun orcamentoNoMenuDaCategoriaDeDespesa() {
        start()
        rule.onNode(hasText("Lançamentos") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { exists("Categorias") }
        rule.onNode(hasText("Categorias") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { exists("Categorias de despesa") }
        rule.onAllNodes(hasText("⋮") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(5_000) { exists("Orçamento mensal…") }
        rule.onNodeWithText("Orçamento mensal…").performClick()
        rule.waitUntil(5_000) { exists("Limite por mês (R$)") }
        rule.onNodeWithText("Cancelar").performClick()
    }
}
