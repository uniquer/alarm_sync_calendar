package com.nen.alarmsynccalendar.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
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

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureSyncNotificationChannel(notificationManager)

        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (!hasExactAlarmPermission) {
            val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                201,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, "sync_channel")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Exact Alarms Permission Required")
                .setContentText("CalAlarm Sync cannot schedule precise alarms. Tap to grant permission.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(201, notification)
        } else {
            notificationManager.cancel(201)
        }

        val prefs = applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)

        val lastGoogleSync = prefs.getLong("last_google_sync", 0L)
        val now = System.currentTimeMillis()
        if (now - lastGoogleSync < 10 * 60 * 1000L) {
            log("[$trigger] Skipped: synced recently")
            return Result.success()
        }

        val accounts: List<ConnectedCloudAccount> = try {
            gson.fromJson(
                prefs.getString("google_accounts_v3", "[]") ?: "[]",
                object : TypeToken<List<ConnectedCloudAccount>>() {}.type
            )
        } catch (e: Exception) { emptyList() }

        if (accounts.isEmpty()) {
            log("[$trigger] Skipped: no accounts")
            notificationManager.cancel(200)
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

        // Check if any accounts had authentication failures (user-actionable)
        val failedAccounts = results.filter { it.status == AccountSyncStatus.AUTH_ERROR }
        if (failedAccounts.isNotEmpty()) {
            val failedSummary = failedAccounts.joinToString(", ") { acc ->
                val reason = when (acc.status) {
                    AccountSyncStatus.AUTH_ERROR -> "Auth Error"
                    AccountSyncStatus.TIMEOUT -> "Timeout"
                    AccountSyncStatus.NETWORK_ERROR -> "Network Error"
                    else -> "Error"
                }
                "${acc.email} ($reason)"
            }

            val appIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                200,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(applicationContext, "sync_channel")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Calendar Sync Failed")
                .setContentText("Failed to sync: $failedSummary")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Failed to sync the following accounts:\n$failedSummary\n\nOpen the app to fix settings or re-connect accounts."))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(200, notification)
        } else {
            notificationManager.cancel(200)
        }

        val allEvents = repo.enrichWithTravelInfo(
            results
                .flatMap { it.events }
                .distinctBy { "${it.title}|${it.startTime}" },
            cachedEvents
        )

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

    private fun ensureSyncNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sync_channel",
                "Sync & Permission Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts about calendar sync failures and background permission errors."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun log(message: String) {
        Log.d("SyncWorker", message)
        val prefs = applicationContext.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val existing = prefs.getString("history", "") ?: ""
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        prefs.edit().putString("history", "[$ts] $message\n$existing".take(5000)).apply()
    }
}
