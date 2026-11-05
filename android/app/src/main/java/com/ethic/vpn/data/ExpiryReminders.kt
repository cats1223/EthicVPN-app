package com.ethic.vpn.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ethic.vpn.R
import com.ethic.vpn.ui.MainActivity

/** Local reminders that the subscription is running out: three days before and the day before. */
object ExpiryReminders {
    private const val CHANNEL = "subscription"
    private const val EXTRA_DAYS = "days"
    private val DAYS_BEFORE = listOf(3, 1)

    /** [expire] is a Unix time; zero means the subscription does not expire. */
    fun schedule(context: Context, expire: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (days in DAYS_BEFORE) {
            val pending = pendingIntent(context, days)
            alarms.cancel(pending)
            val at = expire * 1000 - days * 86_400_000L
            // Inexact is enough for a reminder, and needs no exact-alarm permission.
            if (expire > 0 && at > System.currentTimeMillis()) alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun pendingIntent(context: Context, days: Int): PendingIntent = PendingIntent.getBroadcast(
        context, days,
        Intent(context, Receiver::class.java).putExtra(EXTRA_DAYS, days),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val days = intent.getIntExtra(EXTRA_DAYS, 1)
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.expiry_channel), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(if (days == 1) R.string.expiry_tomorrow else R.string.expiry_in_3_days))
                .setContentText(context.getString(R.string.expiry_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(100 + days, notification) }
        }
    }
}
