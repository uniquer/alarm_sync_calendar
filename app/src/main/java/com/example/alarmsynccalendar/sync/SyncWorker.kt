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
        Log.d("SyncWorker", "--- Starting Sync Cycle ---")
        val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        
        val alarmsJson = prefs.getString("alarm_list", null) ?: "[]"
        val rulesJson = prefs.getString("rule_list", null) ?: "[]"
        Log.d("SyncWorker", "Current Alarms: $alarmsJson")

        val alarmType = object : TypeToken<List<ScheduledAlarm>>() {}.type
        val ruleType = object : TypeToken<List<AutoScheduleRule>>() {}.type
        
        val alarms: MutableList<ScheduledAlarm> = try {
            gson.fromJson(alarmsJson, alarmType)
        } catch (e: Exception) {
            Log.e("SyncWorker", "Failed to parse alarms, skipping sync to avoid corruption")
            return Result.failure()
        }
        
        val rules: List<AutoScheduleRule> = try {
            gson.fromJson(rulesJson, ruleType)
        } catch (e: Exception) {
            Log.e("SyncWorker", "Failed to parse rules")
            emptyList()
        }
        
        var changed = false
        val finalAlarms = alarms.toMutableList()

        // 1. Sync existing alarms (updates/cancellations)
        val iterator = finalAlarms.listIterator()
        while (iterator.hasNext()) {
            val alarm = iterator.next()
            if (alarm.calendarEventId != null) {
                val event = getEventDetails(alarm.calendarEventId)
                if (event == null) {
                    Log.d("SyncWorker", "Event ${alarm.calendarEventId} cancelled or deleted, removing alarm ${alarm.id}")
                    alarmScheduler.cancelAlarm(alarm.id)
                    iterator.remove()
                    changed = true
                } else {
                    // Check if time needs update
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
                        Log.d("SyncWorker", "Time changed for event ${alarm.calendarEventId}, updating alarm ${alarm.id}")
                        alarmScheduler.scheduleAlarm(alarm.id, targetTime, event.message)
                        iterator.set(alarm.copy(time = targetTime))
                        changed = true
                    }
                }
            }
        }

        // 2. Process Auto-Schedule Rules (New events)
        val enabledRules = rules.filter { it.isEnabled }
        if (enabledRules.isNotEmpty()) {
            val futureEvents = calendarScanner.getEventsForNextThreeMonths()
            Log.d("SyncWorker", "Scanning ${futureEvents.size} future events for ${enabledRules.size} rules")
            
            enabledRules.forEach { rule ->
                futureEvents.forEach { event ->
                    val match = event.organizer?.contains(rule.organizerQuery, ignoreCase = true) == true || 
                                event.title.contains(rule.organizerQuery, ignoreCase = true)
                    
                    if (match) {
                        val targetTime = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
                        val existing = finalAlarms.find { it.calendarEventId == event.id }
                        
                        if (existing == null) {
                            val id = (event.id.toInt() + rule.id).hashCode()
                            Log.d("SyncWorker", "Auto-scheduling new alarm $id for event ${event.id}")
                            alarmScheduler.scheduleAlarm(id, targetTime, event.title)
                            finalAlarms.add(ScheduledAlarm(id, targetTime, event.title, event.id, rule.id))
                            changed = true
                        } else if (existing.time != targetTime) {
                            Log.d("SyncWorker", "Updating auto-alarm ${existing.id} for event ${event.id} to new time")
                            alarmScheduler.cancelAlarm(existing.id)
                            alarmScheduler.scheduleAlarm(existing.id, targetTime, event.title)
                            val idx = finalAlarms.indexOf(existing)
                            finalAlarms[idx] = existing.copy(time = targetTime, sourceRuleId = rule.id)
                            changed = true
                        }
                    }
                }
            }
        }

        if (changed) {
            Log.d("SyncWorker", "Saving updated list of ${finalAlarms.size} alarms")
            prefs.edit().putString("alarm_list", gson.toJson(finalAlarms)).apply()
        }

        Log.d("SyncWorker", "--- Sync Cycle Complete ---")
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
