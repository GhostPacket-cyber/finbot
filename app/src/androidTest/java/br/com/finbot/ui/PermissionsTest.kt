package br.com.finbot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.MainActivity
import br.com.finbot.notifications.BankNotificationListener
import br.com.finbot.scheduling.AndroidNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ETAPA 12 — permissões (microfone, notificações, acesso a notificações). Só leitura: o teste não concede nem
 * revoga nada no aparelho (revogar mata o processo do app). Recusas são testadas à mão (TEST_PLAN).
 */
@RunWith(AndroidJUnit4::class)
class PermissionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun manifestoPedeSoOQueUsa() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        listOf(Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .forEach { assertTrue("falta $it em $requested", it in requested) }
        val forbidden = setOf(
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.QUERY_ALL_PACKAGES, Manifest.permission.SCHEDULE_EXACT_ALARM, Manifest.permission.USE_EXACT_ALARM,
            "android.permission.READ_MEDIA_IMAGES", "android.permission.MANAGE_EXTERNAL_STORAGE",
        )
        assertEquals("permissões indevidas", emptySet<String>(), requested intersect forbidden)
    }

    @Test
    fun estadosDasPermissoesBatemComOSistema() {
        val notificationsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        assertEquals(notificationsGranted, AndroidNotifier(context).permissionGranted())
        val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        assertEquals(context.packageName in listeners, BankNotificationListener.hasAccess(context))
    }

    @Test
    fun microfoneENotificacoesDeBancoTemEntradaNaInterface() {
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Bem-vindo ao FinBot") or hasText("Ajustes")).fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodes(hasText("Bem-vindo ao FinBot")).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Começar").performScrollTo().performClick()
        }
        // Botão de voz com rótulo acessível (pede RECORD_AUDIO só no primeiro toque).
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Gravar mensagem de voz")).fetchSemanticsNodes().isNotEmpty() }
        // A tela de notificações de banco mostra o estado real do acesso quando ligada.
        assertTrue(rule.onAllNodes(hasText("Ajustes")).fetchSemanticsNodes().isNotEmpty())
    }
}
