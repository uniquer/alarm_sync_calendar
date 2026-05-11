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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val gson = Gson()
    private val alarmScheduler = AlarmScheduler(context)
    private val calendarScanner = CalendarScanner(context)
    private val googleCalendarScanner = GoogleCalendarScanner(context)
    private val outlookCalendarScanner = OutlookCalendarScanner(context)

    private fun logSyncEvent(context: Context, message: String) {
        val prefs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val logs = prefs.getString("history", "") ?: ""
        val timestamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val newLogs = "[$timestamp] $message\n$logs".take(5000) // Keep last 5k chars
        prefs.edit().putString("history", newLogs).apply()
    }

    private suspend fun refreshOutlookToken(acc: ConnectedCloudAccount): String? = withContext(Dispatchers.IO) {
        if (acc.refreshToken == null) return@withContext null
        try {
            val url = URL("https://login.microsoftonline.com/common/oauth2/v2.0/token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val postData = "client_id=acbc12d9-d41d-4df2-8517-57bdfdd3b0df&grant_type=refresh_token&refresh_token=${acc.refreshToken}"
            conn.outputStream.write(postData.toByteArray())
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val newAccess = json.getString("access_token")
                val newRefresh = json.optString("refresh_token", acc.refreshToken)
                
                // SAVE IMMEDIATELY to avoid losing token on next sync
                val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
                val accountsJson = prefs.getString("google_accounts_v3", "[]") ?: "[]"
                val accountType = object : TypeToken<List<ConnectedCloudAccount>>() {}.type
                val accounts: MutableList<ConnectedCloudAccount> = gson.fromJson(accountsJson, accountType)
                val idx = accounts.indexOfFirst { it.email == acc.email }
                if (idx != -1) {
                    accounts[idx] = accounts[idx].copy(accessToken = newAccess, refreshToken = newRefresh)
                    prefs.edit().putString("google_accounts_v3", gson.toJson(accounts)).apply()
                }
                
                return@withContext newAccess
            } else {
                logSyncEvent(applicationContext, "Outlook Token Error: ${conn.responseCode}")
            }
        } catch (e: Exception) { 
            Log.e("SyncWorker", "Outlook Token Refresh Error", e) 
            logSyncEvent(applicationContext, "Outlook Refresh Fail: ${e.message}")
        }
        null
    }

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "--- Starting Sync Cycle ---")
        val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        
        val alarmsJson = prefs.getString("alarm_list", null) ?: "[]"
        val rulesJson = prefs.getString("rule_list", null) ?: "[]"
        val accountsJson = prefs.getString("google_accounts_v3", "[]") ?: "[]"
        val cachedEventsJson = prefs.getString("cloud_events_cache", "[]") ?: "[]"

        val alarmType = object : TypeToken<List<ScheduledAlarm>>() {}.type
        val ruleType = object : TypeToken<List<AutoScheduleRule>>() {}.type
        val accountType = object : TypeToken<List<ConnectedCloudAccount>>() {}.type
        val eventType = object : TypeToken<List<EventInfo>>() {}.type
        
        val alarms: MutableList<ScheduledAlarm> = try { gson.fromJson(alarmsJson, alarmType) } catch (e: Exception) { return Result.failure() }
        val rules: List<AutoScheduleRule> = try { gson.fromJson(rulesJson, ruleType) } catch (e: Exception) { emptyList() }
        val accounts: List<ConnectedCloudAccount> = try { gson.fromJson(accountsJson, accountType) } catch (e: Exception) { emptyList() }
        val cachedEvents: List<EventInfo> = try { gson.fromJson(cachedEventsJson, eventType) } catch (e: Exception) { emptyList() }

        if (accounts.isEmpty()) {
            logSyncEvent(applicationContext, "Sync skipped: No accounts connected")
            return Result.success()
        }

        val allCloudEvents = mutableListOf<EventInfo>()
        val syncedAccountEmails = mutableSetOf<String>()
        accounts.forEach { acc ->
            try {
                if (acc.selectedCalendars.isNotEmpty()) {
                    val token = if (acc.provider == CloudProvider.GOOGLE) {
                        GoogleAuthUtil.getToken(applicationContext, acc.email, "oauth2:https://www.googleapis.com/auth/calendar.readonly")
                    } else {
                        refreshOutlookToken(acc) ?: acc.accessToken ?: ""
                    }
                    
                    val events = if (acc.provider == CloudProvider.GOOGLE) googleCalendarScanner.fetchEventsForAccount(acc.email, acc.selectedCalendars)
                                 else outlookCalendarScanner.fetchEventsForAccount(acc.email, token, acc.selectedCalendars)
                    allCloudEvents.addAll(events)
                    syncedAccountEmails.add(acc.email)
                    logSyncEvent(applicationContext, "Sync Success: ${acc.email} (${events.size} events)")
                }
            } catch (e: Exception) { 
                Log.e("SyncWorker", "Failed account ${acc.email}: ${e.message}")
                logSyncEvent(applicationContext, "Sync Failed: ${acc.email} (${e.message})")
                allCloudEvents.addAll(cachedEvents.filter { it.accountEmail == acc.email })
            }
        }

        if (syncedAccountEmails.isNotEmpty() || accounts.isEmpty()) {
            prefs.edit().putString("cloud_events_cache", gson.toJson(allCloudEvents)).putLong("last_google_sync", System.currentTimeMillis()).apply()
        }
        
        val localEvents = calendarScanner.getEventsForNextThreeMonths()
        var changed = false
        val finalAlarms = alarms.toMutableList()

        val iterator = finalAlarms.listIterator()
        while (iterator.hasNext()) {
            val alarm = iterator.next()
            
            // Treat any alarm whose target time has already passed as a read-only historical record.
            if (alarm.time <= System.currentTimeMillis()) {
                continue
            }
            
            if (alarm.calendarEventId != null || alarm.googleEventId != null) {
                val event = if (alarm.googleEventId != null) {
                    allCloudEvents.find { it.googleEventId == alarm.googleEventId }?.let { BasicEvent(it.startTime, it.title) }
                } else {
                    getEventDetails(alarm.calendarEventId!!)
                }

                if (event == null) {
                    if (alarm.googleEventId != null) {
                        if (syncedAccountEmails.isNotEmpty()) {
                            alarmScheduler.cancelAlarm(alarm.id); iterator.remove(); changed = true
                        }
                    } else {
                        alarmScheduler.cancelAlarm(alarm.id); iterator.remove(); changed = true
                    }
                } else {
                    var targetTime = event.startTime
                    var shouldDelete = false
                    if (alarm.sourceRuleId != null) {
                        val rule = rules.find { it.id == alarm.sourceRuleId }
                        if (rule != null) {
                            targetTime = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
                            val cloudEvent = allCloudEvents.find { it.googleEventId == alarm.googleEventId }
                            val locEvent = localEvents.find { it.id == alarm.calendarEventId }
                            val fullTitle = cloudEvent?.title ?: locEvent?.title ?: event.message
                            val fullOrg = cloudEvent?.organizer ?: locEvent?.organizer
                            val match = fullOrg?.contains(rule.organizerQuery, ignoreCase = true) == true || fullTitle.contains(rule.organizerQuery, ignoreCase = true)
                            if (!match) shouldDelete = true
                        } else {
                            shouldDelete = true
                        }
                    } else if (alarm.manualLeadTimeMinutes != null) {
                        targetTime = event.startTime - (alarm.manualLeadTimeMinutes * 60 * 1000)
                    }
                    
                    if (shouldDelete) {
                        alarmScheduler.cancelAlarm(alarm.id); iterator.remove(); changed = true
                    } else {
                        val cloudEvent = allCloudEvents.find { it.googleEventId == alarm.googleEventId }
                        if (targetTime != alarm.time || cloudEvent?.recurrenceDetails != alarm.googleRecurrenceInfo) {
                            alarmScheduler.scheduleAlarm(alarm.id, targetTime, event.message)
                            iterator.set(alarm.copy(time = targetTime, googleRecurrenceInfo = cloudEvent?.recurrenceDetails ?: alarm.googleRecurrenceInfo))
                            changed = true
                        }
                    }
                }
            }
        }

        val enabledRules = rules.filter { it.isEnabled }
        if (enabledRules.isNotEmpty()) {
            val allFutureEvents: List<EventInfo> = (allCloudEvents + localEvents).distinctBy { "${it.title}|${it.startTime}" }
            enabledRules.forEach { rule ->
                allFutureEvents.forEach { event ->
                    val match = event.organizer?.contains(rule.organizerQuery, ignoreCase = true) == true || event.title.contains(rule.organizerQuery, ignoreCase = true)
                    if (match && event.startTime > System.currentTimeMillis()) {
                        val targetTime = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
                        val existing = finalAlarms.find { 
                            (it.googleEventId != null && it.googleEventId == event.googleEventId) || 
                            (it.calendarEventId != null && it.calendarEventId == event.id)
                        }
                        
                        if (existing == null) {
                            val id = if (event.source == EventSource.CLOUD) (event.googleEventId.hashCode() + rule.id).hashCode() else (event.id.toInt() + rule.id).hashCode()
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
