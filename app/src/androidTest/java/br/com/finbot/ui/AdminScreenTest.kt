package br.com.finbot.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun telaMostraSecoesPrincipais() {
        rule.onNodeWithText("Status do bot").assertIsDisplayed()
        rule.onNodeWithText("Credenciais").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Usuários autorizados e fuso").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Diagnóstico do aparelho").performScrollTo().assertIsDisplayed()
    }

    /** Pressupõe instalação limpa (nenhum ID salvo). */
    @Test
    fun salvarSemIdMostraErro() {
        rule.onNodeWithText("Salvar configuração").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("Corrija os campos destacados.")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
