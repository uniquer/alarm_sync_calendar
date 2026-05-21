package com.nen.alarmsynccalendar.calendar

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.GoogleAuthUtil
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nen.alarmsynccalendar.EventInfo

data class GoogleCalendarInfo(val id: String, val summary: String, val isPrimary: Boolean = false)

class GoogleCalendarScanner(private val context: Context) {
    suspend fun fetchAvailableCalendars(email: String): List<GoogleCalendarInfo> = withContext(Dispatchers.IO) {
        val scope = "oauth2:https://www.googleapis.com/auth/calendar.readonly"
        try {
            val token = GoogleAuthUtil.getToken(context, email, scope)
            val url = URL("https://www.googleapis.com/calendar/v3/users/me/calendarList")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val items = JSONObject(response).optJSONArray("items")
                if (items != null) {
                    val list = mutableListOf<GoogleCalendarInfo>()
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val isPrimary = item.optBoolean("primary", false)
                        if (isPrimary) list.add(GoogleCalendarInfo(item.getString("id"), item.optString("summary", "Primary"), true))
                    }
                    return@withContext list
                }
            }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Error fetching calendars: ${e.message}") }
        listOf(GoogleCalendarInfo("primary", "Primary Calendar", true))
    }

    suspend fun fetchEventsForAccount(email: String, selectedCalendarIds: List<String>): List<EventInfo> = withContext(Dispatchers.IO) {
        val scope = "oauth2:https://www.googleapis.com/auth/calendar.readonly"
        try {
            val token = GoogleAuthUtil.getToken(context, email, scope)
            val events = fetchEventsFromCalendar(token, "primary", email)
            val now = System.currentTimeMillis()
            events.filter { (it.startTime - 5 * 60 * 1000L) > now }.groupBy { it.recurringEventId ?: it.googleEventId }.map { (_, instances) -> instances.sortedBy { it.startTime }.first() }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Error syncing $email: ${e.message}"); throw e }
    }

    private fun fetchEventsFromCalendar(token: String, calendarId: String, email: String): List<EventInfo> {
        val events = mutableListOf<EventInfo>()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = sdf.format(Date()); val end = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 60) }.time)
        val encodedId = java.net.URLEncoder.encode(calendarId, "UTF-8")
        val url = URL("https://www.googleapis.com/calendar/v3/calendars/$encodedId/events?timeMin=$start&timeMax=$end&singleEvents=true&orderBy=startTime")
        // Let IOException propagate — callers (SyncRepository) catch it and fall back to cache.
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (conn.responseCode == 200) {
            val items = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("status") == "cancelled") continue
                val id = item.getString("id")
                val recurringId = item.optString("recurringEventId").takeIf { it.isNotBlank() }
                val startStr = item.optJSONObject("start")?.optString("dateTime") ?: item.optJSONObject("start")?.optString("date") ?: ""
                if (startStr.isEmpty()) continue
                val org = item.optJSONObject("organizer")?.optString("email") ?: "Unknown"
                events.add(EventInfo(id.hashCode().toLong(), id, recurringId, recurringId != null || item.has("recurrence"), null, item.optString("summary", "No Title"), parseIso(startStr), 0L, null, org, email))
            }
        }
        return events
    }

    private fun parseIso(s: String): Long {
        return try {
            val clean = if (s.contains("+")) s.substring(0, s.indexOf("+")) else if (s.endsWith("Z")) s.substring(0, s.length - 1) else s
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(clean)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
