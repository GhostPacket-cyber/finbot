package br.com.finbot.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** O estado do onboarding persiste entre testes; conclui se ainda estiver na tela inicial. */
    private fun completeOnboardingIfShown() {
        rule.waitUntil(5_000) {
            exists("Bem-vindo ao FinBot") || exists("Ajustes")
        }
        if (exists("Bem-vindo ao FinBot")) {
            rule.onNodeWithText("Começar").performScrollTo().performClick()
        }
        rule.waitUntil(5_000) { exists("Lançamentos") }
    }

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun abasMostramPlaceholdersSemDados() {
        completeOnboardingIfShown()
        rule.onNodeWithText("Em breve (ETAPA 03)").assertIsDisplayed()
        rule.onNodeWithText("Lançamentos").performClick()
        rule.onNodeWithText("Em breve (ETAPA 05)").assertIsDisplayed()
        rule.onNodeWithText("Contas").performClick()
        rule.onNodeWithText("Em breve (ETAPA 06)").assertIsDisplayed()
        rule.onNodeWithText("Relatórios").performClick()
        rule.onNodeWithText("Em breve (ETAPA 07)").assertIsDisplayed()
    }

    @Test
    fun ajustesMostraSecoes() {
        completeOnboardingIfShown()
        rule.onAllNodes(hasText("Ajustes"))[0].performClick()
        rule.onNodeWithText("Perfil").assertIsDisplayed()
        rule.onNodeWithText("Assistente com IA (Gemini)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Diagnóstico do aparelho").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Sobre").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fusoInvalidoEmAjustesMostraErro() {
        completeOnboardingIfShown()
        rule.onAllNodes(hasText("Ajustes"))[0].performClick()
        rule.onNodeWithText("Fuso horário (Área/Cidade)").performTextReplacement("Marte/Olympus")
        rule.onNodeWithText("Salvar ajustes").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Fuso horário desconhecido.") }
        // Restaura um valor válido para não afetar outros testes.
        rule.onNodeWithText("Usar fuso do aparelho").performScrollTo().performClick()
    }
}
