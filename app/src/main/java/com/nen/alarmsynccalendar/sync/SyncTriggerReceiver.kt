package com.nen.alarmsynccalendar.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import com.nen.alarmsynccalendar.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val triggerType = if (action == Intent.ACTION_USER_PRESENT) {
            "User Awake"
        } else {
            "Fallback"
        }
        log(context, "$triggerType enqueued")

        // Trigger a one-time sync
        val data = androidx.work.Data.Builder()
            .putString("trigger", triggerType)
            .build()
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ImmediateSync", ExistingWorkPolicy.REPLACE, req)
        
        // Unconditionally reschedule the fallback alarm (2 hours from now)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val nextIntent = Intent(context, SyncTriggerReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            999,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextFallbackTime = System.currentTimeMillis() + (120 * 60 * 1000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                nextFallbackTime,
                pendingIntent
            )
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        log(context, "Fallback rescheduled. Next: ~${sdf.format(Date(nextFallbackTime))}")

        // Check if background sync is restricted (not run within 200 minutes) and alert the user
        val syncPrefs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val lastExecution = syncPrefs.getLong("last_execution_time", 0L)
        val firstRun = syncPrefs.getLong("first_run_time", 0L)
        val now = System.currentTimeMillis()
        val threshold = 200 * 60 * 1000L
        val snoozeUntil = syncPrefs.getLong("snooze_until", 0L)

        val alarmsPrefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val accountsJson = alarmsPrefs.getString("google_accounts_v3", "[]") ?: "[]"
        val hasAccounts = accountsJson != "[]" && accountsJson.trim().length > 4

        if (hasAccounts && now >= snoozeUntil) {
            val isRestricted = if (lastExecution == 0L) {
                firstRun > 0L && (now - firstRun > threshold)
            } else {
                now - lastExecution > threshold
            }
            if (isRestricted) {
                showRestrictedNotification(context)
            }
        }
    }

    private fun showRestrictedNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sync_channel",
                "Sync & Permission Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            202,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "sync_channel")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Background Sync Restricted")
            .setContentText("Background sync hasn't run recently. Tap to check app permissions.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Background sync has not run recently. On some devices, you must enable Auto-start and set Battery to 'Unrestricted' in App Info to allow syncing in the background."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(202, notification)
    }

    private fun log(context: Context, message: String) {
        Log.d("SyncTriggerReceiver", message)
        val prefs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val existing = prefs.getString("history", "") ?: ""
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        prefs.edit().putString("history", "[$ts] [Receiver] $message\n$existing".take(5000)).apply()
    }
}
