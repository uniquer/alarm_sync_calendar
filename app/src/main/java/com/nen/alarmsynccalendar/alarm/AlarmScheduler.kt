package com.nen.alarmsynccalendar.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleAlarm(id: Int, timeInMillis: Long, message: String, meetingLink: String? = null, location: String? = null, travelTimeMinutes: Int? = null, distanceKm: Double? = null, noDrivingRoute: Boolean? = null) {
        // A past target time (e.g. travel time + buffer pushed the alarm before "now")
        // would make setAlarmClock fire immediately mid-sync. Skip the system alarm;
        // the entry still appears under Past alarms with its links and travel info.
        if (timeInMillis <= System.currentTimeMillis()) {
            Log.d("AlarmScheduler", "Skipping alarm $id: target time $timeInMillis is in the past")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Exact alarm permission required. Please enable it in Settings under Special App Access.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_MESSAGE", message)
            putExtra("ALARM_ID", id)
            putExtra("ALARM_MEETING_LINK", meetingLink)
            putExtra("ALARM_LOCATION", location)
            if (travelTimeMinutes != null) putExtra("ALARM_TRAVEL_MINUTES", travelTimeMinutes)
            if (distanceKm != null) putExtra("ALARM_DISTANCE_KM", distanceKm)
            if (noDrivingRoute == true) putExtra("ALARM_NO_ROUTE", true)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Log.d("AlarmScheduler", "Scheduling alarm $id at $timeInMillis")

        val alarmClockInfo = AlarmManager.AlarmClockInfo(
            timeInMillis,
            pendingIntent
        )

        alarmManager.setAlarmClock(
            alarmClockInfo,
            pendingIntent
        )
    }

    fun cancelAlarm(id: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d("AlarmScheduler", "Cancelled alarm $id")
    }
}