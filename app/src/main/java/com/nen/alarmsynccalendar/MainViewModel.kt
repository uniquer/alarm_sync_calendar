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
        loadAccounts()
        loadCloudEventsCache()
        checkCloudConnection()
        loadAlarms()
        loadExcluded()
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    fun refreshCloudEvents(isManual: Boolean = false) {
        viewModelScope.launch {
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

                val allEvents = results
                    .flatMap { it.events }
                    .distinctBy { "${it.title}|${it.startTime}" }

                val syncedEmails = results
                    .filter { it.status == AccountSyncStatus.OK }
                    .map { it.email }
                    .toSet()

                val (newAlarms, changed) = repo.reconcileAlarms(
                    allEvents,
                    activeAlarms.toList(),
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

            } finally {
                isSyncing = false
            }
        }
    }

    // ── Account management ────────────────────────────────────────────────────

    fun addAccount(account: ConnectedCloudAccount) {
        connectedAccounts.removeAll { it.email == account.email }
        connectedAccounts.add(account)
        saveAccounts()
        isCloudSignedIn = true
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
                val targetTime = event.startTime - (5 * 60 * 1000)
                val id = repo.alarmIdForEvent(event.googleEventId)
                alarmScheduler.scheduleAlarm(id, targetTime, event.title)
                activeAlarms.add(ScheduledAlarm(
                    id, targetTime, event.title,
                    googleEventId = event.googleEventId,
                    googleRecurrenceInfo = event.recurringEventId ?: if (event.isRecurring) "true" else null
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

    private fun loadAccounts() {
        lastSyncTime = prefs().getLong("last_google_sync", 0L)
        val json = prefs().getString("google_accounts_v3", "[]")
        val list: List<ConnectedCloudAccount> = try {
            gson.fromJson(json, object : TypeToken<List<ConnectedCloudAccount>>() {}.type)
        } catch (e: Exception) { emptyList() }
        connectedAccounts.clear()
        connectedAccounts.addAll(list)
    }

    private fun loadCloudEventsCache() {
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

    private fun loadExcluded() {
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
