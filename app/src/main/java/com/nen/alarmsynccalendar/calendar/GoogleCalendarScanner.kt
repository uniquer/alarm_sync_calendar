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
        val url = URL("https://www.googleapis.com/calendar/v3/calendars/$encodedId/events?timeMin=$start&timeMax=$end&singleEvents=true&orderBy=startTime&conferenceDataVersion=1")
        // Let IOException propagate — callers (SyncRepository) catch it and fall back to cache.
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val items = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("status") == "cancelled") continue
                val id = item.getString("id")
                val recurringId = item.optString("recurringEventId").takeIf { it.isNotBlank() }
                val startStr = item.optJSONObject("start")?.optString("dateTime") ?: item.optJSONObject("start")?.optString("date") ?: ""
                if (startStr.isEmpty()) continue
                val org = item.optJSONObject("organizer")?.optString("email") ?: "Unknown"
                
                val desc = item.optString("description").takeIf { it.isNotBlank() }
                val loc = item.optString("location").takeIf { it.isNotBlank() }
                
                var meetingLink = item.optString("hangoutLink").takeIf { it.isNotBlank() }
                if (meetingLink == null) {
                    val confData = item.optJSONObject("conferenceData")
                    val entryPoints = confData?.optJSONArray("entryPoints")
                    if (entryPoints != null) {
                        for (j in 0 until entryPoints.length()) {
                            val ep = entryPoints.getJSONObject(j)
                            if (ep.optString("entryPointType") == "video") {
                                meetingLink = ep.optString("uri").takeIf { it.isNotBlank() }
                                break
                            }
                        }
                    }
                }
                if (meetingLink == null) {
                    meetingLink = MeetingUtils.extractMeetingLink(loc, desc)
                }

                events.add(EventInfo(id.hashCode().toLong(), id, recurringId, recurringId != null || item.has("recurrence"), null, item.optString("summary", "No Title"), parseIso(startStr), 0L, desc, org, email, meetingLink, location = MeetingUtils.extractPhysicalLocation(loc)))
            }
        } else if (responseCode == 401 || responseCode == 403) {
            throw com.google.android.gms.auth.GoogleAuthException("Google Calendar Auth failed: HTTP $responseCode")
        } else {
            throw java.io.IOException("Google Calendar fetch failed: HTTP $responseCode")
        }
        return events
    }

    private fun parseIso(s: String): Long {
        // Google returns dateTime with the event's own offset (e.g. "...T09:00:00-04:00"
        // for a New York event). Honor it so cross-timezone events land at the right
        // local time instead of being read as device-local clock time.
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                // No offset supplied — assume device-local time
                java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e2: Exception) { 0L } // date-only (all-day) events stay excluded
        }
    }
}
