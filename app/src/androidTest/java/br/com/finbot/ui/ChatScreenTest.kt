package br.com.finbot.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fluxo ponta a ponta na tela real: digitar, ver o cartão, confirmar uma vez. Usa o banco do app no aparelho. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearChat() = runBlocking {
        ApplicationProvider.getApplicationContext<FinBotApp>().container.chatAssistant.clearHistory()
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun openChat() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Lançamentos") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Mensagem") }
    }

    private fun sendMessage(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.onNodeWithContentDescription("Enviar").performClick()
    }

    @Test
    fun digitarConfirmarERegistrarUmaUnicaVez() {
        openChat()
        sendMessage("gastei 12,34 no teste de interface")

        rule.waitUntil(5_000) { exists("Nova despesa") }
        rule.onNodeWithText("R$ 12,34").assertExists()
        rule.onNodeWithText("Teste de interface").assertExists()

        rule.onNodeWithText("Confirmar").performClick()
        rule.waitUntil(5_000) { exists("✓ Confirmado") }
        rule.waitUntil(5_000) { exists("Despesa registrada: R$ 12,34", substring = true) }
        // O cartão confirmado não oferece mais o botão.
        rule.onAllNodes(hasText("Confirmar")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun chipDeSaldoResponde() {
        openChat()
        rule.onNodeWithText("Saldo").performClick()
        rule.waitUntil(5_000) { exists("Saldo calculado", substring = true) }
    }

    @Test
    fun frasesSemValorPedemEsclarecimento() {
        openChat()
        sendMessage("gastei no mercado")
        rule.waitUntil(5_000) { exists("Faltou o valor", substring = true) }
        rule.onAllNodes(hasText("Nova despesa")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }
}
