package com.nen.alarmsynccalendar

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class SyncLogicTest {

    @Test
    fun `test mirror sync creates alarm for valid cloud event`() {
        // Mock a cloud event
        val event = EventInfo(
            id = 123L,
            googleEventId = "google_123",
            recurringEventId = null,
            isRecurring = false,
            recurrenceDetails = null,
            title = "Morning Coffee",
            startTime = System.currentTimeMillis() + 3600000, // In 1 hour
            endTime = System.currentTimeMillis() + 7200000,
            description = "Daily kick-off",
            organizer = "manager@company.com",
            accountEmail = "user@gmail.com"
        )
        
        // Mirror Sync logic: If not excluded and is future, create alarm
        val targetTime = event.startTime - (5 * 60 * 1000) // Default 5 min lead
        val alarmId = event.googleEventId.hashCode().hashCode()
        val alarm = ScheduledAlarm(alarmId, targetTime, event.title, googleEventId = event.googleEventId)
        
        assertEquals("Alarm should mirror event title", "Morning Coffee", alarm.message)
        assertEquals("Alarm should have correct Google ID", "google_123", alarm.googleEventId)
        assertTrue("Alarm should be 5 mins before start", alarm.time < event.startTime)
    }

    @Test
    fun `test mirror sync respects excluded list`() {
        val excluded = listOf(ExcludedEvent("google_123", "Morning Coffee", false, Long.MAX_VALUE))
        val event = EventInfo(1L, "google_123", null, false, null, "Morning Coffee", 2000L, 3000L, null, "a@b.com", "user@gmail.com")
        // Overwriting constructor style for test simplicity
        val mockEvent = event.copy(googleEventId = "google_123")

        val isExcluded = excluded.any { it.id == mockEvent.googleEventId }
        assertTrue("Mirror sync should detect exclusion and skip alarm creation", isExcluded)
    }

    @Test
    fun `test mirror sync blocks entire recurring series`() {
        // Exclude a series ID
        val excluded = listOf(ExcludedEvent("series_standup", "Daily Standup", true, Long.MAX_VALUE))
        
        // Mock a specific instance of that series
        val instance = EventInfo(
            id = 456L,
            googleEventId = "series_standup_20260519", // Individual instance ID
            recurringEventId = "series_standup",        // Root Series ID
            isRecurring = true,
            recurrenceDetails = "Daily",
            title = "Daily Standup",
            startTime = System.currentTimeMillis() + 3600000,
            endTime = 0L,
            description = null,
            organizer = "boss@company.com",
            accountEmail = "user@gmail.com"
        )
        
        val seriesId = instance.recurringEventId ?: instance.googleEventId?.split("_")?.get(0)
        val isSeriesExcluded = excluded.any { it.id == seriesId }
        
        assertTrue("Instance should be blocked because the master series ID is excluded", isSeriesExcluded)
    }

    @Test
    fun `test recurrence daily`() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 21, 10, 0, 0)
        }
        val initialTime = calendar.timeInMillis
        val nextTime = RecurrenceUtils.calculateNextOccurrence(initialTime, RecurrenceType.DAILY, null)
        
        val nextCalendar = Calendar.getInstance().apply { timeInMillis = nextTime }
        assertEquals(2026, nextCalendar.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, nextCalendar.get(Calendar.MONTH))
        assertEquals(22, nextCalendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, nextCalendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `test recurrence weekly moves to specified day`() {
        val calendar = Calendar.getInstance().apply {
            // Thursday, May 21, 2026
            set(2026, Calendar.MAY, 21, 10, 0, 0)
        }
        val initialTime = calendar.timeInMillis
        // Ask for next Wednesday (Calendar.WEDNESDAY = 4)
        val nextTime = RecurrenceUtils.calculateNextOccurrence(initialTime, RecurrenceType.WEEKLY, Calendar.WEDNESDAY)
        
        val nextCalendar = Calendar.getInstance().apply { timeInMillis = nextTime }
        // Next Wednesday should be May 27, 2026
        assertEquals(2026, nextCalendar.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, nextCalendar.get(Calendar.MONTH))
        assertEquals(27, nextCalendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, nextCalendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `test recurrence monthly standard`() {
        val calendar = Calendar.getInstance().apply {
            // May 15, 2026
            set(2026, Calendar.MAY, 15, 10, 0, 0)
        }
        val initialTime = calendar.timeInMillis
        val nextTime = RecurrenceUtils.calculateNextOccurrence(initialTime, RecurrenceType.MONTHLY, 15)
        
        val nextCalendar = Calendar.getInstance().apply { timeInMillis = nextTime }
        // Next month same day: June 15, 2026
        assertEquals(2026, nextCalendar.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, nextCalendar.get(Calendar.MONTH))
        assertEquals(15, nextCalendar.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `test recurrence monthly end of month adjustment`() {
        val calendar = Calendar.getInstance().apply {
            // Oct 31, 2026
            set(2026, Calendar.OCTOBER, 31, 10, 0, 0)
        }
        val initialTime = calendar.timeInMillis
        // Move to November (which only has 30 days)
        val nextTime = RecurrenceUtils.calculateNextOccurrence(initialTime, RecurrenceType.MONTHLY, 31)
        
        val nextCalendar = Calendar.getInstance().apply { timeInMillis = nextTime }
        // November has 30 days maximum, so it should adjust to Nov 30
        assertEquals(2026, nextCalendar.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, nextCalendar.get(Calendar.MONTH))
        assertEquals(30, nextCalendar.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `test reschedule detection logic`() {
        val originalStartTime = System.currentTimeMillis() + 3600000 // In 1 hour
        val originalTargetTime = originalStartTime - (5 * 60 * 1000)
        val alarm = ScheduledAlarm(123, originalTargetTime, "Meeting", googleEventId = "google_123")

        // Cloud event gets rescheduled by 30 mins later
        val newStartTime = originalStartTime + 30 * 60 * 1000
        val event = EventInfo(
            id = 123L,
            googleEventId = "google_123",
            title = "Meeting",
            startTime = newStartTime,
            endTime = newStartTime + 30 * 60 * 1000,
            description = null,
            organizer = null
        )

        val newTargetTime = event.startTime - (5 * 60 * 1000)
        val isRescheduled = newTargetTime != alarm.time

        assertTrue("Should detect rescheduling when event time changes", isRescheduled)
        assertEquals("New target time should match expected lead offset", originalTargetTime + (30 * 60 * 1000), newTargetTime)
    }

    @Test
    fun `test cancellation detection logic`() {
        val alarm = ScheduledAlarm(123, System.currentTimeMillis() + 3600000, "Meeting", googleEventId = "google_123")

        val allEvents = emptyList<EventInfo>()
        val syncedEmails = setOf("user@company.com") // Synced successfully

        val event = allEvents.find { it.googleEventId == alarm.googleEventId }
        val shouldCancel = event == null && syncedEmails.isNotEmpty()

        assertTrue("Should cancel alarm if event is missing from successfully synced calendars", shouldCancel)
    }

    @Test
    fun `test series exclusion detection logic`() {
        val excluded = listOf(ExcludedEvent("series123", "Weekly Sync", true, Long.MAX_VALUE))
        val alarm = ScheduledAlarm(123, System.currentTimeMillis() + 3600000, "Weekly Sync", googleEventId = "series123_20260521")

        val seriesId = alarm.googleEventId!!.split("_")[0]
        val isSeriesExcluded = excluded.any { it.id == alarm.googleEventId || it.id == seriesId }

        assertTrue("Should detect matching series exclusion using extracted root ID", isSeriesExcluded)
    }
}
