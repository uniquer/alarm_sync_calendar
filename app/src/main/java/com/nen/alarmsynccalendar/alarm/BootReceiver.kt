package com.nen.alarmsynccalendar.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nen.alarmsynccalendar.MainActivity
import com.nen.alarmsynccalendar.ScheduledAlarm
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val scheduler = AlarmScheduler(context)
            val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
            val json = prefs.getString("alarm_list", null)
            
            if (json != null) {
                val gson = Gson()
                val type = object : TypeToken<List<ScheduledAlarm>>() {}.type
                val alarms: List<ScheduledAlarm> = gson.fromJson(json, type)
                val currentTime = System.currentTimeMillis()

                alarms.forEach { alarm ->
                    if (alarm.time > currentTime) {
                        scheduler.scheduleAlarm(alarm.id, alarm.time, alarm.message, alarm.meetingLink)
                    }
                }
            }

            // Also schedule the fallback sync alarm to kickstart background syncs after boot
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val syncIntent = Intent(context, com.nen.alarmsynccalendar.sync.SyncTriggerReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                999,
                syncIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + (30 * 60 * 1000L),
                    pendingIntent
                )
            }
        }
    }
}