package br.com.finbot.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import br.com.finbot.config.SecuritySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bloqueio do app e tela de backup no app real. Restaura os ajustes de segurança ao final. */
@RunWith(AndroidJUnit4::class)
class SecurityScreensTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<FinBotApp>().container

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun completeOnboardingIfShown() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes") || exists("FinBot bloqueado") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
    }

    @Test
    fun bloqueioLigadoMostraSoATelaDeBloqueio() {
        val before: SecuritySettings = runBlocking { container.configRepository.config.first().security }
        container.appLock.testBypass = false
        runBlocking { container.configRepository.setSecurity { it.copy(appLock = true) } }
        container.appLock.lockNow()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            completeOnboardingIfShown()
            rule.waitUntil(5_000) { exists("FinBot bloqueado") }
            assertTrue(exists("Desbloquear"))
            // Nada com dados financeiros é composto.
            assertFalse(exists("Lançamentos"))
            assertFalse(exists("Saldo"))
        } finally {
            runBlocking { container.configRepository.setSecurity { before } }
            container.appLock.markUnlocked()
            container.appLock.testBypass = true
            scenario.close()
        }
        assertEquals(before, runBlocking { container.configRepository.config.first().security })
    }

    @Test
    fun telaDeBackupRecusaSenhaCurtaENaoExporta() {
        val before = runBlocking { container.configRepository.config.first().security }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            completeOnboardingIfShown()
            rule.waitUntil(5_000) { exists("Ajustes") }
            rule.onNode(hasText("Ajustes") and hasClickAction()).performClick()
            rule.onNodeWithText("Abrir backup e segurança").performScrollTo().performClick()
            rule.waitUntil(5_000) { exists("Exportar backup") }
            assertTrue(exists("apaga tudo o que não foi exportado", substring = true))
            assertTrue(exists("Apagar todos os dados"))
            rule.onNodeWithText("Exportar backup").performScrollTo().performClick()
            rule.waitUntil(5_000) { exists("Senha do backup") }
            rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("curta")
            rule.onNodeWithText("Escolher onde salvar").performClick()
            rule.waitUntil(5_000) { exists("Use pelo menos 10 caracteres.") }
            rule.onNodeWithText("Cancelar").performClick()
        } finally {
            scenario.close()
        }
        // Nenhuma exportação registrada.
        assertEquals(before.lastExportAt, runBlocking { container.configRepository.config.first().security.lastExportAt })
    }
}
