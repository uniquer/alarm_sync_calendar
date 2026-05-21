package com.nen.alarmsynccalendar.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nen.alarmsynccalendar.RecurrenceType
import com.nen.alarmsynccalendar.RecurrenceUtils
import com.nen.alarmsynccalendar.ScheduledAlarm

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "CalAlarm:WakeLock"
        )
        wakeLock.acquire(5000) // Keep CPU on for 5 seconds

        val message = intent.getStringExtra("ALARM_MESSAGE") ?: "Meeting Alarm!"
        val id = intent.getIntExtra("ALARM_ID", 1)
        
        // Handle Recurrence chaining BEFORE showing notification to ensure persistence
        handleRecurrence(context, id)
        
        showNotification(context, message, id)
    }

    private fun handleRecurrence(context: Context, alarmId: Int) {
        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val gson = Gson()
        val alarmsJson = prefs.getString("alarm_list", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<ScheduledAlarm>>() {}.type
        
        try {
            val alarms: MutableList<ScheduledAlarm> = gson.fromJson(alarmsJson, type)
            val alarmIndex = alarms.indexOfFirst { it.id == alarmId }
            
            if (alarmIndex != -1) {
                val alarm = alarms[alarmIndex]
                
                // If this is a calendar-linked alarm, trigger an immediate background sync to update cache and reschedule recurring series
                if (alarm.googleEventId != null) {
                    val req = androidx.work.OneTimeWorkRequestBuilder<com.nen.alarmsynccalendar.sync.SyncWorker>().build()
                    androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                        "ImmediateSync",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        req
                    )
                }

                if (alarm.recurrenceType != RecurrenceType.NONE) {
                    val nextTime = RecurrenceUtils.calculateNextOccurrence(
                        alarm.time, 
                        alarm.recurrenceType, 
                        alarm.recurrenceData
                    )
                    
                    Log.d("AlarmReceiver", "Chaining recurring alarm ${alarm.id} from ${alarm.time} to $nextTime")
                    
                    // Modify current alarm to be a one-time past event so it stays in history
                    alarms[alarmIndex] = alarm.copy(recurrenceType = RecurrenceType.NONE)
                    
                    // Create and schedule the new future instance
                    val newId = System.currentTimeMillis().toInt()
                    val nextAlarm = alarm.copy(id = newId, time = nextTime)
                    alarms.add(nextAlarm)
                    
                    // Save updated list
                    prefs.edit().putString("alarm_list", gson.toJson(alarms)).apply()
                    
                    // Schedule the next instance
                    AlarmScheduler(context).scheduleAlarm(nextAlarm.id, nextAlarm.time, nextAlarm.message)
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Error handling recurrence: ${e.message}")
        }
    }

    private fun showNotification(context: Context, message: String, id: Int) {
        val channelId = "alarm_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Meeting Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for meeting alarms"
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALARM_MESSAGE", message)
            putExtra("ALARM_ID", id)
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            id,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Meeting Reminder")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)
    }
}
