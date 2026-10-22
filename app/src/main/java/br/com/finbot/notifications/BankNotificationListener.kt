package br.com.finbot.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import br.com.finbot.R
import br.com.finbot.domain.Money
import br.com.finbot.security.SafeLog
import br.com.finbot.ui.MainTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Recebe notificações do sistema **somente** se o usuário ligou o recurso no FinBot (o componente nasce
 * desativado no manifesto) e concedeu o acesso nas configurações do Android.
 * Notificações de apps fora da lista escolhida são descartadas antes de qualquer leitura do conteúdo.
 */
class BankNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val packageName = sbn.packageName
        val postedAt = Instant.ofEpochMilli(sbn.postTime)
        val container = (application as FinBotApp).container
        scope.launch {
            try {
                // O conteúdo só é lido dentro do ingest, depois do portão.
                container.bankNotificationIngestor.ingest(packageName, postedAt) { texts(notification) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao processar notificação de banco", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun texts(n: Notification): List<String> {
        val extras = n.extras ?: return emptyList()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        return listOfNotNull(title, body) + lines
    }

    companion object {
        fun component(context: Context) = ComponentName(context, BankNotificationListener::class.java)

        /** Liga ou desliga o serviço no sistema. Desligado, o Android não entrega nenhuma notificação ao app. */
        fun setComponentEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        fun isComponentEnabled(context: Context): Boolean =
            context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        /** O usuário concedeu "Acesso a notificações" ao FinBot nas configurações do Android. */
        fun hasAccess(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Tela do sistema para conceder ou revogar o acesso. */
        fun accessSettingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }

        /** Fabricantes (ex.: HyperOS) às vezes desligam o serviço; pede ao sistema para religar. */
        fun requestRebindIfNeeded(context: Context) {
            if (isComponentEnabled(context) && hasAccess(context)) {
                runCatching { requestRebind(component(context)) }
            }
        }
    }
}

/** Notificação discreta: na tela bloqueada aparece só "Nova sugestão de lançamento". */
class AndroidSuggestionNotifier(private val context: Context) : SuggestionNotifier {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sugestões de notificações de banco", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Avisa quando uma notificação de banco vira sugestão para revisar."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun notifySuggestion(transactionId: Long, event: BankNotificationEvent) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, MainTab.TRANSACTIONS.name)
        val pending = PendingIntent.getActivity(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "Nova sugestão: ${Money.format(event.amountCents)} em ${event.counterpart.take(40)} — Revisar"
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_bills)
            .setContentTitle("FinBot")
            .setContentText("Nova sugestão de lançamento")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_bills)
            .setContentTitle("FinBot")
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_TAG, transactionId.toInt(), notification)
        } catch (e: SecurityException) {
            SafeLog.w("Notificação de sugestão recusada pelo sistema", e)
        }
    }

    companion object {
        const val CHANNEL_ID = "sugestoes_banco"
        const val NOTIFICATION_TAG = "sugestao"
        private const val REQUEST_CODE = 9_001
    }
}
