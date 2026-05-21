package com.nen.alarmsynccalendar.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nen.alarmsynccalendar.*
import com.nen.alarmsynccalendar.alarm.AlarmScheduler
import com.nen.alarmsynccalendar.calendar.GoogleCalendarScanner
import com.nen.alarmsynccalendar.calendar.OutlookCalendarScanner
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AccountFetchResult(
    val email: String,
    val events: List<EventInfo>,
    val status: AccountSyncStatus
)

private class OutlookAuthException(message: String) : Exception(message)

class SyncRepository(private val context: Context) {
    private val googleScanner = GoogleCalendarScanner(context)
    private val outlookScanner = OutlookCalendarScanner(context)
    val alarmScheduler = AlarmScheduler(context)
    private val gson = Gson()

    /** Single source of truth for deriving an alarm ID from a calendar event ID. */
    fun alarmIdForEvent(googleEventId: String): Int = googleEventId.hashCode()

    /**
     * Refreshes an Outlook OAuth token using the stored refresh token.
     * Persists the new tokens to SharedPreferences so SyncWorker and ViewModel
     * both see the latest values without duplicating this logic.
     */
    suspend fun refreshOutlookToken(acc: ConnectedCloudAccount): String = withContext(Dispatchers.IO) {
        val refreshToken = acc.refreshToken
            ?: throw OutlookAuthException("No refresh token stored for ${acc.email}")
        try {
            val url = URL("https://login.microsoftonline.com/common/oauth2/v2.0/token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val encodedToken = URLEncoder.encode(refreshToken, "UTF-8")
            val postData = "client_id=acbc12d9-d41d-4df2-8517-57bdfdd3b0df&grant_type=refresh_token&refresh_token=$encodedToken"
            conn.outputStream.write(postData.toByteArray())

            when (conn.responseCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val newAccess = json.getString("access_token")
                    val newRefresh = json.optString("refresh_token", refreshToken)
                    persistOutlookTokens(acc.email, newAccess, newRefresh)
                    return@withContext newAccess
                }
                400, 401 -> throw OutlookAuthException("Token refresh rejected (${conn.responseCode}) for ${acc.email}")
                else -> throw IOException("Token refresh HTTP ${conn.responseCode} for ${acc.email}")
            }
        } catch (e: OutlookAuthException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.e("SyncRepository", "Outlook token refresh failed for ${acc.email}", e)
            throw OutlookAuthException("Unexpected error refreshing token for ${acc.email}")
        }
    }

    private fun persistOutlookTokens(email: String, accessToken: String, refreshToken: String) {
        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val accountsJson = prefs.getString("google_accounts_v3", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<ConnectedCloudAccount>>() {}.type
        val accounts: MutableList<ConnectedCloudAccount> = gson.fromJson(accountsJson, type)
        val idx = accounts.indexOfFirst { it.email == email }
        if (idx != -1) {
            accounts[idx] = accounts[idx].copy(accessToken = accessToken, refreshToken = refreshToken)
            prefs.edit().putString("google_accounts_v3", gson.toJson(accounts)).apply()
        }
    }

    /**
     * Fetches events for all enabled accounts in parallel, each with its own 10-second timeout.
     * Falls back to the supplied cached events on timeout, auth error, or network error,
     * and reports a typed status per account so the UI can show the right indicator.
     */
    suspend fun fetchAllAccountEvents(
        accounts: List<ConnectedCloudAccount>,
        cachedEvents: List<EventInfo>
    ): List<AccountFetchResult> = supervisorScope {
        accounts.filter { it.isPrimaryEnabled }.map { acc ->
            async {
                val cached = cachedEvents.filter { it.accountEmail == acc.email }
                try {
                    val events = withTimeoutOrNull(10_000L) {
                        if (acc.provider == CloudProvider.GOOGLE) {
                            googleScanner.fetchEventsForAccount(acc.email, emptyList())
                        } else {
                            val token = refreshOutlookToken(acc)
                            outlookScanner.fetchEventsForAccount(acc.email, token, emptyList())
                        }
                    }
                    if (events == null) {
                        AccountFetchResult(acc.email, cached, AccountSyncStatus.TIMEOUT)
                    } else {
                        AccountFetchResult(acc.email, events, AccountSyncStatus.OK)
                    }
                } catch (e: OutlookAuthException) {
                    Log.w("SyncRepository", "Auth error for ${acc.email}: ${e.message}")
                    AccountFetchResult(acc.email, cached, AccountSyncStatus.AUTH_ERROR)
                } catch (e: UserRecoverableAuthException) {
                    Log.w("SyncRepository", "Google auth error for ${acc.email}")
                    AccountFetchResult(acc.email, cached, AccountSyncStatus.AUTH_ERROR)
                } catch (e: GoogleAuthException) {
                    Log.w("SyncRepository", "Google auth exception for ${acc.email}")
                    AccountFetchResult(acc.email, cached, AccountSyncStatus.AUTH_ERROR)
                } catch (e: IOException) {
                    Log.w("SyncRepository", "Network error for ${acc.email}: ${e.message}")
                    AccountFetchResult(acc.email, cached, AccountSyncStatus.NETWORK_ERROR)
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Unexpected error for ${acc.email}", e)
                    AccountFetchResult(acc.email, cached, AccountSyncStatus.AUTH_ERROR)
                }
            }
        }.awaitAll()
    }

    /**
     * Reconciles the current alarm list against the latest cloud events:
     * - Cancels alarms for deleted or excluded events
     * - Updates alarm times when events are rescheduled
     * - Creates new alarms for upcoming events not yet tracked
     *
     * Returns (updatedAlarms, wereAlarmsChanged).
     */
    fun reconcileAlarms(
        allEvents: List<EventInfo>,
        currentAlarms: List<ScheduledAlarm>,
        excluded: List<ExcludedEvent>,
        syncedEmails: Set<String>
    ): Pair<List<ScheduledAlarm>, Boolean> {
        var changed = false
        val finalAlarms = currentAlarms.toMutableList()
        val now = System.currentTimeMillis()

        val iterator = finalAlarms.listIterator()
        while (iterator.hasNext()) {
            val alarm = iterator.next()
            if (alarm.time <= now || alarm.googleEventId == null) continue

            val seriesId = alarm.googleEventId.split("_")[0]
            if (excluded.any { it.id == alarm.googleEventId || it.id == seriesId }) {
                alarmScheduler.cancelAlarm(alarm.id)
                iterator.remove()
                changed = true
                continue
            }

            val event = allEvents.find { it.googleEventId == alarm.googleEventId }
            if (event == null) {
                if (syncedEmails.isNotEmpty()) {
                    alarmScheduler.cancelAlarm(alarm.id)
                    iterator.remove()
                    changed = true
                }
            } else {
                val targetTime = event.startTime - (5 * 60 * 1000)
                if (targetTime != alarm.time) {
                    alarmScheduler.scheduleAlarm(alarm.id, targetTime, event.title)
                    iterator.set(alarm.copy(
                        time = targetTime,
                        googleRecurrenceInfo = event.recurringEventId ?: if (event.isRecurring) "true" else null
                    ))
                    changed = true
                }
            }
        }

        allEvents.forEach { event ->
            val seriesId = event.recurringEventId ?: event.googleEventId?.split("_")?.get(0)
            val isExcluded = excluded.any { it.id == event.googleEventId || it.id == seriesId }
            if (!isExcluded && event.startTime > now && event.googleEventId != null) {
                if (finalAlarms.none { it.googleEventId == event.googleEventId }) {
                    val targetTime = event.startTime - (5 * 60 * 1000)
                    val id = alarmIdForEvent(event.googleEventId)
                    alarmScheduler.scheduleAlarm(id, targetTime, event.title)
                    finalAlarms.add(ScheduledAlarm(
                        id, targetTime, event.title,
                        googleEventId = event.googleEventId,
                        googleRecurrenceInfo = event.recurringEventId ?: if (event.isRecurring) "true" else null
                    ))
                    changed = true
                }
            }
        }

        return Pair(finalAlarms, changed)
    }
}
