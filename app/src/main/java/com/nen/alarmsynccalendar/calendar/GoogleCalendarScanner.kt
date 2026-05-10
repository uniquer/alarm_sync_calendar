package com.nen.alarmsynccalendar.calendar

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.nen.alarmsynccalendar.BuildConfig

data class GoogleCalendarInfo(val id: String, val summary: String)

class GoogleCalendarScanner(private val context: Context) {
    
    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    suspend fun fetchAvailableCalendars(email: String): List<GoogleCalendarInfo> = withContext(Dispatchers.IO) {
        val scope = "oauth2:https://www.googleapis.com/auth/calendar.readonly"
        return@withContext try {
            val token = GoogleAuthUtil.getToken(context, email, scope)
            fetchCalendarList(token)
        } catch (e: Exception) {
            Log.e("CAL_DEBUG", "Error fetching calendars for $email: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchEventsForAccount(email: String, selectedCalendarIds: List<String>): List<EventInfo> = withContext(Dispatchers.IO) {
        Log.d("CAL_DEBUG", "Comprehensive fetch for: $email on ${selectedCalendarIds.size} calendars")
        
        val scope = "oauth2:https://www.googleapis.com/auth/calendar.readonly"
        
        return@withContext try {
            val token = GoogleAuthUtil.getToken(context, email, scope)
            
            val allEvents = mutableListOf<EventInfo>()
            selectedCalendarIds.forEach { calId ->
                val events = fetchEventsFromCalendar(token, calId, email)
                allEvents.addAll(events)
            }
            
            // Refine: Only keep upcoming instances for any series/event
            val now = System.currentTimeMillis()
            val refinedEvents = allEvents
                .filter { it.startTime > now }
                .groupBy { it.recurringEventId ?: it.googleEventId } 
                .map { (_, instances) ->
                    instances.sortedBy { it.startTime }.first()
                }

            refinedEvents
        } catch (e: Exception) {
            Log.e("CAL_DEBUG", "Error syncing $email: ${e.message}", e)
            throw e
        }
    }

    private fun fetchCalendarList(token: String): List<GoogleCalendarInfo> {
        val calendars = mutableListOf<GoogleCalendarInfo>()
        try {
            Log.d("CAL_DEBUG", "Fetching calendar list...")
            val url = URL("https://www.googleapis.com/calendar/v3/users/me/calendarList?minAccessRole=freeBusyReader")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                Log.d("CAL_DEBUG", "CalendarList JSON length: ${response.length}")
                val json = JSONObject(response)
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        calendars.add(GoogleCalendarInfo(item.getString("id"), item.optString("summary", "Unknown")))
                    }
                }
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("CAL_DEBUG", "CalendarList API Error ${conn.responseCode}: $error")
            }
        } catch (e: Exception) {
            Log.e("CAL_DEBUG", "CalendarList Network Error", e)
        }
        if (calendars.isEmpty()) {
            calendars.add(GoogleCalendarInfo("primary", "Primary Calendar"))
        }
        return calendars
    }

    private fun fetchEventsFromCalendar(token: String, calendarId: String, email: String): List<EventInfo> {
        val events = mutableListOf<EventInfo>()
        
        val rfcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        
        val now = Calendar.getInstance()
        val timeMin = rfcFormatter.format(now.time)
        
        val future = Calendar.getInstance()
        future.add(Calendar.DAY_OF_YEAR, 90)
        val timeMax = rfcFormatter.format(future.time)

        val encodedId = java.net.URLEncoder.encode(calendarId, "UTF-8")
        val urlString = "https://www.googleapis.com/calendar/v3/calendars/$encodedId/events" +
                "?timeMin=$timeMin&timeMax=$timeMax&singleEvents=true&orderBy=startTime"
        
        try {
            Log.d("CAL_DEBUG", "Fetching events for calendar: $calendarId")
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                Log.d("CAL_DEBUG", "Events response length for $calendarId: ${response.length}")
                val jsonResponse = JSONObject(response)
                val items = jsonResponse.optJSONArray("items")
                
                if (items != null) {
                    Log.d("CAL_DEBUG", "Parsed ${items.length()} raw items from $calendarId")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        if (item.optString("status") == "cancelled") continue

                        val id = item.getString("id")
                        val recurringId = item.optString("recurringEventId").takeIf { it.isNotBlank() }
                        val summary = item.optString("summary", "No Title")
                        
                        val isRecurring = recurringId != null || item.has("recurrence")
                        
                        val startObj = item.optJSONObject("start")
                        val endObj = item.optJSONObject("end")
                        
                        val startStr = startObj?.optString("dateTime") ?: startObj?.optString("date") ?: ""
                        val endStr = endObj?.optString("dateTime") ?: endObj?.optString("date") ?: ""
                        
                        if (startStr.isEmpty()) {
                            Log.w("CAL_DEBUG", "Skipping event $id: No start date/time")
                            continue
                        }

                        val organizerObj = item.optJSONObject("organizer") ?: item.optJSONObject("creator")
                        val organizer = organizerObj?.optString("displayName")?.takeIf { it.isNotBlank() }
                                ?: organizerObj?.optString("email")?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        events.add(EventInfo(
                            id = id.hashCode().toLong(),
                            googleEventId = id,
                            recurringEventId = recurringId,
                            isRecurring = isRecurring,
                            recurrenceDetails = if (isRecurring) "Recurring" else null,
                            title = summary,
                            startTime = parseRfc3339(startStr),
                            endTime = parseRfc3339(endStr),
                            description = item.optString("description"),
                            organizer = organizer,
                            accountEmail = email,
                            source = EventSource.CLOUD
                        ))
                    }
                    Log.d("CAL_DEBUG", "Valid events kept for $calendarId: ${events.size}")
                } else {
                    Log.w("CAL_DEBUG", "Events items array is null for $calendarId")
                }
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("CAL_DEBUG", "Event Fetch API Error ${conn.responseCode} for $calendarId: $error")
            }
        } catch (e: Exception) {
            Log.e("CAL_DEBUG", "Event Fetch Network Error for $calendarId", e)
        }
        return events
    }

    private fun parseRfc3339(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val cleanStr = if (dateStr.contains("+")) {
                dateStr.substring(0, dateStr.indexOf("+"))
            } else if (dateStr.endsWith("Z")) {
                dateStr.substring(0, dateStr.length - 1)
            } else {
                dateStr
            }
            
            val format = if (cleanStr.contains("T")) {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.US)
            }
            format.parse(cleanStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
