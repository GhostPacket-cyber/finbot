package br.com.finbot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import br.com.finbot.R

object Notifications {
    const val CHANNEL_STATUS = "bot_status"

    fun createChannels(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.notification_channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_status_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
