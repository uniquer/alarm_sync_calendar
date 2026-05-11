package com.nen.alarmsynccalendar.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy

class SyncTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Trigger a one-time sync whenever the phone is unlocked (ACTION_USER_PRESENT)
        // or when our custom AlarmManager trigger fires.
        val req = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork("ImmediateSync", ExistingWorkPolicy.REPLACE, req)
        
        // Re-schedule the fallback alarm if this was a timed wakeup
        if (intent.action != Intent.ACTION_USER_PRESENT) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val nextIntent = Intent(context, SyncTriggerReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(context, 999, nextIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + (2 * 60 * 60 * 1000L), pendingIntent)
            }
        }
    }
}
