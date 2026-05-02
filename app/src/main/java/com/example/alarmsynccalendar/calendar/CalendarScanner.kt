package com.example.alarmsynccalendar.calendar

import android.content.Context
import android.provider.CalendarContract

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val isPrimary: Boolean
)

data class EventInfo(
    val id: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String?,
    val organizer: String?
)

class CalendarScanner(private val context: Context) {
    fun getLocalCalendars(): List<CalendarInfo> {
        val calendars = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS
        )

        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
                val nameIndex = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountNameIndex = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val accountTypeIndex = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val primaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val visibleIndex = it.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                val syncIndex = it.getColumnIndex(CalendarContract.Calendars.SYNC_EVENTS)

                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val displayName = it.getString(nameIndex) ?: "Unknown"
                    val accountName = it.getString(accountNameIndex) ?: "Unknown"
                    val accountType = it.getString(accountTypeIndex) ?: "Unknown"
                    val isPrimary = it.getInt(primaryIndex) == 1
                    val isVisible = it.getInt(visibleIndex) == 1
                    val isSynced = it.getInt(syncIndex) == 1
                    
                    // We log this to help debugging
                    android.util.Log.d("CalendarScanner", "Found Calendar: $displayName, Visible: $isVisible, Synced: $isSynced")
                    
                    calendars.add(CalendarInfo(id, displayName, accountName, accountType, isPrimary))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CalendarScanner", "Error querying calendars", e)
        }
        return calendars
    }

    fun getEventsForCalendar(calendarId: Long): List<EventInfo> {
        return getEventsForRange(calendarId, System.currentTimeMillis(), System.currentTimeMillis() + 90L * 24 * 60 * 60 * 1000)
    }

    fun getEventsForNextThreeMonths(): List<EventInfo> {
        val allEvents = mutableListOf<EventInfo>()
        val calendars = getLocalCalendars()
        val endTime = System.currentTimeMillis() + 90L * 24 * 60 * 60 * 1000

        calendars.forEach { cal ->
            allEvents.addAll(getEventsForRange(cal.id, System.currentTimeMillis(), endTime))
        }
        return allEvents.distinctBy { it.id }
    }

    private fun getEventsForRange(calendarId: Long, startMillis: Long, endMillis: Long): List<EventInfo> {
        val events = mutableListOf<EventInfo>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.SELF_ATTENDEE_STATUS
        )

        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.TITLE} IS NOT NULL AND ${CalendarContract.Events.TITLE} != ''"
        val selectionArgs = arrayOf(calendarId.toString(), startMillis.toString(), endMillis.toString())

        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = it.getColumnIndex(CalendarContract.Events.DTEND)
                val descIdx = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val orgIdx = it.getColumnIndex(CalendarContract.Events.ORGANIZER)
                val statusIdx = it.getColumnIndex(CalendarContract.Events.STATUS)
                val attendeeStatusIdx = it.getColumnIndex(CalendarContract.Events.SELF_ATTENDEE_STATUS)

                while (it.moveToNext()) {
                    val status = it.getInt(statusIdx)
                    val attendeeStatus = it.getInt(attendeeStatusIdx)

                    if (status == CalendarContract.Events.STATUS_CANCELED || 
                        attendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) {
                        continue
                    }

                    val id = it.getLong(idIdx)
                    val title = it.getString(titleIdx) ?: ""
                    val start = it.getLong(startIdx)
                    val end = it.getLong(endIdx)
                    val desc = it.getString(descIdx)
                    val organizer = it.getString(orgIdx)

                    events.add(EventInfo(id, title, start, end, desc, organizer))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CalendarScanner", "Error querying events", e)
        }
        return events
    }
    }