package br.com.finbot.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import br.com.finbot.MainActivity
import br.com.finbot.R
import br.com.finbot.security.SafeLog
import br.com.finbot.ui.MainTab

/** Notificação local de alerta. Na tela bloqueada mostra só "Novo alerta do FinBot". Toque abre o chat. */
class AndroidAlertNotifier(private val context: Context) : AlertNotifier {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Alertas", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Orçamento, cobranças duplicadas, assinaturas, gastos fora do padrão e resumo semanal."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun show(alert: Alert, text: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, MainTab.CHAT.name)
        val pending = PendingIntent.getActivity(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_bills)
            .setContentTitle("FinBot")
            .setContentText("Novo alerta do FinBot")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_bills)
            .setContentTitle(alert.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(TAG, alert.key.hashCode(), notification)
            true
        } catch (e: SecurityException) {
            SafeLog.w("Notificação de alerta recusada pelo sistema", e)
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "alertas"
        const val TAG = "alerta"
        private const val REQUEST_CODE = 9_002
    }
}
