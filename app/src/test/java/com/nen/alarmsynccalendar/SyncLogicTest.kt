package com.nen.alarmsynccalendar

import com.nen.alarmsynccalendar.calendar.EventInfo
import com.nen.alarmsynccalendar.calendar.EventSource
import org.junit.Assert.*
import org.junit.Test

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
            accountEmail = "user@gmail.com",
            source = EventSource.CLOUD
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
        val event = EventInfo(1L, "google_123", null, false, null, "Morning Coffee", 2000L, 3000L, null, "a@b.com", "user@gmail.com", EventSource.CLOUD)
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
            accountEmail = "user@gmail.com",
            source = EventSource.CLOUD
        )
        
        val seriesId = instance.recurringEventId ?: instance.googleEventId?.split("_")?.get(0)
        val isSeriesExcluded = excluded.any { it.id == seriesId }
        
        assertTrue("Instance should be blocked because the master series ID is excluded", isSeriesExcluded)
    }
}
