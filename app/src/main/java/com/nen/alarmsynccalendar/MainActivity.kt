package com.nen.alarmsynccalendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nen.alarmsynccalendar.alarm.AlarmScheduler
import com.nen.alarmsynccalendar.calendar.GoogleCalendarScanner
import com.nen.alarmsynccalendar.calendar.OutlookCalendarScanner
import com.nen.alarmsynccalendar.sync.SyncWorker
import com.nen.alarmsynccalendar.ui.theme.AlarmSyncCalendarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.openid.appauth.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var googleCalendarScanner: GoogleCalendarScanner
    private lateinit var outlookCalendarScanner: OutlookCalendarScanner
    
    private val activeAlarms = mutableStateListOf<ScheduledAlarm>()
    private val cloudEvents = mutableStateListOf<EventInfo>()
    private val connectedAccounts = mutableStateListOf<ConnectedCloudAccount>()
    private val excludedEvents = mutableStateListOf<ExcludedEvent>()
    private val gson = Gson()
    private var isCloudSignedIn by mutableStateOf(false)
    private var lastSyncTime by mutableStateOf(0L)
    private var isGlobalSyncing by mutableStateOf(false)

    private lateinit var authService: AuthorizationService

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).result
            if (account != null && account.email != null) {
                val cloudAcc = ConnectedCloudAccount(account.email!!, CloudProvider.GOOGLE)
                connectedAccounts.removeAll { it.email == account.email }
                connectedAccounts.add(cloudAcc)
                saveAccounts(); isCloudSignedIn = true
                refreshCloudEvents(true)
            }
        }
    }

    private val outlookSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val response = AuthorizationResponse.fromIntent(result.data!!)
            if (response != null) {
                isGlobalSyncing = true
                authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, _ ->
                    if (tokenResponse != null) {
                        val email = tokenResponse.idToken?.let { idToken ->
                             try {
                                 val parts = idToken.split(".")
                                 if (parts.size >= 2) {
                                     val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                                     val json = JSONObject(payload)
                                     json.optString("preferred_username", json.optString("email", "Outlook User"))
                                 } else "Outlook User"
                             } catch (e: Exception) { "Outlook User" }
                        } ?: "Outlook User"
                        
                        val cloudAcc = ConnectedCloudAccount(email, CloudProvider.OUTLOOK, true, tokenResponse.accessToken, tokenResponse.refreshToken)
                        connectedAccounts.removeAll { it.email == email }
                        connectedAccounts.add(cloudAcc)
                        saveAccounts(); isCloudSignedIn = true
                        refreshCloudEvents(true)
                    } else { isGlobalSyncing = false }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmScheduler = AlarmScheduler(this)
        googleCalendarScanner = GoogleCalendarScanner(this); outlookCalendarScanner = OutlookCalendarScanner(this)
        authService = AuthorizationService(this)
        
        loadAccounts(); loadCloudEventsCache(); checkCloudConnection()
        loadAlarms(); loadExcluded(); scheduleSync(); checkBatteryOptimization()

        val permissions = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent { 
            AlarmSyncCalendarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box {
                        MainScreen(
                            alarmScheduler = alarmScheduler,
                            context = this@MainActivity,
                            activeAlarms = activeAlarms,
                            cloudEvents = cloudEvents,
                            isCloudSignedIn = isCloudSignedIn,
                            connectedAccounts = connectedAccounts,
                            lastSyncTime = lastSyncTime,
                            onGoogleSignIn = { signInGoogle() },
                            onOutlookSignIn = { signInOutlook() },
                            onDisconnectAccount = { disconnectAccount(it) },
                            onTogglePrimary = { email, enabled -> updatePrimaryEnable(email, enabled) },
                            onManualSync = { refreshCloudEvents(true) },
                            onSave = { saveAlarms() },
                            excludedEvents = excludedEvents,
                            onRestoreExcluded = { excludedEvents.remove(it); saveExcluded(); refreshCloudEvents(true) },
                            onSaveExcluded = { saveExcluded() },
                            onToggleAlarm = { event, enabled -> toggleEventAlarm(event, enabled) },
                            isSyncing = isGlobalSyncing
                        )
                        
                        if (isGlobalSyncing && cloudEvents.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("Syncing...", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun toggleEventAlarm(event: EventInfo, enabled: Boolean) {
        if (enabled) {
            // Remove from excluded if it was there
            val seriesId = event.recurringEventId ?: event.googleEventId?.split("_")?.get(0)
            excludedEvents.removeAll { it.id == event.googleEventId || (seriesId != null && it.id == seriesId) }
            saveExcluded()
            
            val existing = activeAlarms.find { it.googleEventId == event.googleEventId }
            if (existing == null) {
                val tm = event.startTime - (5 * 60 * 1000)
                val id = (event.googleEventId.hashCode() + System.currentTimeMillis().toInt()).hashCode()
                alarmScheduler.scheduleAlarm(id, tm, event.title)
                activeAlarms.add(ScheduledAlarm(id, tm, event.title, googleEventId = event.googleEventId, googleRecurrenceInfo = event.recurringEventId ?: if (event.isRecurring) "true" else null))
                saveAlarms()
                Toast.makeText(this, "Alarm set!", Toast.LENGTH_SHORT).show()
            }
        } else {
            val existing = activeAlarms.find { it.googleEventId == event.googleEventId }
            if (existing != null) {
                alarmScheduler.cancelAlarm(existing.id)
                activeAlarms.remove(existing)
                saveAlarms()
                Toast.makeText(this, "Alarm removed", Toast.LENGTH_SHORT).show()
            }
            if (event.googleEventId != null) {
                val isSeries = event.recurringEventId != null || event.isRecurring
                val rootId = event.recurringEventId ?: event.googleEventId.split("_")[0]
                excludedEvents.add(ExcludedEvent(rootId, event.title, isSeries, System.currentTimeMillis() + (90L * 24 * 60 * 60 * 1000)))
                saveExcluded()
            }
        }
    }

    private fun loadAccounts() {
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        lastSyncTime = prefs.getLong("last_google_sync", 0L)
        val json = prefs.getString("google_accounts_v3", "[]")
        val list: List<ConnectedCloudAccount> = try { gson.fromJson(json, object : TypeToken<List<ConnectedCloudAccount>>() {}.type) } catch (e: Exception) { emptyList() }
        connectedAccounts.clear(); connectedAccounts.addAll(list)
    }

    private fun saveAccounts() { getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putString("google_accounts_v3", gson.toJson(connectedAccounts.toList())).commit() }
    private fun checkCloudConnection() { isCloudSignedIn = connectedAccounts.isNotEmpty() }

    private fun signInGoogle() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/calendar.readonly")).build()
        val client = GoogleSignIn.getClient(this, options)
        client.signOut().addOnCompleteListener { googleSignInLauncher.launch(client.signInIntent) }
    }

    private fun signInOutlook() {
        val config = AuthorizationServiceConfiguration(Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"), Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"))
        val req = AuthorizationRequest.Builder(config, "acbc12d9-d41d-4df2-8517-57bdfdd3b0df", ResponseTypeValues.CODE, Uri.parse("msauth://com.nen.alarmsynccalendar/1NqMWNmdbXBPmEnKVGhIDOnHqaA%3D")).setScopes("openid", "profile", "email", "offline_access", "Calendars.Read").build()
        outlookSignInLauncher.launch(authService.getAuthorizationRequestIntent(req))
    }

    private fun disconnectAccount(email: String) {
        val acc = connectedAccounts.find { it.email == email } ?: return
        connectedAccounts.remove(acc); saveAccounts(); isCloudSignedIn = connectedAccounts.isNotEmpty()
        
        val alarmsToRemove = activeAlarms.filter { alarm ->
            cloudEvents.any { it.googleEventId == alarm.googleEventId && it.accountEmail == email }
        }
        alarmsToRemove.forEach { alarmScheduler.cancelAlarm(it.id) }
        activeAlarms.removeAll(alarmsToRemove)
        saveAlarms()
        
        cloudEvents.removeAll { it.accountEmail == email }
        saveCloudEventsCache()

        if (acc.provider == CloudProvider.GOOGLE) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(this, gso).revokeAccess().addOnCompleteListener { GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener { refreshCloudEvents() } }
        } else refreshCloudEvents()
    }

    private fun updatePrimaryEnable(email: String, enabled: Boolean) {
        val i = connectedAccounts.indexOfFirst { it.email == email }; if (i != -1) { connectedAccounts[i] = connectedAccounts[i].copy(isPrimaryEnabled = enabled); saveAccounts(); refreshCloudEvents(true) }
    }

    private suspend fun refreshOutlookToken(acc: ConnectedCloudAccount): String? = withContext(Dispatchers.IO) {
        if (acc.refreshToken == null) return@withContext null
        try {
            val url = java.net.URL("https://login.microsoftonline.com/common/oauth2/v2.0/token")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val postData = "client_id=acbc12d9-d41d-4df2-8517-57bdfdd3b0df&grant_type=refresh_token&refresh_token=${acc.refreshToken}"
            conn.outputStream.write(postData.toByteArray())
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val newAccess = json.getString("access_token")
                acc.accessToken = newAccess
                json.optString("refresh_token", null)?.let { acc.refreshToken = it }
                withContext(Dispatchers.Main) { saveAccounts() }
                return@withContext newAccess
            }
        } catch (e: Exception) {}
        null
    }

    private fun refreshCloudEvents(isManual: Boolean = false) {
        lifecycleScope.launch {
            isGlobalSyncing = true
            try {
                val success = withTimeoutOrNull(10000) {
                    refreshCloudEventsInternal(isManual)
                    true
                }
                if (success == null && isManual) Toast.makeText(this@MainActivity, "Sync timed out.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
            } finally {
                isGlobalSyncing = false
            }
        }
    }

    private suspend fun refreshCloudEventsInternal(isManual: Boolean = false) {
        val all = mutableListOf<EventInfo>()
        connectedAccounts.toList().forEach { acc ->
            if (acc.isPrimaryEnabled) {
                try {
                    val token = if (acc.provider == CloudProvider.GOOGLE) {
                        withContext(Dispatchers.IO) { com.google.android.gms.auth.GoogleAuthUtil.getToken(this@MainActivity, acc.email, "oauth2:https://www.googleapis.com/auth/calendar.readonly") }
                    } else { refreshOutlookToken(acc) ?: acc.accessToken ?: "" }
                    
                    val events = if (acc.provider == CloudProvider.GOOGLE) googleCalendarScanner.fetchEventsForAccount(acc.email, emptyList()) else outlookCalendarScanner.fetchEventsForAccount(acc.email, token, emptyList())
                    all.addAll(events)
                } catch (e: Exception) {
                    all.addAll(cloudEvents.filter { it.accountEmail == acc.email })
                }
            }
        }
        
        val deduplicated = all.distinctBy { "${it.title}|${it.startTime}" }
        withContext(Dispatchers.Main) {
            cloudEvents.clear(); cloudEvents.addAll(deduplicated); saveCloudEventsCache()
            val req = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(this@MainActivity).enqueueUniqueWork("ImmediateSync", ExistingWorkPolicy.REPLACE, req)
        }
    }

    private fun loadCloudEventsCache() {
        val json = getSharedPreferences("alarms", Context.MODE_PRIVATE).getString("cloud_events_cache", "[]")
        val list: List<EventInfo> = try { gson.fromJson(json, object : TypeToken<List<EventInfo>>() {}.type) } catch (e: Exception) { emptyList() }
        cloudEvents.clear(); cloudEvents.addAll(list)
    }

    private fun saveCloudEventsCache() {
        lastSyncTime = System.currentTimeMillis()
        getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putLong("last_google_sync", lastSyncTime).putString("cloud_events_cache", gson.toJson(cloudEvents.toList())).commit()
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "alarm_list") loadAlarms() }

    override fun onResume() { 
        super.onResume()
        getSharedPreferences("alarms", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        loadAlarms()
        if (isCloudSignedIn && (System.currentTimeMillis() - lastSyncTime > 15 * 60 * 1000)) refreshCloudEvents() 
    }
    
    override fun onPause() { super.onPause(); getSharedPreferences("alarms", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener) }
    override fun onDestroy() { super.onDestroy(); authService.dispose() }

    private fun scheduleSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("CalendarSync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun saveAlarms() { getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putString("alarm_list", gson.toJson(activeAlarms.toList())).apply() }
    private fun loadAlarms() { val j = getSharedPreferences("alarms", Context.MODE_PRIVATE).getString("alarm_list", null); if (j != null) try { activeAlarms.clear(); activeAlarms.addAll(gson.fromJson(j, object : TypeToken<List<ScheduledAlarm>>() {}.type)) } catch (e: Exception) {} }

    private fun saveExcluded() { getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putString("excluded_list", gson.toJson(excludedEvents.toList())).apply() }
    private fun loadExcluded() {
        val j = getSharedPreferences("alarms", Context.MODE_PRIVATE).getString("excluded_list", null)
        if (j != null) try {
            val list: List<ExcludedEvent> = gson.fromJson(j, object : TypeToken<List<ExcludedEvent>>() {}.type)
            val now = System.currentTimeMillis()
            excludedEvents.clear(); excludedEvents.addAll(list.filter { it.expiryTime > now })
        } catch (e: Exception) {}
    }

    fun openSettings() { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }) }
    fun openOEMSettings() {
        val m = android.os.Build.MANUFACTURER.lowercase()
        try {
            val i = Intent()
            if (m.contains("xiaomi")) i.component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            else if (m.contains("oppo") || m.contains("realme")) i.component = android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            else { openSettings(); return }
            startActivity(i)
        } catch (e: Exception) { openSettings() }
    }
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }) } catch (e: Exception) {}
            }
        }
    }
}
