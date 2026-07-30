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

        val type = when (trigger) {
            "Periodic", "Timer Triggered (Periodic)" -> "Periodic"
            "Fallback" -> "Fallback"
            "User Awake" -> "User Awake"
            "Manual" -> "Manual"
            "App refresh", "App Refresh" -> "App Refresh"
            else -> trigger
        }

        val hasNext = (type == "Periodic" || type == "Fallback")
        val nextTimeStr = if (hasNext) {
            val offsetMs = if (type == "Periodic") 30 * 60 * 1000L else 120 * 60 * 1000L
            val nextTime = now + offsetMs
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextTime))
            " Next run: $timeStr"
        } else {
            ""
        }

        if (now - lastGoogleSync < 10 * 60 * 1000L) {
            log("[$type] Skipped: synced recently$nextTimeStr")
            return Result.success()
        }

        val accounts: List<ConnectedCloudAccount> = try {
            val raw: List<ConnectedCloudAccount> = gson.fromJson(
                prefs.getString("google_accounts_v3", "[]") ?: "[]",
                object : TypeToken<List<ConnectedCloudAccount>>() {}.type
            )
            raw.map { acc -> if (acc.selectedSecondaryCalendarIds == null) acc.copy(selectedSecondaryCalendarIds = emptyList()) else acc }
        } catch (e: Exception) { emptyList() }

        if (accounts.isEmpty()) {
            log("[$type] Skipped: no accounts$nextTimeStr")
            notificationManager.cancel(200)
            return Result.success()
        }

        val alarms: MutableList<ScheduledAlarm> = try {
            gson.fromJson(
                prefs.getString("alarm_list", "[]") ?: "[]",
                object : TypeToken<List<ScheduledAlarm>>() {}.type
            )
        } catch (e: Exception) {
            log("[$type] Failed: read error")
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



        results.forEach { r ->
            val obfuscated = obfuscateEmail(r.email)
            val updateCount = calculateUpdateCount(r.email, alarms, newAlarms, allEvents, cachedEvents)
            log("[$type] $obfuscated overall:${r.events.size} update:$updateCount$nextTimeStr")
        }

        log("[$type] Done: ${syncedEmails.size}/${accounts.size} accounts OK, ${allEvents.size} events, alarms ${if (changed) "updated" else "unchanged"}")
        return Result.success()
    }

    private fun obfuscateEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return email
        val username = parts[0]
        val domain = parts[1]
        val obfuscatedUser = if (username.length > 3) {
            username.substring(0, 3) + "..."
        } else {
            username + "..."
        }
        val domainName = domain.split(".")[0]
        val obfuscatedDomain = if (domainName.length > 2) {
            domainName.substring(0, 2)
        } else {
            domainName
        }
        return "$obfuscatedUser@$obfuscatedDomain"
    }

    private fun calculateUpdateCount(
        email: String,
        oldAlarms: List<ScheduledAlarm>,
        newAlarms: List<ScheduledAlarm>,
        allEvents: List<EventInfo>,
        cachedEvents: List<EventInfo>
    ): Int {
        var count = 0
        count += newAlarms.count { newAlarm ->
            oldAlarms.none { it.googleEventId == newAlarm.googleEventId } &&
            allEvents.any { it.googleEventId == newAlarm.googleEventId && it.accountEmail == email }
        }
        count += newAlarms.count { newAlarm ->
            val oldAlarm = oldAlarms.find { it.googleEventId == newAlarm.googleEventId }
            oldAlarm != null && (
                oldAlarm.time != newAlarm.time ||
                oldAlarm.meetingLink != newAlarm.meetingLink ||
                oldAlarm.location != newAlarm.location ||
                oldAlarm.distanceKm != newAlarm.distanceKm ||
                oldAlarm.travelTimeMinutes != newAlarm.travelTimeMinutes ||
                oldAlarm.noDrivingRoute != newAlarm.noDrivingRoute ||
                oldAlarm.eventStartTime != newAlarm.eventStartTime
            ) && allEvents.any { it.googleEventId == newAlarm.googleEventId && it.accountEmail == email }
        }
        count += oldAlarms.count { oldAlarm ->
            newAlarms.none { it.googleEventId == oldAlarm.googleEventId } &&
            (allEvents.any { it.googleEventId == oldAlarm.googleEventId && it.accountEmail == email } ||
             cachedEvents.any { it.googleEventId == oldAlarm.googleEventId && it.accountEmail == email })
        }
        return count
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
