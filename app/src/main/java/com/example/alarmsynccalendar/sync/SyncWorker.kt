package com.example.alarmsynccalendar.sync

import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.alarmsynccalendar.ScheduledAlarm
import com.example.alarmsynccalendar.alarm.AlarmScheduler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import com.example.alarmsynccalendar.AutoScheduleRule
import com.example.alarmsynccalendar.calendar.CalendarScanner

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val gson = Gson()
    private val alarmScheduler = AlarmScheduler(context)
    private val calendarScanner = CalendarScanner(context)

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting background calendar sync...")
        val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        
        val alarmsJson = prefs.getString("alarm_list", null) ?: "[]"
        val rulesJson = prefs.getString("rule_list", null) ?: "[]"

        val alarmType = object : TypeToken<List<ScheduledAlarm>>() {}.type
        val ruleType = object : TypeToken<List<AutoScheduleRule>>() {}.type
        
        val alarms: List<ScheduledAlarm> = gson.fromJson(alarmsJson, alarmType)
        val rules: List<AutoScheduleRule> = gson.fromJson(rulesJson, ruleType)
        
        val syncedAlarms = alarms.toMutableList()
        var changed = false

        // 1. Sync existing alarms (updates/cancellations)
        val iterator = syncedAlarms.iterator()
        val toUpdate = mutableListOf<ScheduledAlarm>()
        
        while (iterator.hasNext()) {
            val alarm = iterator.next()
            if (alarm.calendarEventId != null) {
                val event = getEventDetails(alarm.calendarEventId)
                if (event == null) {
                    alarmScheduler.cancelAlarm(alarm.id)
                    changed = true
                } else if (event.startTime != alarm.time) {
                    // Check if it's an auto-scheduled alarm or a manually synced one with lead time
                    var targetTime = event.startTime
                    if (alarm.sourceRuleId != null) {
                        val rule = rules.find { it.id == alarm.sourceRuleId }
                        if (rule != null) {
                            targetTime = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
                        }
                    } else if (alarm.manualLeadTimeMinutes != null) {
                        targetTime = event.startTime - (alarm.manualLeadTimeMinutes * 60 * 1000)
                    }
                    
                    if (targetTime != alarm.time) {
                        alarmScheduler.scheduleAlarm(alarm.id, targetTime, event.message)
                        toUpdate.add(alarm.copy(time = targetTime))
                        changed = true
                    }
                }
            }
        }

        // 2. Process Auto-Schedule Rules (New events)
        if (rules.isNotEmpty()) {
            val futureEvents = calendarScanner.getEventsForNextThreeMonths()
            rules.filter { it.isEnabled }.forEach { rule ->
                futureEvents.forEach { event ->
                    val match = event.organizer?.contains(rule.organizerQuery, ignoreCase = true) == true || 
                                event.title.contains(rule.organizerQuery, ignoreCase = true)
                    
                    if (match) {
                        val targetTime = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
                        val existing = syncedAlarms.find { it.calendarEventId == event.id }
                        
                        if (existing == null) {
                            val id = (event.id.toInt() + rule.id).hashCode()
                            alarmScheduler.scheduleAlarm(id, targetTime, event.title)
                            syncedAlarms.add(ScheduledAlarm(id, targetTime, event.title, event.id, rule.id))
                            changed = true
                        } else if (existing.time != targetTime) {
                            // Overwrite existing if time differs
                            alarmScheduler.cancelAlarm(existing.id)
                            alarmScheduler.scheduleAlarm(existing.id, targetTime, event.title)
                            val idx = syncedAlarms.indexOf(existing)
                            syncedAlarms[idx] = existing.copy(time = targetTime, sourceRuleId = rule.id)
                            changed = true
                        }
                    }
                }
            }
        }

        if (changed) {
            // Clean up old alarms (past events) that are gone from calendar
            val finalAlarms = syncedAlarms.filter { alarm ->
                if (alarm.calendarEventId == null) return@filter true
                val exists = getEventDetails(alarm.calendarEventId)
                exists != null
            }.map { alarm ->
                toUpdate.find { it.id == alarm.id } ?: alarm
            }
            
            prefs.edit().putString("alarm_list", gson.toJson(finalAlarms)).apply()
        }

        return Result.success()
    }

    private data class BasicEvent(val startTime: Long, val message: String)

    private fun getEventDetails(eventId: Long): BasicEvent? {
        val projection = arrayOf(
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.SELF_ATTENDEE_STATUS
        )
        val cursor = applicationContext.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.STATUS))
                val attendeeStatus = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.SELF_ATTENDEE_STATUS))
                
                if (status == CalendarContract.Events.STATUS_CANCELED || 
                    attendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) {
                    return null
                }
                
                val start = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                val title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: "Event"
                return BasicEvent(start, title)
            }
        }
        return null
    }
}
