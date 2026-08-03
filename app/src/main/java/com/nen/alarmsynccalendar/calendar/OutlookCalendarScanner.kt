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
                        val isDefault = item.optBoolean("isDefaultCalendar", false)
                        val id = item.getString("id")
                        val name = item.optString("name", if (isDefault) "Default Calendar" else id)
                        list.add(GoogleCalendarInfo(id, name, isDefault))
                    }
                    return@withContext list
                }
            }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Outlook list error: ${e.message}") }
        listOf(GoogleCalendarInfo("primary", "Default Calendar", true))
    }

    suspend fun fetchEventsForAccount(email: String, token: String, selectedCalendarIds: List<String>): List<EventInfo> = withContext(Dispatchers.IO) {
        try {
            val pathsToFetch = (listOf("me/calendar") + selectedCalendarIds.map { "me/calendars/${java.net.URLEncoder.encode(it, "UTF-8")}" }).distinct()
            val allEvents = pathsToFetch.flatMap { path ->
                try {
                    fetchEventsFromCalendar(token, path, email)
                } catch (e: Exception) {
                    Log.w("CAL_DEBUG", "Error fetching Outlook path $path for $email: ${e.message}")
                    emptyList()
                }
            }
            val now = System.currentTimeMillis()
            allEvents.filter { if (it.isAllDay) (it.startTime + 24 * 60 * 60 * 1000L) > now else (it.startTime - 5 * 60 * 1000L) > now }
                .groupBy { it.recurringEventId ?: it.googleEventId }
                .map { (_, instances) -> instances.sortedBy { it.startTime }.first() }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Outlook events error: ${e.message}"); throw e }
    }

    private fun fetchEventsFromCalendar(token: String, calendarPath: String, email: String): List<EventInfo> {
        val events = mutableListOf<EventInfo>()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val startCal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val start = sdf.format(startCal.time); val end = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 60) }.time)

        // Graph defaults to ~10 events per page; request bigger pages and follow
        // nextLink so recurring occurrences past page 1 aren't silently dropped.
        var nextUrl: String? = "https://graph.microsoft.com/v1.0/$calendarPath/calendarView?startDateTime=$start&endDateTime=$end&\$top=250"
        var pagesFetched = 0
        while (nextUrl != null && pagesFetched < 5) {
            // Let IOException propagate — callers (SyncRepository) catch it and fall back to cache.
            val conn = URL(nextUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Prefer", "outlook.timezone=\"UTC\"")
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val value = json.optJSONArray("value")
                if (value != null) for (i in 0 until value.length()) {
                    val item = value.getJSONObject(i)
                    val id = item.getString("id")
                    val seriesId = item.optString("seriesMasterId").takeIf { it != "null" && it.isNotBlank() }
                    val isAllDay = item.optBoolean("isAllDay", false)
                    val startTs = parseIso(item.optJSONObject("start")?.optString("dateTime") ?: "", isAllDay)
                    if (startTs == 0L) continue
                    val org = item.optJSONObject("organizer")?.optJSONObject("emailAddress")?.optString("address") ?: "Unknown"

                    val desc = item.optJSONObject("body")?.optString("content")?.takeIf { it != "null" && it.isNotBlank() }
                    val loc = item.optJSONObject("location")?.optString("displayName")?.takeIf { it != "null" && it.isNotBlank() }

                    var meetingLink = item.optJSONObject("onlineMeeting")?.optString("joinUrl")?.takeIf { it != "null" && it.isNotBlank() }
                    if (meetingLink == null) {
                        meetingLink = MeetingUtils.extractMeetingLink(loc, desc)
                    }

                    events.add(EventInfo(id.hashCode().toLong(), id, seriesId, seriesId != null, null, item.optString("subject", "No Title"), startTs, 0L, desc, org, email, meetingLink, location = MeetingUtils.extractPhysicalLocation(loc), isAllDay = isAllDay))
                }
                nextUrl = json.optString("@odata.nextLink").takeIf { it.isNotBlank() }
                pagesFetched++
            } else if (responseCode == 401 || responseCode == 403) {
                throw OutlookAuthException("Outlook Calendar Auth failed: HTTP $responseCode")
            } else {
                throw java.io.IOException("Outlook Calendar fetch failed: HTTP $responseCode")
            }
        }
        return events
    }

    private fun parseIso(s: String, isAllDay: Boolean): Long {
        if (s.isEmpty()) return 0L
        return try {
            if (isAllDay && s.length >= 10) {
                java.time.LocalDate.parse(s.substring(0, 10)).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.substring(0, 19))?.time ?: 0L
            }
        } catch (e: Exception) { 0L }
    }
}
