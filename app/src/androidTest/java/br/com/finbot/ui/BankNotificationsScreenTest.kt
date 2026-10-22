package br.com.finbot.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tela de notificações de banco no app real. Não liga o recurso (o estado do aparelho fica como está). */
@RunWith(AndroidJUnit4::class)
class BankNotificationsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun explicaETestaTextoSemGravar() {
        val container = ApplicationProvider.getApplicationContext<FinBotApp>().container
        val before = runBlocking { container.transactionRepository.listPendingReview().size }

        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Ajustes") }
        rule.onNode(hasText("Ajustes") and hasClickAction()).performClick()
        rule.onNodeWithText("Configurar notificações de banco").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Ler notificações de banco") }
        assertEquals(true, exists("Nada é enviado para fora do aparelho", substring = true))
        assertEquals(true, exists("Para revogar", substring = true))

        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Você enviou um Pix de R$ 50,00 para Maria Exemplo.")
        rule.onNodeWithText("Ver o que seria extraído").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Pix enviado · R$ 50,00 · Pix para Maria Exemplo", substring = true) }

        // O teste de texto não grava nada.
        assertEquals(before, runBlocking { container.transactionRepository.listPendingReview().size })
        // Abrir a tela não liga o recurso.
        val cfg = runBlocking { container.configRepository.config.first() }
        rule.onNodeWithText("← Voltar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Configurar notificações de banco") }
        assertEquals(cfg.bankNotificationsEnabled, runBlocking { container.configRepository.config.first().bankNotificationsEnabled })
    }
}
