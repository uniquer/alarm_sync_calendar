package com.nen.alarmsynccalendar.calendar

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OutlookCalendarScanner(private val context: Context) {

    suspend fun fetchAvailableCalendars(email: String, token: String): List<GoogleCalendarInfo> = withContext(Dispatchers.IO) {
        return@withContext fetchCalendarList(token)
    }

    suspend fun fetchEventsForAccount(email: String, token: String, selectedCalendarIds: List<String>): List<EventInfo> = withContext(Dispatchers.IO) {
        try {
            val allEvents = mutableListOf<EventInfo>()
            selectedCalendarIds.forEach { calId ->
                allEvents.addAll(fetchEventsFromCalendar(token, calId, email))
            }
            val now = System.currentTimeMillis()
            val refined = allEvents.groupBy { it.recurringEventId ?: it.googleEventId }
                .map { (_, instances) ->
                    instances.sortedBy { it.startTime }.firstOrNull { it.startTime > now } ?: instances.last()
                }
            refined
        } catch (e: Exception) {
            Log.e("CAL_DEBUG", "Error syncing Outlook $email: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Outlook Sync Error ($email): ${e.message}", Toast.LENGTH_LONG).show()
            }
            emptyList()
        }
    }

    private fun fetchCalendarList(token: String): List<GoogleCalendarInfo> {
        val calendars = mutableListOf<GoogleCalendarInfo>()
        try {
            val url = URL("https://graph.microsoft.com/v1.0/me/calendars")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val value = JSONObject(response).optJSONArray("value")
                if (value != null) {
                    for (i in 0 until value.length()) {
                        val item = value.getJSONObject(i)
                        calendars.add(GoogleCalendarInfo(item.getString("id"), item.optString("name", "Outlook Calendar")))
                    }
                }
            }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Outlook CalendarList Error", e) }
        return calendars
    }

    private fun fetchEventsFromCalendar(token: String, calendarId: String, email: String): List<EventInfo> {
        val events = mutableListOf<EventInfo>()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = sdf.format(Date()); val end = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 90) }.time)
        val urlString = "https://graph.microsoft.com/v1.0/me/calendars/$calendarId/calendarView?startDateTime=$start&endDateTime=$end"
        try {
            val url = URL(urlString); val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Prefer", "outlook.timezone=\"UTC\"")
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val value = JSONObject(response).optJSONArray("value")
                if (value != null) {
                    for (i in 0 until value.length()) {
                        val item = value.getJSONObject(i)
                        val id = item.getString("id")
                        val seriesId = item.optString("seriesMasterId").takeIf { it != "null" && it.isNotBlank() }
                        val summary = item.optString("subject", "No Title")
                        val startTs = parseIso(item.getJSONObject("start").getString("dateTime"))
                        val endTs = parseIso(item.getJSONObject("end").getString("dateTime"))
                        val organizer = item.optJSONObject("organizer")?.optJSONObject("emailAddress")?.optString("name")
                                ?: item.optJSONObject("organizer")?.optJSONObject("emailAddress")?.optString("address") ?: "Unknown"
                        events.add(EventInfo(id.hashCode().toLong(), id, seriesId, seriesId != null, if (seriesId != null) "Outlook Recurring" else null, summary, startTs, endTs, item.optString("bodyPreview"), organizer, email, EventSource.GOOGLE))
                    }
                }
            }
        } catch (e: Exception) { Log.e("CAL_DEBUG", "Outlook Fetch Error", e) }
        return events
    }

    private fun parseIso(dateStr: String): Long {
        return try { val clean = dateStr.substring(0, 19); SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(clean)?.time ?: 0L } catch (e: Exception) { 0L }
    }
}
