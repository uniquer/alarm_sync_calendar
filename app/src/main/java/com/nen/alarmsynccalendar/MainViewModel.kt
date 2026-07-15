package com.nen.alarmsynccalendar

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nen.alarmsynccalendar.alarm.AlarmScheduler
import com.nen.alarmsynccalendar.sync.SyncRepository
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val activeAlarms = mutableStateListOf<ScheduledAlarm>()
    val cloudEvents = mutableStateListOf<EventInfo>()
    val connectedAccounts = mutableStateListOf<ConnectedCloudAccount>()
    val excludedEvents = mutableStateListOf<ExcludedEvent>()
    var isCloudSignedIn by mutableStateOf(false)
    var lastSyncTime by mutableStateOf(0L)
    var isSyncing by mutableStateOf(false)
    var appSettings by mutableStateOf(AppSettings())
        private set
    var showLocationPrompt by mutableStateOf(false)

    private val gson = Gson()
    private val repo = SyncRepository(app)
    val alarmScheduler = AlarmScheduler(app)

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        viewModelScope.launch {
            when (key) {
                "alarm_list" -> loadAlarms()
                "cloud_events_cache" -> loadCloudEventsCache()
                "google_accounts_v3" -> loadAccounts()
                "excluded_list" -> loadExcluded()
            }
        }
    }

    init {
        prefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        appSettings = AppSettings.load(app)
        loadAccounts()
        loadCloudEventsCache()
        checkCloudConnection()
        loadAlarms()
        loadExcluded()
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    fun refreshCloudEvents(isManual: Boolean = false) {
        viewModelScope.launch {
            val type = if (isManual) "Manual" else "App Refresh"
            val lastGoogleSync = prefs().getLong("last_google_sync", 0L)
            val now = System.currentTimeMillis()
            if (!isManual && now - lastGoogleSync < 10 * 60 * 1000L) {
                logSync("[$type] Skipped: synced recently")
                return@launch
            }

            isSyncing = true
            try {
                val results = repo.fetchAllAccountEvents(
                    connectedAccounts.toList(),
                    cloudEvents.toList()
                )

                // Update per-account sync status; reload from prefs first so we
                // pick up any Outlook token rotation that refreshOutlookToken persisted.
                loadAccounts()
                results.forEach { result ->
                    val idx = connectedAccounts.indexOfFirst { it.email == result.email }
                    if (idx != -1) {
                        connectedAccounts[idx] = connectedAccounts[idx].copy(syncStatus = result.status)
                    }
                }
                saveAccounts()

                val allEvents = repo.enrichWithTravelInfo(
                    results
                        .flatMap { it.events }
                        .distinctBy { "${it.title}|${it.startTime}" },
                    cloudEvents.toList()
                )

                val syncedEmails = results
                    .filter { it.status == AccountSyncStatus.OK }
                    .map { it.email }
                    .toSet()

                val oldAlarms = activeAlarms.toList()
                val (newAlarms, changed) = repo.reconcileAlarms(
                    allEvents,
                    oldAlarms,
                    excludedEvents.toList(),
                    syncedEmails
                )
                if (changed) {
                    activeAlarms.clear()
                    activeAlarms.addAll(newAlarms)
                    saveAlarms()
                }

                cloudEvents.clear()
                cloudEvents.addAll(allEvents)
                saveCloudEventsCache()

                results.forEach { r ->
                    val obfuscated = obfuscateEmail(r.email)
                    val updateCount = calculateUpdateCount(r.email, oldAlarms, newAlarms, allEvents, cloudEvents.toList())
                    logSync("[$type] $obfuscated overall:${r.events.size} update:$updateCount")
                }

                logSync("[$type] Done: ${syncedEmails.size}/${results.size} accounts OK, ${allEvents.size} events, alarms ${if (changed) "updated" else "unchanged"}")
            } finally {
                isSyncing = false
            }
        }
    }

    private fun obfuscateEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return email
        val username = parts[0]
        val domain = parts[1]
        val obfuscatedUser = if (username.length > 3) {
            username.substring(0, 3) + "..."
        } else {
            username + "..."
        }
        val domainName = domain.split(".")[0]
        val obfuscatedDomain = if (domainName.length > 2) {
            domainName.substring(0, 2)
        } else {
            domainName
        }
        return "$obfuscatedUser@$obfuscatedDomain"
    }

    private fun calculateUpdateCount(
        email: String,
        oldAlarms: List<ScheduledAlarm>,
        newAlarms: List<ScheduledAlarm>,
        allEvents: List<EventInfo>,
        cachedEvents: List<EventInfo>
    ): Int {
        var count = 0
        count += newAlarms.count { newAlarm ->
            oldAlarms.none { it.googleEventId == newAlarm.googleEventId } &&
            allEvents.any { it.googleEventId == newAlarm.googleEventId && it.accountEmail == email }
        }
        count += newAlarms.count { newAlarm ->
            val oldAlarm = oldAlarms.find { it.googleEventId == newAlarm.googleEventId }
            oldAlarm != null && (
                oldAlarm.time != newAlarm.time ||
                oldAlarm.meetingLink != newAlarm.meetingLink ||
                oldAlarm.location != newAlarm.location ||
                oldAlarm.distanceKm != newAlarm.distanceKm ||
                oldAlarm.travelTimeMinutes != newAlarm.travelTimeMinutes ||
                oldAlarm.noDrivingRoute != newAlarm.noDrivingRoute ||
                oldAlarm.eventStartTime != newAlarm.eventStartTime
            ) && allEvents.any { it.googleEventId == newAlarm.googleEventId && it.accountEmail == email }
        }
        count += oldAlarms.count { oldAlarm ->
            newAlarms.none { it.googleEventId == oldAlarm.googleEventId } &&
            (allEvents.any { it.googleEventId == oldAlarm.googleEventId && it.accountEmail == email } ||
             cachedEvents.any { it.googleEventId == oldAlarm.googleEventId && it.accountEmail == email })
        }
        return count
    }

    private fun logSync(message: String) {
        val prefs = getApplication<Application>().getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val existing = prefs.getString("history", "") ?: ""
        val ts = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        prefs.edit().putString("history", "[$ts] $message\n$existing".take(5000)).apply()
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun updateSettings(newSettings: AppSettings) {
        appSettings = newSettings
        newSettings.save(getApplication())
        // Re-enrich travel info and reschedule alarms with the new lead times.
        if (isCloudSignedIn) refreshCloudEvents(isManual = true)
    }

    // ── Account management ────────────────────────────────────────────────────

    fun addAccount(account: ConnectedCloudAccount) {
        connectedAccounts.removeAll { it.email == account.email }
        connectedAccounts.add(account)
        saveAccounts()
        isCloudSignedIn = true
        if (!appSettings.hasStartLocation) showLocationPrompt = true
        refreshCloudEvents(isManual = true)
    }

    fun disconnectAccount(email: String) {
        val acc = connectedAccounts.find { it.email == email } ?: return
        connectedAccounts.remove(acc)
        saveAccounts()
        isCloudSignedIn = connectedAccounts.isNotEmpty()

        val toRemove = activeAlarms.filter { alarm ->
            cloudEvents.any { it.googleEventId == alarm.googleEventId && it.accountEmail == email }
        }
        toRemove.forEach { alarmScheduler.cancelAlarm(it.id) }
        activeAlarms.removeAll(toRemove.toSet())
        saveAlarms()

        cloudEvents.removeAll { it.accountEmail == email }
        saveCloudEventsCache()
    }

    fun updatePrimaryEnable(email: String, enabled: Boolean) {
        val i = connectedAccounts.indexOfFirst { it.email == email }
        if (i != -1) {
            connectedAccounts[i] = connectedAccounts[i].copy(isPrimaryEnabled = enabled)
            saveAccounts()
            refreshCloudEvents(isManual = true)
        }
    }

    // ── Alarm toggle ──────────────────────────────────────────────────────────

    fun toggleEventAlarm(event: EventInfo, enabled: Boolean) {
        if (enabled) {
            val seriesId = event.recurringEventId ?: event.googleEventId?.split("_")?.get(0)
            excludedEvents.removeAll { it.id == event.googleEventId || (seriesId != null && it.id == seriesId) }
            saveExcluded()

            if (event.googleEventId != null && activeAlarms.none { it.googleEventId == event.googleEventId }) {
                val targetTime = repo.alarmTimeForEvent(event, appSettings)
                val id = repo.alarmIdForEvent(event.googleEventId)
                alarmScheduler.scheduleAlarm(id, targetTime, event.title, event.meetingLink, event.location, event.travelTimeMinutes, event.distanceKm, event.noDrivingRoute)
                activeAlarms.add(ScheduledAlarm(
                    id, targetTime, event.title,
                    googleEventId = event.googleEventId,
                    googleRecurrenceInfo = event.recurringEventId ?: if (event.isRecurring) "true" else null,
                    meetingLink = event.meetingLink,
                    location = event.location,
                    distanceKm = event.distanceKm,
                    travelTimeMinutes = event.travelTimeMinutes,
                    noDrivingRoute = event.noDrivingRoute,
                    eventStartTime = event.startTime
                ))
                saveAlarms()
            }
        } else {
            val existing = activeAlarms.find { it.googleEventId == event.googleEventId }
            if (existing != null) {
                alarmScheduler.cancelAlarm(existing.id)
                activeAlarms.remove(existing)
                saveAlarms()
            }
            if (event.googleEventId != null) {
                val isSeries = event.recurringEventId != null || event.isRecurring
                val rootId = event.recurringEventId ?: event.googleEventId.split("_")[0]
                excludedEvents.add(ExcludedEvent(
                    rootId, event.title, isSeries,
                    System.currentTimeMillis() + (90L * 24 * 60 * 60 * 1000)
                ))
                saveExcluded()
            }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    fun loadAlarms() {
        val j = prefs().getString("alarm_list", null) ?: return
        try {
            activeAlarms.clear()
            activeAlarms.addAll(gson.fromJson(j, object : TypeToken<List<ScheduledAlarm>>() {}.type))
        } catch (e: Exception) { /* corrupt prefs — leave list unchanged */ }
    }

    fun saveAlarms() {
        prefs().edit().putString("alarm_list", gson.toJson(activeAlarms.toList())).apply()
    }

    fun saveAccounts() {
        prefs().edit().putString("google_accounts_v3", gson.toJson(connectedAccounts.toList())).commit()
    }

    fun saveExcluded() {
        prefs().edit().putString("excluded_list", gson.toJson(excludedEvents.toList())).apply()
    }

    private fun checkCloudConnection() { isCloudSignedIn = connectedAccounts.isNotEmpty() }

    fun loadAccounts() {
        lastSyncTime = prefs().getLong("last_google_sync", 0L)
        val json = prefs().getString("google_accounts_v3", "[]")
        val list: List<ConnectedCloudAccount> = try {
            gson.fromJson(json, object : TypeToken<List<ConnectedCloudAccount>>() {}.type)
        } catch (e: Exception) { emptyList() }
        connectedAccounts.clear()
        connectedAccounts.addAll(list)
    }

    fun loadCloudEventsCache() {
        val json = prefs().getString("cloud_events_cache", "[]")
        val list: List<EventInfo> = try {
            gson.fromJson(json, object : TypeToken<List<EventInfo>>() {}.type)
        } catch (e: Exception) { emptyList() }
        cloudEvents.clear()
        cloudEvents.addAll(list)
    }

    private fun saveCloudEventsCache() {
        lastSyncTime = System.currentTimeMillis()
        prefs().edit()
            .putLong("last_google_sync", lastSyncTime)
            .putString("cloud_events_cache", gson.toJson(cloudEvents.toList()))
            .commit()
    }

    fun loadExcluded() {
        val j = prefs().getString("excluded_list", null) ?: return
        try {
            val list: List<ExcludedEvent> = gson.fromJson(j, object : TypeToken<List<ExcludedEvent>>() {}.type)
            val now = System.currentTimeMillis()
            excludedEvents.clear()
            excludedEvents.addAll(list.filter { it.expiryTime > now })
        } catch (e: Exception) { /* corrupt prefs — leave list unchanged */ }
    }

    private fun prefs() = getApplication<Application>().getSharedPreferences("alarms", Context.MODE_PRIVATE)

    override fun onCleared() {
        super.onCleared()
        prefs().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }
}
