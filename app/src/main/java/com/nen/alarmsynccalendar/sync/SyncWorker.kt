package com.nen.alarmsynccalendar.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nen.alarmsynccalendar.MainActivity
import com.nen.alarmsynccalendar.ScheduledAlarm
import com.nen.alarmsynccalendar.ConnectedCloudAccount
import com.nen.alarmsynccalendar.CloudProvider
import com.nen.alarmsynccalendar.ExcludedEvent
import com.nen.alarmsynccalendar.alarm.AlarmScheduler
import com.nen.alarmsynccalendar.calendar.GoogleCalendarScanner
import com.nen.alarmsynccalendar.calendar.OutlookCalendarScanner
import com.nen.alarmsynccalendar.EventInfo
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
    private val googleCalendarScanner = GoogleCalendarScanner(context)
    private val outlookCalendarScanner = OutlookCalendarScanner(context)

    private fun logSyncEvent(context: Context, message: String) {
        val prefs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val logs = prefs.getString("history", "") ?: ""
        val timestamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val newLogs = "[$timestamp] $message\n$logs".take(5000)
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
            }
        } catch (e: Exception) { 
            Log.e("SyncWorker", "Outlook Token Refresh Error", e) 
        }
        null
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        
        val alarmsJson = prefs.getString("alarm_list", null) ?: "[]"
        val accountsJson = prefs.getString("google_accounts_v3", "[]") ?: "[]"
        val cachedEventsJson = prefs.getString("cloud_events_cache", "[]") ?: "[]"
        val excludedJson = prefs.getString("excluded_list", "[]") ?: "[]"

        val alarmType = object : TypeToken<List<ScheduledAlarm>>() {}.type
        val accountType = object : TypeToken<List<ConnectedCloudAccount>>() {}.type
        val eventType = object : TypeToken<List<EventInfo>>() {}.type
        val excludedType = object : TypeToken<List<ExcludedEvent>>() {}.type
        
        val alarms: MutableList<ScheduledAlarm> = try { gson.fromJson(alarmsJson, alarmType) } catch (e: Exception) { return Result.failure() }
        val accounts: List<ConnectedCloudAccount> = try { gson.fromJson(accountsJson, accountType) } catch (e: Exception) { emptyList() }
        val cachedEvents: List<EventInfo> = try { gson.fromJson(cachedEventsJson, eventType) } catch (e: Exception) { emptyList() }
        val excluded: List<ExcludedEvent> = try { gson.fromJson(excludedJson, excludedType) } catch (e: Exception) { emptyList() }

        if (accounts.isEmpty()) {
            logSyncEvent(applicationContext, "Sync skipped: No accounts connected")
            return Result.success()
        }

        val allCloudEvents = mutableListOf<EventInfo>()
        val syncedAccountEmails = mutableSetOf<String>()
        accounts.forEach { acc ->
            if (acc.isPrimaryEnabled) {
                try {
                    val token = if (acc.provider == CloudProvider.GOOGLE) {
                        GoogleAuthUtil.getToken(applicationContext, acc.email, "oauth2:https://www.googleapis.com/auth/calendar.readonly")
                    } else {
                        refreshOutlookToken(acc) ?: acc.accessToken ?: ""
                    }
                    
                    val events = if (acc.provider == CloudProvider.GOOGLE) googleCalendarScanner.fetchEventsForAccount(acc.email, emptyList())
                                 else outlookCalendarScanner.fetchEventsForAccount(acc.email, token, emptyList())
                    allCloudEvents.addAll(events)
                    syncedAccountEmails.add(acc.email)
                } catch (e: Exception) { 
                    allCloudEvents.addAll(cachedEvents.filter { it.accountEmail == acc.email })
                }
            }
        }

        if (syncedAccountEmails.isNotEmpty()) {
            prefs.edit().putString("cloud_events_cache", gson.toJson(allCloudEvents)).putLong("last_google_sync", System.currentTimeMillis()).apply()
        }
        
        var changed = false
        val finalAlarms = alarms.toMutableList()

        // 1. RECONCILE EXISTING ALARMS (Update time or Delete if gone/excluded)
        val iterator = finalAlarms.listIterator()
        while (iterator.hasNext()) {
            val alarm = iterator.next()
            if (alarm.time <= System.currentTimeMillis()) continue
            
            if (alarm.googleEventId != null) {
                // Check if this alarm is now excluded
                val seriesId = alarm.googleEventId.split("_")[0]
                if (excluded.any { it.id == alarm.googleEventId || it.id == seriesId }) {
                    alarmScheduler.cancelAlarm(alarm.id); iterator.remove(); changed = true; continue
                }

                val event = allCloudEvents.find { it.googleEventId == alarm.googleEventId }
                if (event == null) {
                    // Event was deleted from cloud, so remove our local alarm
                    if (syncedAccountEmails.isNotEmpty()) { alarmScheduler.cancelAlarm(alarm.id); iterator.remove(); changed = true }
                } else {
                    val targetTime = event.startTime - (5 * 60 * 1000) // Default 5 min lead time for mirror sync
                    if (targetTime != alarm.time) {
                        alarmScheduler.scheduleAlarm(alarm.id, targetTime, event.title)
                        iterator.set(alarm.copy(time = targetTime, googleRecurrenceInfo = event.recurringEventId ?: if (event.isRecurring) "true" else null))
                        changed = true
                    }
                }
            }
        }

        // 2. MIRROR SYNC: Add new alarms for all upcoming events that aren't excluded or already synced
        allCloudEvents.forEach { event ->
            val seriesId = event.recurringEventId ?: event.googleEventId?.split("_")?.get(0)
            val isExcluded = excluded.any { it.id == event.googleEventId || it.id == seriesId }
            
            if (!isExcluded && event.startTime > System.currentTimeMillis() && event.googleEventId != null) {
                val existing = finalAlarms.find { it.googleEventId == event.googleEventId }
                if (existing == null) {
                    val targetTime = event.startTime - (5 * 60 * 1000)
                    val id = event.googleEventId.hashCode().hashCode() // Simple derivation
                    alarmScheduler.scheduleAlarm(id, targetTime, event.title)
                    finalAlarms.add(ScheduledAlarm(id, targetTime, event.title, googleEventId = event.googleEventId, googleRecurrenceInfo = event.recurringEventId ?: if (event.isRecurring) "true" else null))
                    changed = true
                }
            }
        }

        if (changed) prefs.edit().putString("alarm_list", gson.toJson(finalAlarms)).apply()
        logSyncEvent(applicationContext, "Background Mirror Sync Complete")
        return Result.success()
    }
}
