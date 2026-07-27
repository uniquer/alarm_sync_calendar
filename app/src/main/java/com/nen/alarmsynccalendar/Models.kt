package com.nen.alarmsynccalendar

enum class RecurrenceType { NONE, DAILY, WEEKLY, MONTHLY }

// Beyond this driving distance travel time is unrealistic for a same-day drive;
// the alarm is set 24 hours before the event instead so there is time to plan.
const val LONG_TRIP_THRESHOLD_KM = 1000.0
enum class CloudProvider { GOOGLE, OUTLOOK }
enum class AccountSyncStatus { OK, AUTH_ERROR, TIMEOUT, NETWORK_ERROR }

data class EventInfo(
    val id: Long,
    val googleEventId: String? = null,
    val recurringEventId: String? = null,
    val isRecurring: Boolean = false,
    val recurrenceDetails: String? = null,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String?,
    val organizer: String?,
    val accountEmail: String? = null,
    val meetingLink: String? = null,
    // Physical address from the calendar event (null for online-only meetings)
    val location: String? = null,
    val distanceKm: Double? = null,
    val travelTimeMinutes: Int? = null,
    // True when the Distance Matrix API found no driving route (e.g. overseas) —
    // treated like a long trip: alarm 24hrs before, no travel time shown.
    val noDrivingRoute: Boolean? = null
)

data class ScheduledAlarm(
    val id: Int,
    val time: Long,
    val message: String,
    val calendarEventId: Long? = null,
    val googleEventId: String? = null,
    val googleRecurrenceInfo: String? = null,
    val sourceRuleId: Int? = null,
    val manualLeadTimeMinutes: Int? = null,
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val recurrenceData: Int? = null,
    val meetingLink: String? = null,
    val location: String? = null,
    val distanceKm: Double? = null,
    val travelTimeMinutes: Int? = null,
    val noDrivingRoute: Boolean? = null,
    // Start time of the linked calendar event, used to show how far ahead the alarm fires
    val eventStartTime: Long? = null
)

data class ConnectedCloudAccount(
    val email: String,
    val provider: CloudProvider,
    val isPrimaryEnabled: Boolean = true,
    val selectedSecondaryCalendarIds: List<String> = emptyList(),
    var accessToken: String? = null,
    var refreshToken: String? = null,
    var isExpanded: Boolean = false,
    // nullable for JSON back-compat with older stored data; null is treated as OK
    val syncStatus: AccountSyncStatus? = null
)

data class ExcludedEvent(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val expiryTime: Long
)

data class AppSettings(
    // Lead time before online (link-only) events
    val onlineLeadMinutes: Int = 5,
    // Extra prep buffer added on top of travel time for in-person events
    val offlineBufferMinutes: Int = 15,
    val startLocationName: String? = null,
    val startLocationLat: Double? = null,
    val startLocationLng: Double? = null,
    val enableSecondaryCalendars: Boolean = false
) {
    val hasStartLocation: Boolean get() = startLocationLat != null && startLocationLng != null

    fun save(context: android.content.Context) {
        val editor = context.getSharedPreferences("alarms", android.content.Context.MODE_PRIVATE).edit()
            .putInt("online_lead_minutes", onlineLeadMinutes)
            .putInt("offline_buffer_minutes", offlineBufferMinutes)
            .putBoolean("enable_secondary_calendars", enableSecondaryCalendars)
        if (startLocationName != null) editor.putString("start_location_name", startLocationName)
        else editor.remove("start_location_name")
        if (startLocationLat != null && startLocationLng != null) {
            editor.putLong("start_location_lat", startLocationLat.toRawBits())
            editor.putLong("start_location_lng", startLocationLng.toRawBits())
        } else {
            editor.remove("start_location_lat").remove("start_location_lng")
        }
        editor.apply()
    }

    companion object {
        fun load(context: android.content.Context): AppSettings {
            val p = context.getSharedPreferences("alarms", android.content.Context.MODE_PRIVATE)
            return AppSettings(
                onlineLeadMinutes = p.getInt("online_lead_minutes", 5),
                offlineBufferMinutes = p.getInt("offline_buffer_minutes", 15),
                startLocationName = p.getString("start_location_name", null),
                startLocationLat = if (p.contains("start_location_lat")) Double.fromBits(p.getLong("start_location_lat", 0L)) else null,
                startLocationLng = if (p.contains("start_location_lng")) Double.fromBits(p.getLong("start_location_lng", 0L)) else null,
                enableSecondaryCalendars = p.getBoolean("enable_secondary_calendars", false)
            )
        }
    }
}
