package com.nen.alarmsynccalendar

enum class RecurrenceType { NONE, DAILY, WEEKLY, MONTHLY }
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
    val accountEmail: String? = null
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
    val recurrenceData: Int? = null
)

data class ConnectedCloudAccount(
    val email: String,
    val provider: CloudProvider,
    val isPrimaryEnabled: Boolean = true,
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
