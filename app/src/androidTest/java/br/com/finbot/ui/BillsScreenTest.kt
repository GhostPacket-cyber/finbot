package br.com.finbot.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fluxo real: cadastrar conta que vence hoje, pagar e desfazer. Limpa o que criou no banco do app. */
@RunWith(AndroidJUnit4::class)
class BillsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantNotifications() {
        // Evita o diálogo do sistema no primeiro salvamento (o fluxo de pedido é verificado manualmente).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun cleanUp() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<FinBotApp>().container
        c.billRepository.observeActiveBills().first().filter { it.title == TITLE }.forEach { c.billRepository.deactivate(it.id) }
        c.database.transactionDao().listRecentConfirmed(500, 0)
            .filter { it.transaction.description == TITLE }
            .forEach { c.transactionRepository.softDelete(it.transaction.id) }
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test
    fun cadastrarPagarEDesfazer() {
        rule.waitUntil(5_000) { exists("Bem-vindo ao FinBot") || exists("Ajustes") }
        if (exists("Bem-vindo ao FinBot")) rule.onNodeWithText("Começar").performScrollTo().performClick()
        rule.waitUntil(5_000) { exists("Contas") }
        rule.onNode(hasText("Contas") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { exists("+ Conta") }

        rule.onNodeWithText("+ Conta").performClick()
        rule.waitUntil(5_000) { exists("Nova conta a pagar") }
        field("Título").performTextInput(TITLE)
        field("Valor").performTextInput("50,00")
        // Vencimento já vem preenchido com hoje.
        rule.onNodeWithText("Salvar").performClick()
        rule.waitUntil(5_000) { exists(TITLE) && exists("Vence hoje", substring = true) }

        rule.onAllNodes(hasText("Pagar") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(5_000) { exists("Confirmar pagamento") }
        rule.onNodeWithText("Confirmar pagamento").performClick()
        rule.waitUntil(5_000) { exists("Pagas nos últimos 30 dias") && exists("Desfazer") }

        rule.onAllNodes(hasText("Desfazer") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(5_000) { !exists("Pagas nos últimos 30 dias") && exists("Vence hoje", substring = true) }
    }

    private companion object {
        const val TITLE = "Teste UI conta"
    }
}
