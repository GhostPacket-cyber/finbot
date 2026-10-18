package br.com.finbot.scheduling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.finbot.FinBotApp
import br.com.finbot.MainActivity
import br.com.finbot.R
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Notificações locais de contas e lembretes. Nada é enviado para fora do aparelho. */
class AndroidNotifier(private val context: Context) : Notifier {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Contas e lembretes", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisos de vencimento de contas e lembretes pessoais."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun show(event: DueEvent): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!permissionGranted() || !manager.areNotificationsEnabled()) return false
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, event.target.name)
        val pending = PendingIntent.getActivity(
            context,
            event.target.ordinal,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_bills)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(event.key.hashCode(), notification)
            true
        } catch (e: SecurityException) {
            SafeLog.w("Notificação recusada pelo sistema", e)
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "contas_lembretes"
    }
}

/** Verificação periódica. O Android pode atrasá-la (Doze, economia de bateria do fabricante). */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as FinBotApp).container.reminderProcessor.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SafeLog.e("Falha na verificação de avisos", e)
        Result.retry()
    }
}

object ReminderScheduler {
    const val UNIQUE_NAME = "finbot-avisos"
    const val INTERVAL_MINUTES = 30L

    /** Idempotente (KEEP): chamar a cada abertura do app não duplica o agendamento. Sobrevive a reinícios. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
