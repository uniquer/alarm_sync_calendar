package com.nen.alarmsynccalendar.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nen.alarmsynccalendar.*
import java.text.SimpleDateFormat
import java.util.*

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val gson = Gson()
    private val repo = SyncRepository(context)

    override suspend fun doWork(): Result {
        val trigger = inputData.getString("trigger") ?: "Timer Triggered (Periodic)"
        
        val syncPrefs = applicationContext.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        syncPrefs.edit().putLong("last_execution_time", System.currentTimeMillis()).apply()

        val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)

        val accounts: List<ConnectedCloudAccount> = try {
            gson.fromJson(
                prefs.getString("google_accounts_v3", "[]") ?: "[]",
                object : TypeToken<List<ConnectedCloudAccount>>() {}.type
            )
        } catch (e: Exception) { emptyList() }

        if (accounts.isEmpty()) {
            log("[$trigger] Skipped: no accounts")
            return Result.success()
        }

        val alarms: MutableList<ScheduledAlarm> = try {
            gson.fromJson(
                prefs.getString("alarm_list", "[]") ?: "[]",
                object : TypeToken<List<ScheduledAlarm>>() {}.type
            )
        } catch (e: Exception) {
            log("[$trigger] Failed: read error")
            return Result.failure()
        }

        val cachedEvents: List<EventInfo> = try {
            gson.fromJson(
                prefs.getString("cloud_events_cache", "[]") ?: "[]",
                object : TypeToken<List<EventInfo>>() {}.type
            )
        } catch (e: Exception) { emptyList() }

        val excluded: List<ExcludedEvent> = try {
            gson.fromJson(
                prefs.getString("excluded_list", "[]") ?: "[]",
                object : TypeToken<List<ExcludedEvent>>() {}.type
            )
        } catch (e: Exception) { emptyList() }

        // Fetch all accounts in parallel, each with its own 10-second timeout.
        // Falls back to cached events per account on any error.
        val results = repo.fetchAllAccountEvents(accounts, cachedEvents)

        // Persist updated syncStatus and any rotated Outlook tokens.
        val updatedAccounts = accounts.map { acc ->
            results.find { it.email == acc.email }
                ?.let { acc.copy(syncStatus = it.status) }
                ?: acc
        }
        prefs.edit().putString("google_accounts_v3", gson.toJson(updatedAccounts)).apply()

        val allEvents = results
            .flatMap { it.events }
            .distinctBy { "${it.title}|${it.startTime}" }

        val syncedEmails = results
            .filter { it.status == AccountSyncStatus.OK }
            .map { it.email }
            .toSet()

        if (syncedEmails.isNotEmpty()) {
            prefs.edit()
                .putString("cloud_events_cache", gson.toJson(allEvents))
                .putLong("last_google_sync", System.currentTimeMillis())
                .apply()
        }

        val (newAlarms, changed) = repo.reconcileAlarms(allEvents, alarms, excluded, syncedEmails)
        if (changed) {
            prefs.edit().putString("alarm_list", gson.toJson(newAlarms)).apply()
        }

        log("[$trigger] OK: ${syncedEmails.size}/${accounts.size}")
        return Result.success()
    }

    private fun log(message: String) {
        Log.d("SyncWorker", message)
        val prefs = applicationContext.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val existing = prefs.getString("history", "") ?: ""
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        prefs.edit().putString("history", "[$ts] $message\n$existing".take(5000)).apply()
    }
}
