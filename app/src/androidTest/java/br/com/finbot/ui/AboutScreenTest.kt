package br.com.finbot.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.BuildConfig
import br.com.finbot.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** ETAPA 13 — tela Sobre: versão, privacidade, avisos obrigatórios e licenças. */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String, substring: Boolean = true) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun sobreMostraVersaoPrivacidadeAvisosELicencas() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes", substring = false) }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Ajustes", substring = false) }
        rule.onNode(hasText("Ajustes") and hasClickAction()).performClick()
        rule.onNodeWithText("Privacidade, avisos e licenças").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Sobre o FinBot") }
        listOf(
            "Versão ${BuildConfig.VERSION_NAME}",
            "Sem Open Finance",
            "cota",
            "sai do aparelho para o Google",
            "restrições de bateria",
            "Licença Apache 2.0",
            "AndroidX Room",
        ).forEach { assertTrue("falta \"$it\"", exists(it)) }
        rule.onNodeWithText("← Voltar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Privacidade, avisos e licenças") }
    }
}
