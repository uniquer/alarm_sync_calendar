package com.nen.alarmsynccalendar.calendar

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nen.alarmsynccalendar.EventInfo
import com.nen.alarmsynccalendar.sync.OutlookAuthException

class OutlookCalendarScanner(private val context: Context) {
    suspend fun fetchAvailableCalendars(email: String, token: String): List<GoogleCalendarInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://graph.microsoft.com/v1.0/me/calendars")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val value = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("value")
                if (value != null) {
                    val list = mutableListOf<GoogleCalendarInfo>()
                    for (i in 0 until value.length()) {
                        val item = value.getJSONObject(i)
                        if (item.optBoolean("isDefaultCalendar", false)) {
                            list.add(GoogleCalendarInfo(item.getString("id"), item.optString("name", "Default"), true))
                        }
                    }
                    return@withContext list
                }
            }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Outlook list error: ${e.message}") }
        listOf(GoogleCalendarInfo("primary", "Default Calendar", true))
    }

    suspend fun fetchEventsForAccount(email: String, token: String, selectedCalendarIds: List<String>): List<EventInfo> = withContext(Dispatchers.IO) {
        try {
            val events = fetchEventsFromCalendar(token, "me/calendar", email)
            val now = System.currentTimeMillis()
            events.filter { (it.startTime - 5 * 60 * 1000L) > now }.groupBy { it.recurringEventId ?: it.googleEventId }.map { (_, instances) -> instances.sortedBy { it.startTime }.first() }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Outlook events error: ${e.message}"); throw e }
    }

    private fun fetchEventsFromCalendar(token: String, calendarPath: String, email: String): List<EventInfo> {
        val events = mutableListOf<EventInfo>()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = sdf.format(Date()); val end = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 60) }.time)
        val url = URL("https://graph.microsoft.com/v1.0/$calendarPath/calendarView?startDateTime=$start&endDateTime=$end")
        // Let IOException propagate — callers (SyncRepository) catch it and fall back to cache.
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Prefer", "outlook.timezone=\"UTC\"")
        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val value = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("value")
            if (value != null) for (i in 0 until value.length()) {
                val item = value.getJSONObject(i)
                val id = item.getString("id")
                val seriesId = item.optString("seriesMasterId").takeIf { it != "null" && it.isNotBlank() }
                val startTs = parseIso(item.getJSONObject("start").getString("dateTime"))
                val org = item.optJSONObject("organizer")?.optJSONObject("emailAddress")?.optString("address") ?: "Unknown"
                
                val desc = item.optJSONObject("body")?.optString("content")?.takeIf { it != "null" && it.isNotBlank() }
                val loc = item.optJSONObject("location")?.optString("displayName")?.takeIf { it != "null" && it.isNotBlank() }
                
                var meetingLink = item.optJSONObject("onlineMeeting")?.optString("joinUrl")?.takeIf { it != "null" && it.isNotBlank() }
                if (meetingLink == null) {
                    meetingLink = MeetingUtils.extractMeetingLink(loc, desc)
                }

                events.add(EventInfo(id.hashCode().toLong(), id, seriesId, seriesId != null, null, item.optString("subject", "No Title"), startTs, 0L, desc, org, email, meetingLink))
            }
        } else if (responseCode == 401 || responseCode == 403) {
            throw OutlookAuthException("Outlook Calendar Auth failed: HTTP $responseCode")
        } else {
            throw java.io.IOException("Outlook Calendar fetch failed: HTTP $responseCode")
        }
        return events
    }

    private fun parseIso(s: String): Long {
        return try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.substring(0, 19))?.time ?: 0L } catch (e: Exception) { 0L }
    }
}
