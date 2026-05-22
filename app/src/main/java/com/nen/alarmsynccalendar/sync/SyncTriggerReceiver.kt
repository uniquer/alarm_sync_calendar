package com.nen.alarmsynccalendar.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
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
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            999,
            nextIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val nextFallbackTime = System.currentTimeMillis() + (120 * 60 * 1000L)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                nextFallbackTime,
                pendingIntent
            )
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        log(context, "Fallback rescheduled. Next: ~${sdf.format(Date(nextFallbackTime))}")
    }

    private fun log(context: Context, message: String) {
        Log.d("SyncTriggerReceiver", message)
        val prefs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val existing = prefs.getString("history", "") ?: ""
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        prefs.edit().putString("history", "[$ts] [Receiver] $message\n$existing".take(5000)).apply()
    }
}
