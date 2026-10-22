package br.com.finbot.screenshots

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Gera as capturas do README rodando o app de verdade na JVM (Robolectric + gráficos nativos), com [DemoData].
 * Só roda quando pedido:
 * `FINBOT_SCREENSHOTS=1 ./gradlew testDebugUnitTest --tests br.com.finbot.screenshots.ScreenshotsTest`
 * As imagens vão para `docs/images/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
class ScreenshotsTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val out = File("../docs/images").apply { mkdirs() }

    @Before
    fun setUp() {
        assumeTrue("defina FINBOT_SCREENSHOTS=1", System.getenv("FINBOT_SCREENSHOTS") == "1")
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "$name.png"))
    }

    private fun tab(label: String) {
        rule.onNode(hasText(label) and hasClickAction()).performClick()
        rule.waitForIdle()
    }

    @Test
    fun capturasDoReadme() {
        val container = ApplicationProvider.getApplicationContext<FinBotApp>().container
        runBlocking { DemoData.seed(container) }
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntil(10_000) { exists("Saldo") }
            shot("chat")

            tab("Lançamentos")
            rule.waitUntil(10_000) { exists("+ Novo lançamento") }
            shot("lancamentos")

            rule.onNode(hasText("para revisar", substring = true) and hasClickAction()).performClick()
            rule.waitUntil(10_000) { exists("Revisar lançamentos") }
            shot("revisao")
            rule.onNode(hasText("← Voltar") and hasClickAction()).performClick()

            rule.waitUntil(10_000) { exists("Categorias") }
            rule.onNode(hasText("Categorias") and hasClickAction()).performClick()
            rule.waitUntil(10_000) { exists("Categorias de despesa") }
            shot("categorias")
            rule.onNode(hasText("← Voltar") and hasClickAction()).performClick()

            tab("Contas")
            rule.waitUntil(10_000) { exists("Fatura do cartão", substring = true) }
            shot("contas")

            tab("Relatórios")
            rule.waitUntil(10_000) { exists("Resumo do mês") }
            shot("relatorios")

            tab("Ajustes")
            rule.onNode(hasText("Configurar alertas") and hasClickAction()).performScrollTo().performClick()
            rule.waitUntil(10_000) { exists("Orçamento por categoria") }
            shot("alertas")
            rule.onNode(hasText("← Voltar") and hasClickAction()).performClick()

            rule.onNode(hasText("Abrir backup e segurança") and hasClickAction()).performScrollTo().performClick()
            rule.waitUntil(10_000) { exists("Exportar backup") }
            shot("backup")
        }
    }
}
