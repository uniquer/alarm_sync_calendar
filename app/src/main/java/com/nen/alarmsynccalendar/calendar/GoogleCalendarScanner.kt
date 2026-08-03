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
                        val id = item.getString("id")
                        val summary = item.optString("summary", if (isPrimary) "Primary Calendar" else id)
                        list.add(GoogleCalendarInfo(id, summary, isPrimary))
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
            val calendarIdsToFetch = (listOf("primary") + selectedCalendarIds).distinct()
            val allEvents = calendarIdsToFetch.flatMap { calId ->
                try {
                    fetchEventsFromCalendar(token, calId, email)
                } catch (e: Exception) {
                    Log.w("CAL_DEBUG", "Error fetching calendar $calId for $email: ${e.message}")
                    emptyList()
                }
            }
            val now = System.currentTimeMillis()
            allEvents.filter { if (it.isAllDay) (it.startTime + 24 * 60 * 60 * 1000L) > now else (it.startTime - 5 * 60 * 1000L) > now }
                .groupBy { it.recurringEventId ?: it.googleEventId }
                .map { (_, instances) -> instances.sortedBy { it.startTime }.first() }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Error syncing $email: ${e.message}"); throw e }
    }

    private fun fetchEventsFromCalendar(token: String, calendarId: String, email: String): List<EventInfo> {
        val events = mutableListOf<EventInfo>()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val startCal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val start = sdf.format(startCal.time); val end = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 60) }.time)
        val encodedId = java.net.URLEncoder.encode(calendarId, "UTF-8")
        val baseUrl = "https://www.googleapis.com/calendar/v3/calendars/$encodedId/events?timeMin=$start&timeMax=$end&singleEvents=true&orderBy=startTime&conferenceDataVersion=1&maxResults=250"

        // Follow nextPageToken so busy calendars beyond one page aren't truncated.
        var pageToken: String? = null
        var pagesFetched = 0
        do {
            val url = URL(baseUrl + (pageToken?.let { "&pageToken=$it" } ?: ""))
            // Let IOException propagate — callers (SyncRepository) catch it and fall back to cache.
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            val responseCode = conn.responseCode
            if (responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            pagesFetched++
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("status") == "cancelled") continue
                val id = item.getString("id")
                val recurringId = item.optString("recurringEventId").takeIf { it.isNotBlank() }
                val startObj = item.optJSONObject("start")
                val isAllDay = startObj?.has("date") == true && !startObj.has("dateTime")
                val startStr = startObj?.optString("dateTime")?.takeIf { it.isNotBlank() }
                    ?: startObj?.optString("date")?.takeIf { it.isNotBlank() }
                    ?: ""
                if (startStr.isEmpty()) continue
                val startTs = parseIso(startStr, isAllDay)
                if (startTs == 0L) continue
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

                events.add(EventInfo(id.hashCode().toLong(), id, recurringId, recurringId != null || item.has("recurrence"), null, item.optString("summary", "No Title"), startTs, 0L, desc, org, email, meetingLink, location = MeetingUtils.extractPhysicalLocation(loc), isAllDay = isAllDay))
            }
            } else if (responseCode == 401 || responseCode == 403) {
                throw com.google.android.gms.auth.GoogleAuthException("Google Calendar Auth failed: HTTP $responseCode")
            } else {
                throw java.io.IOException("Google Calendar fetch failed: HTTP $responseCode")
            }
        } while (pageToken != null && pagesFetched < 5)
        return events
    }

    private fun parseIso(s: String, isAllDay: Boolean): Long {
        if (s.isEmpty()) return 0L
        if (isAllDay) {
            return try {
                java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) { 0L }
        }
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                // No offset supplied — assume device-local time
                java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e2: Exception) { 0L }
        }
    }
}
