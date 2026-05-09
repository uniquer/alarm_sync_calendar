package com.nen.alarmsynccalendar.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nen.alarmsynccalendar.MainActivity
import com.nen.alarmsynccalendar.ScheduledAlarm
import com.nen.alarmsynccalendar.AutoScheduleRule
import com.nen.alarmsynccalendar.ConnectedCloudAccount
import com.nen.alarmsynccalendar.CloudProvider
import com.nen.alarmsynccalendar.alarm.AlarmScheduler
import com.nen.alarmsynccalendar.calendar.CalendarScanner
import com.nen.alarmsynccalendar.calendar.GoogleCalendarScanner
import com.nen.alarmsynccalendar.calendar.OutlookCalendarScanner
import com.nen.alarmsynccalendar.calendar.EventSource
import com.nen.alarmsynccalendar.calendar.EventInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.provider.CalendarContract
import com.google.android.gms.auth.GoogleAuthUtil

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val gson = Gson()
    private val alarmScheduler = AlarmScheduler(context)
    private val calendarScanner = CalendarScanner(context)
    private val googleCalendarScanner = GoogleCalendarScanner(context)
    private val outlookCalendarScanner = OutlookCalendarScanner(context)

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "--- Starting Sync Cycle ---")
        val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        
        val alarmsJson = prefs.getString("alarm_list", null) ?: "[]"
        val rulesJson = prefs.getString("rule_list", null) ?: "[]"
        val accountsJson = prefs.getString("google_accounts_v3", "[]") ?: "[]"

        val alarmType = object : TypeToken<List<ScheduledAlarm>>() {}.type
        val ruleType = object : TypeToken<List<AutoScheduleRule>>() {}.type
        val accountType = object : TypeToken<List<ConnectedCloudAccount>>() {}.type
        
        val alarms: MutableList<ScheduledAlarm> = try { gson.fromJson(alarmsJson, alarmType) } catch (e: Exception) { return Result.failure() }
        val rules: List<AutoScheduleRule> = try { gson.fromJson(rulesJson, ruleType) } catch (e: Exception) { emptyList() }
        val accounts: List<ConnectedCloudAccount> = try { gson.fromJson(accountsJson, accountType) } catch (e: Exception) { emptyList() }

        val allCloudEvents = mutableListOf<EventInfo>()
        var anyAccountSynced = false
        accounts.forEach { acc ->
            try {
                if (acc.selectedCalendars.isNotEmpty()) {
                    val token = if (acc.provider == CloudProvider.GOOGLE) {
                        GoogleAuthUtil.getToken(applicationContext, acc.email, "oauth2:https://www.googleapis.com/auth/calendar.readonly")
                    } else acc.accessToken ?: ""
                    
                    val events = if (acc.provider == CloudProvider.GOOGLE) googleCalendarScanner.fetchEventsForAccount(acc.email, acc.selectedCalendars)
                                 else outlookCalendarScanner.fetchEventsForAccount(acc.email, token, acc.selectedCalendars)
                    allCloudEvents.addAll(events)
                    anyAccountSynced = true
                }
            } catch (e: Exception) { Log.e("SyncWorker", "Failed account ${acc.email}: ${e.message}") }
        }

        if (anyAccountSynced || accounts.isEmpty()) {
            prefs.edit().putString("cloud_events_cache", gson.toJson(allCloudEvents)).putLong("last_google_sync", System.currentTimeMillis()).apply()
        }
        
        var changed = false
        val finalAlarms = alarms.toMutableList()

        val iterator = finalAlarms.listIterator()
        while (iterator.hasNext()) {
            val alarm = iterator.next()
            if (alarm.calendarEventId != null || alarm.googleEventId != null) {
                val event = if (alarm.googleEventId != null) {
                    allCloudEvents.find { it.googleEventId == alarm.googleEventId }?.let { BasicEvent(it.startTime, it.title) }
                } else {
                    getEventDetails(alarm.calendarEventId!!)
                }

                if (event == null) {
                    if (alarm.googleEventId == null || allCloudEvents.isNotEmpty() || accounts.isEmpty()) {
                        alarmScheduler.cancelAlarm(alarm.id); iterator.remove(); changed = true
                    }
                } else {
                    var targetTime = event.startTime
                    if (alarm.sourceRuleId != null) {
                        rules.find { it.id == alarm.sourceRuleId }?.let { targetTime = event.startTime - (it.leadTimeMinutes * 60 * 1000) }
                    } else if (alarm.manualLeadTimeMinutes != null) {
                        targetTime = event.startTime - (alarm.manualLeadTimeMinutes * 60 * 1000)
                    }
                    
                    val cloudEvent = allCloudEvents.find { it.googleEventId == alarm.googleEventId }
                    if (targetTime != alarm.time || cloudEvent?.recurrenceDetails != alarm.googleRecurrenceInfo) {
                        alarmScheduler.scheduleAlarm(alarm.id, targetTime, event.message)
                        iterator.set(alarm.copy(time = targetTime, googleRecurrenceInfo = cloudEvent?.recurrenceDetails ?: alarm.googleRecurrenceInfo))
                        changed = true
                    }
                }
            }
        }

        val enabledRules = rules.filter { it.isEnabled }
        if (enabledRules.isNotEmpty()) {
            val localEvents = calendarScanner.getEventsForNextThreeMonths()
            val allFutureEvents: List<EventInfo> = localEvents + allCloudEvents
            enabledRules.forEach { rule ->
                allFutureEvents.forEach { event ->
                    val match = event.organizer?.contains(rule.organizerQuery, ignoreCase = true) == true || event.title.contains(rule.organizerQuery, ignoreCase = true)
                    if (match) {
                        val targetTime = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
                        val existing = if (event.source == EventSource.GOOGLE) finalAlarms.find { it.googleEventId == event.googleEventId } else finalAlarms.find { it.calendarEventId == event.id }
                        if (existing == null) {
                            val id = if (event.source == EventSource.GOOGLE) (event.googleEventId.hashCode() + rule.id).hashCode() else (event.id.toInt() + rule.id).hashCode()
                            alarmScheduler.scheduleAlarm(id, targetTime, event.title)
                            finalAlarms.add(ScheduledAlarm(id, targetTime, event.title, calendarEventId = if (event.source == EventSource.LOCAL) event.id else null, googleEventId = event.googleEventId, googleRecurrenceInfo = event.recurrenceDetails, sourceRuleId = rule.id))
                            changed = true
                        } else if (existing.time != targetTime) {
                            alarmScheduler.cancelAlarm(existing.id); alarmScheduler.scheduleAlarm(existing.id, targetTime, event.title)
                            val idx = finalAlarms.indexOf(existing); if (idx != -1) finalAlarms[idx] = existing.copy(time = targetTime, sourceRuleId = rule.id)
                            changed = true
                        }
                    }
                }
            }
        }

        if (changed) prefs.edit().putString("alarm_list", gson.toJson(finalAlarms)).apply()
        return Result.success()
    }

    private fun getEventDetails(eventId: Long): BasicEvent? {
        val proj = arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.TITLE)
        applicationContext.contentResolver.query(CalendarContract.Events.CONTENT_URI, proj, "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()), null)?.use {
            if (it.moveToFirst()) return BasicEvent(it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)), it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: "Event")
        }
        return null
    }

    data class BasicEvent(val startTime: Long, val message: String)
}
