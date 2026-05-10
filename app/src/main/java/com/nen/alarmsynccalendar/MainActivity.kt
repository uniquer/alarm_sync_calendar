package com.nen.alarmsynccalendar

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nen.alarmsynccalendar.alarm.AlarmScheduler
import com.nen.alarmsynccalendar.calendar.CalendarScanner
import com.nen.alarmsynccalendar.calendar.GoogleCalendarScanner
import com.nen.alarmsynccalendar.calendar.OutlookCalendarScanner
import com.nen.alarmsynccalendar.calendar.EventSource
import com.nen.alarmsynccalendar.calendar.EventInfo
import com.nen.alarmsynccalendar.ui.theme.AlarmSyncCalendarTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject
import java.util.Base64

import androidx.work.*
import com.nen.alarmsynccalendar.sync.SyncWorker
import java.util.concurrent.TimeUnit
import androidx.lifecycle.lifecycleScope
import net.openid.appauth.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RecurrenceType { NONE, DAILY, WEEKLY, MONTHLY }
enum class CloudProvider { GOOGLE, OUTLOOK }

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

data class AutoScheduleRule(
    val id: Int,
    val organizerQuery: String,
    val leadTimeMinutes: Int,
    val isEnabled: Boolean = true
)

data class ConnectedCloudAccount(
    val email: String,
    val provider: CloudProvider,
    val selectedCalendars: List<String> = emptyList(),
    var accessToken: String? = null,
    var refreshToken: String? = null,
    val isExpandedInUi: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var calendarScanner: CalendarScanner
    private lateinit var googleCalendarScanner: GoogleCalendarScanner
    private lateinit var outlookCalendarScanner: OutlookCalendarScanner
    
    private val activeAlarms = mutableStateListOf<ScheduledAlarm>()
    private val activeRules = mutableStateListOf<AutoScheduleRule>()
    private val cloudEvents = mutableStateListOf<EventInfo>()
    private val connectedAccounts = mutableStateListOf<ConnectedCloudAccount>()
    private val availableCalendarsMap = mutableStateMapOf<String, List<com.nen.alarmsynccalendar.calendar.GoogleCalendarInfo>>()
    private val gson = Gson()
    private var isCloudSignedIn by mutableStateOf(false)
    private var lastSyncTime by mutableStateOf(0L)

    private lateinit var authService: AuthorizationService

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null && account.email != null) {
                if (connectedAccounts.none { it.email == account.email }) {
                    connectedAccounts.add(ConnectedCloudAccount(account.email!!, CloudProvider.GOOGLE))
                    saveAccounts(); isCloudSignedIn = true; refreshCloudEvents(true); fetchAvailableCalendars()
                }
                Toast.makeText(this, "Connected Google: ${account.email}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
             android.util.Log.e("CAL_DEBUG", "Google Sign-In Fail", e)
        }
    }

    private val outlookAuthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val response = AuthorizationResponse.fromIntent(data)
        if (response != null) {
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, _ ->
                if (tokenResponse != null) {
                    // Try to get email from id_token or similar
                    val email = tokenResponse.idToken?.let { idToken ->
                         try {
                             val parts = idToken.split(".")
                             if (parts.size >= 2) {
                                 val payload = String(Base64.getDecoder().decode(parts[1]))
                                 val json = JSONObject(payload)
                                 json.optString("preferred_username", json.optString("email", "Outlook User"))
                             } else "Outlook User"
                         } catch (e: Exception) { "Outlook User" }
                    } ?: "Outlook User"
                    
                    val account = ConnectedCloudAccount(email, CloudProvider.OUTLOOK, emptyList(), tokenResponse.accessToken, tokenResponse.refreshToken)
                    connectedAccounts.removeAll { it.email == email }
                    connectedAccounts.add(account)
                    saveAccounts(); isCloudSignedIn = true; refreshCloudEvents(true); fetchAvailableCalendars()
                    Toast.makeText(this, "Outlook Connected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmScheduler = AlarmScheduler(this); calendarScanner = CalendarScanner(this)
        googleCalendarScanner = GoogleCalendarScanner(this); outlookCalendarScanner = OutlookCalendarScanner(this)
        authService = AuthorizationService(this)
        
        loadAccounts(); loadCloudEventsCache(); checkCloudConnection()
        loadAlarms(); loadRules(); scheduleSync(); checkBatteryOptimization()

        val permissions = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionLauncher.launch(permissions.toTypedArray())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (!am.canScheduleExactAlarms() && !appPrefs.getBoolean("prompted_alarm_v2", false)) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:$packageName") })
                    appPrefs.edit().putBoolean("prompted_alarm_v2", true).apply()
                } catch (e: Exception) {}
            }
        }

        setContent { AlarmSyncCalendarTheme {
            MainScreen(alarmScheduler, calendarScanner, googleCalendarScanner, this, activeAlarms, activeRules, cloudEvents, isCloudSignedIn, connectedAccounts, availableCalendarsMap, lastSyncTime, 
            { signInGoogle() }, { signInOutlook() }, { disconnectAccount(it) }, { email, ids -> updateSelectedCalendars(email, ids) }, 
            { refreshCloudEvents(true) }, { saveAlarms() }, { saveRules() }, { email, expanded -> toggleAccountExpansion(email, expanded) })        }}
    }

    private fun loadAccounts() {
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        lastSyncTime = prefs.getLong("last_google_sync", 0L)
        val json = prefs.getString("google_accounts_v3", "[]")
        val list: List<ConnectedCloudAccount> = try { gson.fromJson(json, object : TypeToken<List<ConnectedCloudAccount>>() {}.type) } catch (e: Exception) { emptyList() }
        connectedAccounts.clear(); connectedAccounts.addAll(list)
    }

    private fun saveAccounts() { getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putString("google_accounts_v3", gson.toJson(connectedAccounts.toList())).apply() }

    private fun checkCloudConnection() { isCloudSignedIn = connectedAccounts.isNotEmpty(); if (isCloudSignedIn) fetchAvailableCalendars() }

    private fun signInGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(Scope("https://www.googleapis.com/auth/calendar.readonly")).build()
        GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener { googleSignInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent) }
    }

    private fun signInOutlook() {
        val serviceConfig = AuthorizationServiceConfiguration(Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"), Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"))
        val authRequest = AuthorizationRequest.Builder(serviceConfig, "acbc12d9-d41d-4df2-8517-57bdfdd3b0df", ResponseTypeValues.CODE, Uri.parse("msauth://com.nen.alarmsynccalendar/1NqMWNmdbXBPmEnKVGhIDOnHqaA%3D")).setScopes("openid", "profile", "email", "offline_access", "Calendars.Read").build()
        outlookAuthLauncher.launch(authService.getAuthorizationRequestIntent(authRequest))
    }

    private fun disconnectAccount(email: String) {
        val acc = connectedAccounts.find { it.email == email } ?: return
        connectedAccounts.remove(acc); saveAccounts(); availableCalendarsMap.remove(email); isCloudSignedIn = connectedAccounts.isNotEmpty()
        if (acc.provider == CloudProvider.GOOGLE) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(this, gso).revokeAccess().addOnCompleteListener { GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener { refreshCloudEvents() } }
        } else refreshCloudEvents()
        Toast.makeText(this, "Disconnected $email", Toast.LENGTH_SHORT).show()
    }

    private fun fetchAvailableCalendars() {
        lifecycleScope.launchWhenStarted {
            connectedAccounts.forEach { acc ->
                try {
                    val token = if (acc.provider == CloudProvider.GOOGLE) {
                        withContext(Dispatchers.IO) {
                            com.google.android.gms.auth.GoogleAuthUtil.getToken(this@MainActivity, acc.email, "oauth2:https://www.googleapis.com/auth/calendar.readonly")
                        }
                    } else acc.accessToken ?: ""
                    
                    val calendars = if (acc.provider == CloudProvider.GOOGLE) googleCalendarScanner.fetchAvailableCalendars(acc.email) else outlookCalendarScanner.fetchAvailableCalendars(acc.email, token)
                    availableCalendarsMap[acc.email] = calendars
                    if (acc.selectedCalendars.isEmpty() && calendars.isNotEmpty()) {
                        val i = connectedAccounts.indexOf(acc); if (i != -1) { connectedAccounts[i] = acc.copy(selectedCalendars = calendars.map { it.id }); saveAccounts() }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CAL_DEBUG", "Failed fetchAvailableCalendars for ${acc.email}", e)
                }
            }
            refreshCloudEvents()
        }
    }

    private fun updateSelectedCalendars(email: String, ids: List<String>) {
        val i = connectedAccounts.indexOfFirst { it.email == email }; if (i != -1) { connectedAccounts[i] = connectedAccounts[i].copy(selectedCalendars = ids); saveAccounts(); refreshCloudEvents() }
    }

    private fun toggleAccountExpansion(email: String, expanded: Boolean) {
        val i = connectedAccounts.indexOfFirst { it.email == email }; if (i != -1) { connectedAccounts[i] = connectedAccounts[i].copy(isExpandedInUi = expanded); saveAccounts() }
    }

    private fun refreshCloudEvents(isManual: Boolean = false) {
        lifecycleScope.launchWhenStarted {
            try {
                val all = mutableListOf<EventInfo>()
                connectedAccounts.forEach { acc ->
                    if (acc.selectedCalendars.isNotEmpty()) {
                        val token = if (acc.provider == CloudProvider.GOOGLE) {
                            withContext(Dispatchers.IO) {
                                com.google.android.gms.auth.GoogleAuthUtil.getToken(this@MainActivity, acc.email, "oauth2:https://www.googleapis.com/auth/calendar.readonly")
                            }
                        } else acc.accessToken ?: ""
                        
                        val events = if (acc.provider == CloudProvider.GOOGLE) googleCalendarScanner.fetchEventsForAccount(acc.email, acc.selectedCalendars) else outlookCalendarScanner.fetchEventsForAccount(acc.email, token, acc.selectedCalendars)
                        all.addAll(events)
                    }
                }
                cloudEvents.clear(); cloudEvents.addAll(all); saveCloudEventsCache()
                if (isManual) {
                    Toast.makeText(this@MainActivity, "Sync Complete: ${cloudEvents.size} events", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                 android.util.Log.e("CAL_DEBUG", "Failed refreshCloudEvents", e)
                 if (isManual) Toast.makeText(this@MainActivity, "Sync Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadCloudEventsCache() {
        val json = getSharedPreferences("alarms", Context.MODE_PRIVATE).getString("cloud_events_cache", "[]")
        val list: List<EventInfo> = try { gson.fromJson(json, object : TypeToken<List<EventInfo>>() {}.type) } catch (e: Exception) { emptyList() }
        cloudEvents.clear(); cloudEvents.addAll(list)
    }

    private fun saveCloudEventsCache() {
        lastSyncTime = System.currentTimeMillis()
        getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putLong("last_google_sync", lastSyncTime).putString("cloud_events_cache", gson.toJson(cloudEvents.toList())).apply()
    }

    override fun onResume() { super.onResume(); loadAlarms(); loadRules(); if (isCloudSignedIn) refreshCloudEvents() }
    override fun onDestroy() { super.onDestroy(); authService.dispose() }

    private fun scheduleSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("CalendarSync", ExistingPeriodicWorkPolicy.KEEP, req)
    }
    private fun saveAlarms() { getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putString("alarm_list", gson.toJson(activeAlarms.toList())).apply() }
    private fun saveRules() { getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putString("rule_list", gson.toJson(activeRules.toList())).apply() }
    private fun loadAlarms() { val j = getSharedPreferences("alarms", Context.MODE_PRIVATE).getString("alarm_list", null); if (j != null) try { activeAlarms.clear(); activeAlarms.addAll(gson.fromJson(j, object : TypeToken<List<ScheduledAlarm>>() {}.type)) } catch (e: Exception) {} }
    private fun loadRules() { val j = getSharedPreferences("alarms", Context.MODE_PRIVATE).getString("rule_list", null); if (j != null) try { activeRules.clear(); activeRules.addAll(gson.fromJson(j, object : TypeToken<List<AutoScheduleRule>>() {}.type)) } catch (e: Exception) {} }

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
            val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !appPrefs.getBoolean("prompted_battery_final", false)) {
                try { 
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }) 
                    appPrefs.edit().putBoolean("prompted_battery_final", true).apply()
                } catch (e: Exception) {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    alarmScheduler: AlarmScheduler, calendarScanner: CalendarScanner, googleCalendarScanner: GoogleCalendarScanner, context: android.content.Context,
    activeAlarms: MutableList<ScheduledAlarm>, activeRules: MutableList<AutoScheduleRule>, cloudEvents: List<EventInfo>, isCloudSignedIn: Boolean,
    connectedAccounts: List<ConnectedCloudAccount>, availableCalendarsMap: Map<String, List<com.nen.alarmsynccalendar.calendar.GoogleCalendarInfo>>,
    lastSyncTime: Long, onGoogleSignIn: () -> Unit, onOutlookSignIn: () -> Unit, onDisconnectAccount: (String) -> Unit, onUpdateCalendars: (String, List<String>) -> Unit,
    onManualSync: () -> Unit, onSave: () -> Unit, saveRules: () -> Unit, onToggleAccountExpansion: (String, Boolean) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }
    var alarmToEdit by remember { mutableStateOf<ScheduledAlarm?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var ruleToEdit by remember { mutableStateOf<AutoScheduleRule?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showAddAccountChoice by remember { mutableStateOf(false) }

    if (showAboutDialog) AboutDialog({ showAboutDialog = false }, { (context as MainActivity).openSettings() }, { (context as MainActivity).openOEMSettings() })
    if (showEditDialog) AlarmEditDialog(alarmToEdit, { showEditDialog = false; alarmToEdit = null }, { t, tm, rt, rd ->
        var f = tm; if (f < System.currentTimeMillis() && rt != RecurrenceType.NONE) f = RecurrenceUtils.calculateNextOccurrence(f, rt, rd)
        
        if (f < System.currentTimeMillis()) {
            Toast.makeText(context, "Cannot set alarm in the past!", Toast.LENGTH_SHORT).show()
        } else {
            if (alarmToEdit != null) {
                alarmScheduler.cancelAlarm(alarmToEdit!!.id)
                val idx = activeAlarms.indexOfFirst { it.id == alarmToEdit!!.id }
                val u = alarmToEdit!!.copy(time = f, message = t, recurrenceType = rt, recurrenceData = rd)
                alarmScheduler.scheduleAlarm(u.id, f, t)
                if (idx != -1) activeAlarms[idx] = u
            } else {
                val id = System.currentTimeMillis().toInt(); val n = ScheduledAlarm(id, f, t, recurrenceType = rt, recurrenceData = rd)
                alarmScheduler.scheduleAlarm(id, f, t); activeAlarms.add(n)
            }
            onSave(); showEditDialog = false; alarmToEdit = null
        }
    })
    if (showRuleDialog) RuleEditDialog(ruleToEdit, { showRuleDialog = false; ruleToEdit = null }, { q, l ->
        val rule = if (ruleToEdit != null) {
            val idx = activeRules.indexOfFirst { it.id == ruleToEdit!!.id }
            val u = ruleToEdit!!.copy(organizerQuery = q, leadTimeMinutes = l)
            if (idx != -1) activeRules[idx] = u; u
        } else { val n = AutoScheduleRule(System.currentTimeMillis().toInt(), q, l); activeRules.add(n); n }
        saveRules()
        val localEvents = calendarScanner.getEventsForNextThreeMonths()
        val count = runRule(rule, localEvents, alarmScheduler, activeAlarms) + runRule(rule, cloudEvents, alarmScheduler, activeAlarms)
        if (count > 0) onSave()
        showRuleDialog = false; ruleToEdit = null
    })
    if (showAddAccountChoice) {
        AlertDialog(onDismissRequest = { showAddAccountChoice = false }, title = { Text("Add Account") },
            text = { Column {
                Button(onClick = { onGoogleSignIn(); showAddAccountChoice = false }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Cloud, null); Spacer(Modifier.width(8.dp)); Text("Google Calendar") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onOutlookSignIn(); showAddAccountChoice = false }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Email, null); Spacer(Modifier.width(8.dp)); Text("Outlook / Microsoft") }
            }}, confirmButton = {}, dismissButton = { TextButton(onClick = { showAddAccountChoice = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("CalAlarm Sync", style = MaterialTheme.typography.headlineMedium) }, actions = { IconButton(onClick = { showAboutDialog = true }) { Icon(Icons.Default.Info, null) } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) },
        bottomBar = { NavigationBar {
            NavigationBarItem(icon = { Icon(Icons.Default.Alarm, null) }, label = { Text("Alarms") }, selected = selectedTab == 0, onClick = { selectedTab = 0 })
            NavigationBarItem(icon = { Icon(Icons.Default.CalendarToday, null) }, label = { Text("Calendars") }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
            NavigationBarItem(icon = { Icon(Icons.Default.AutoFixHigh, null) }, label = { Text("Auto") }, selected = selectedTab == 2, onClick = { selectedTab = 2 })
        }},
        floatingActionButton = { if (selectedTab == 0 || selectedTab == 2) FloatingActionButton(onClick = { if (selectedTab == 0) showEditDialog = true else showRuleDialog = true }) { Icon(Icons.Default.Add, null) } }
    ) { p ->
        Box(modifier = Modifier.padding(p).fillMaxSize()) {
            when (selectedTab) {
                0 -> AlarmsTabScreen(activeAlarms, onDelete = { alarmScheduler.cancelAlarm(it.id); activeAlarms.remove(it); onSave() }, onEdit = { alarmToEdit = it; showEditDialog = true })
                1 -> CalendarsTabScreen(cloudEvents, isCloudSignedIn, connectedAccounts, availableCalendarsMap, lastSyncTime, { showAddAccountChoice = true }, onDisconnectAccount, onUpdateCalendars, onManualSync, alarmScheduler, activeAlarms, onSave, context, onToggleAccountExpansion)
                2 -> AutoScheduleTabScreen(activeRules, activeAlarms, { saveRules() }, { onSave() }, calendarScanner, cloudEvents, isCloudSignedIn, alarmScheduler, context)
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit, onOpenOEM: () -> Unit) {
    val m = android.os.Build.MANUFACTURER.lowercase()
    val isKnown = m.contains("xiaomi") || m.contains("oppo") || m.contains("realme") || m.contains("vivo")
    val context = androidx.compose.ui.platform.LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About & Privacy") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Text("CalAlarm Sync automates alarms from your cloud calendars. Version 1.7", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Privacy: All calendar data is processed and stored locally on this device. No information is collected or transmitted.", style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(Modifier.height(16.dp))
                    Text("Device Reliability", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("For 100% reliability, ensure these are enabled in App Info:", style = MaterialTheme.typography.bodySmall)
                    Text("• Auto-start\n• Battery: 'Unrestricted'\n• Show on Lock screen\n• Display over other apps", style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = if (isKnown) onOpenOEM else onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isKnown) "Open Device Settings" else "Open App Info")
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Project Links", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(text = "Source Code (GitHub)", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/uniquer/alarm_sync_calendar"))) }.padding(vertical = 4.dp))
                    Text(text = "Official Website", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://calalarm.netlify.app/"))) }.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditDialog(existingRule: AutoScheduleRule?, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var q by remember { mutableStateOf(existingRule?.organizerQuery ?: "") }; var l by remember { mutableStateOf(existingRule?.leadTimeMinutes ?: 5) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existingRule == null) "Create Rule" else "Edit Rule") },
        text = { Column {
            OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Keyword match") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(0, 5, 10, 15).forEach { mins -> FilterChip(selected = l == mins, onClick = { l = mins }, label = { Text("${mins}m") }) }
            }
        }}, confirmButton = { Button(onClick = { if (q.isNotBlank()) onConfirm(q, l) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsTabScreen(
    cloudEvents: List<EventInfo>, isCloudSignedIn: Boolean, connectedAccounts: List<ConnectedCloudAccount>,
    availableCalendarsMap: Map<String, List<com.nen.alarmsynccalendar.calendar.GoogleCalendarInfo>>,
    lastSyncTime: Long, onAddAccount: () -> Unit, onDisconnectAccount: (String) -> Unit, onUpdateCalendars: (String, List<String>) -> Unit,
    onManualSync: () -> Unit, alarmScheduler: AlarmScheduler, activeAlarms: MutableList<ScheduledAlarm>, onSave: () -> Unit, context: android.content.Context,
    onToggleAccountExpansion: (String, Boolean) -> Unit
) {
    var subTab by remember { mutableStateOf(0) }; var showDeleteConfirm by remember { mutableStateOf<ScheduledAlarm?>(null) }
    val syncSdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    if (showDeleteConfirm != null) {
        AlertDialog(onDismissRequest = { showDeleteConfirm = null }, title = { Text("Delete Alarm?") }, text = { Text("Delete local alarm? (Doesn't affect calendar event)") },
            confirmButton = { Button(onClick = { alarmScheduler.cancelAlarm(showDeleteConfirm!!.id); activeAlarms.removeAll { it.id == showDeleteConfirm!!.id }; onSave(); showDeleteConfirm = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sync and manage your cloud calendars", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
            IconButton(onClick = onManualSync) { Icon(Icons.Default.Refresh, null) }
        }
        if (lastSyncTime > 0) Text("Last synced: ${syncSdf.format(Date(lastSyncTime))}", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = subTab) { Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Events") }); Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Accounts") }) }
        Spacer(Modifier.height(16.dp))
        if (subTab == 0) {
            if (cloudEvents.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No events found.") } }
            else {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault()); val dateSdf = SimpleDateFormat("MMM dd", Locale.getDefault())
                val groupedEvents = cloudEvents.sortedBy { it.startTime }.groupBy { it.accountEmail ?: "Unknown Account" }

                androidx.compose.foundation.lazy.LazyColumn {
                    groupedEvents.forEach { (email, eventsForAccount) ->
                        val account = connectedAccounts.find { it.email == email }
                        val isExpanded = account?.isExpandedInUi ?: true
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { onToggleAccountExpansion(email, !isExpanded) },
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if(isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(text = "Account: $email", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                    Text(text = "${eventsForAccount.size} events", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (isExpanded) {
                            items(eventsForAccount) { event ->
                                val existing = activeAlarms.find { it.googleEventId == event.googleEventId }
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = {
                                    if (existing == null) {
                                        val tm = event.startTime - (5 * 60 * 1000); val id = (event.googleEventId.hashCode() + System.currentTimeMillis().toInt()).hashCode()
                                        alarmScheduler.scheduleAlarm(id, tm, event.title); activeAlarms.add(ScheduledAlarm(id, tm, event.title, googleEventId = event.googleEventId, googleRecurrenceInfo = event.recurrenceDetails, manualLeadTimeMinutes = 5))
                                        onSave(); Toast.makeText(context, "Alarm set!", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) { Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (event.isRecurring) Icon(Icons.Default.Repeat, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary) }
                                            Text("${dateSdf.format(Date(event.startTime))}, ${sdf.format(Date(event.startTime))}"); Text(event.organizer ?: "Unknown", style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (existing != null) { IconButton(onClick = { showDeleteConfirm = existing }) { Icon(Icons.Default.AlarmOn, null, tint = MaterialTheme.colorScheme.primary) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(connectedAccounts) { acc ->
                    var expanded by remember { mutableStateOf(false) }
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column {
                            Row(modifier = Modifier.padding(12.dp).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                                Icon(if(acc.provider == CloudProvider.GOOGLE) Icons.Default.Cloud else Icons.Default.Email, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) { Text(acc.email, style = MaterialTheme.typography.bodyMedium); Text("${acc.selectedCalendars.size} selected", style = MaterialTheme.typography.labelSmall) }
                                IconButton(onClick = { onDisconnectAccount(acc.email) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            }
                            if (expanded) {
                                val cals = availableCalendarsMap[acc.email] ?: emptyList()
                                if (cals.isEmpty()) { Text("Fetching calendars...", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                                else cals.forEach { cal ->
                                    val isSelected = acc.selectedCalendars.contains(cal.id)
                                    Row(modifier = Modifier.fillMaxWidth().clickable { val next = acc.selectedCalendars.toMutableList(); if (isSelected) next.remove(cal.id) else next.add(cal.id); onUpdateCalendars(acc.email, next) }.padding(horizontal = 32.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(isSelected, { checked -> val next = acc.selectedCalendars.toMutableList(); if (checked) next.add(cal.id) else next.remove(cal.id); onUpdateCalendars(acc.email, next) }); Text(cal.summary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Button(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) { Text("Add Cloud Account") } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoScheduleTabScreen(activeRules: MutableList<AutoScheduleRule>, activeAlarms: MutableList<ScheduledAlarm>, onSaveRules: () -> Unit, onSaveAlarms: () -> Unit, calendarScanner: CalendarScanner, cloudEvents: List<EventInfo>, isCloudSignedIn: Boolean, alarmScheduler: AlarmScheduler, context: android.content.Context) {
    var ruleToDelete by remember { mutableStateOf<AutoScheduleRule?>(null) }; var selectedRule by remember { mutableStateOf<AutoScheduleRule?>(null) }
    if (ruleToDelete != null) {
        var deleteAlarms by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { ruleToDelete = null }, title = { Text("Delete Rule?") }, text = { Column { Text("Delete rule for '${ruleToDelete?.organizerQuery}'?"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteAlarms, { deleteAlarms = it }); Text("Delete alarms") } } },
            confirmButton = { Button(onClick = { val rule = ruleToDelete!!; if (deleteAlarms) { val alarmsToRemove = activeAlarms.filter { it.sourceRuleId == rule.id }; alarmsToRemove.forEach { alarmScheduler.cancelAlarm(it.id) }; activeAlarms.removeAll(alarmsToRemove); onSaveAlarms() }; activeRules.remove(rule); onSaveRules(); ruleToDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { ruleToDelete = null }) { Text("Cancel") } }
        )
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (selectedRule != null) {
            val ruleAlarms = activeAlarms.filter { it.sourceRuleId == selectedRule!!.id }
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { selectedRule = null }) { Icon(Icons.Default.ArrowBack, null) }; Text("Alarms: ${selectedRule!!.organizerQuery}", modifier = Modifier.weight(1f)) }
            if (ruleAlarms.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No alarms.") }
            else androidx.compose.foundation.lazy.LazyColumn { items(ruleAlarms) { AlarmCard(it, {}, { alarmScheduler.cancelAlarm(it.id); activeAlarms.remove(it); onSaveAlarms() }) } }
        } else {
            Text("Automate alarms based on calendar keywords", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(activeRules) { rule -> RuleCard(rule, activeAlarms, { idx -> val i = activeRules.indexOfFirst { it.id == rule.id }; if (i != -1) { activeRules[i] = rule.copy(isEnabled = idx); onSaveRules() } }, { ruleToDelete = rule }, { selectedRule = rule }) }
            }
        }
    }
}

@Composable
fun RuleCard(rule: AutoScheduleRule, activeAlarms: List<ScheduledAlarm>, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    val count = activeAlarms.count { it.sourceRuleId == rule.id }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { Text(rule.organizerQuery, style = MaterialTheme.typography.titleMedium); Text("$count scheduled", style = MaterialTheme.typography.bodySmall) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

fun runRule(rule: AutoScheduleRule, events: List<EventInfo>, scheduler: AlarmScheduler, activeAlarms: MutableList<ScheduledAlarm>): Int {
    if (!rule.isEnabled) return 0
    var scheduledCount = 0
    events.forEach { event ->
        val match = event.organizer?.contains(rule.organizerQuery, ignoreCase = true) == true || event.title.contains(rule.organizerQuery, ignoreCase = true)
        if (match) {
            val tm = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
            val existingIdx = activeAlarms.indexOfFirst { (it.googleEventId != null && it.googleEventId == event.googleEventId) || (it.calendarEventId != null && it.calendarEventId == event.id) }
            if (existingIdx != -1) {
                val existing = activeAlarms[existingIdx]; if (existing.time != tm) { scheduler.cancelAlarm(existing.id); scheduler.scheduleAlarm(existing.id, tm, event.title); activeAlarms[existingIdx] = existing.copy(time = tm, sourceRuleId = rule.id); scheduledCount++ }
            } else {
                val id = if (event.source == EventSource.CLOUD) (event.googleEventId.hashCode() + rule.id).hashCode() else (event.id.toInt() + rule.id).hashCode()
                scheduler.scheduleAlarm(id, tm, event.title); activeAlarms.add(ScheduledAlarm(id, tm, event.title, calendarEventId = if (event.source == EventSource.LOCAL) event.id else null, googleEventId = event.googleEventId, googleRecurrenceInfo = event.recurrenceDetails, sourceRuleId = rule.id)); scheduledCount++
            }
        }
    }
    return scheduledCount
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditDialog(existingAlarm: ScheduledAlarm?, onDismiss: () -> Unit, onConfirm: (String, Long, RecurrenceType, Int?) -> Unit) {
    var title by remember { mutableStateOf(existingAlarm?.message ?: "") }; var recurrenceType by remember { mutableStateOf(existingAlarm?.recurrenceType ?: RecurrenceType.NONE) }
    val calendar = remember { Calendar.getInstance().apply { if (existingAlarm != null) timeInMillis = existingAlarm.time } }
    var selectedDate by remember { mutableStateOf(calendar.time) }; var selectedTime by remember { mutableStateOf(calendar.time) }
    val context = androidx.compose.ui.platform.LocalContext.current; val dateSdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()); val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existingAlarm == null) "Create Alarm" else "Edit Alarm") },
        text = { androidx.compose.foundation.lazy.LazyColumn { item {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth().clickable { DatePickerDialog(context, { _, y, m, d -> calendar.set(y, m, d); selectedDate = calendar.time }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show() }.padding(8.dp)) { Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(16.dp)); Text("Date: ${dateSdf.format(selectedDate)}") }
            Row(modifier = Modifier.fillMaxWidth().clickable { TimePickerDialog(context, { _, h, min -> calendar.set(Calendar.HOUR_OF_DAY, h); calendar.set(Calendar.MINUTE, min); selectedTime = calendar.time }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show() }.padding(8.dp)) { Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(16.dp)); Text("Time: ${timeSdf.format(selectedTime)}") }
            Spacer(Modifier.height(16.dp)); Text("Recurrence", style = MaterialTheme.typography.labelLarge)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf(RecurrenceType.NONE, RecurrenceType.DAILY, RecurrenceType.WEEKLY).forEach { type -> FilterChip(selected = recurrenceType == type, onClick = { recurrenceType = type }, label = { Text(if(type == RecurrenceType.NONE) "One-time" else type.name) }) } }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { FilterChip(selected = recurrenceType == RecurrenceType.MONTHLY, onClick = { recurrenceType = RecurrenceType.MONTHLY }, label = { Text("MONTHLY") }) }
            }
            if (recurrenceType == RecurrenceType.WEEKLY) {
                Spacer(Modifier.height(8.dp)); Text("Day of Week", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { i, n -> val dayNum = i + 1; val isSelected = calendar.get(Calendar.DAY_OF_WEEK) == dayNum; AssistChip(onClick = { calendar.set(Calendar.DAY_OF_WEEK, dayNum); selectedDate = calendar.time }, label = { Text(n) }, colors = if (isSelected) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else AssistChipDefaults.assistChipColors()) }
                }
            }
        }}}, confirmButton = { Button(onClick = { val recData = when(recurrenceType) { RecurrenceType.WEEKLY -> calendar.get(Calendar.DAY_OF_WEEK); RecurrenceType.MONTHLY -> calendar.get(Calendar.DAY_OF_MONTH); else -> null }; onConfirm(title.ifBlank { "Manual Alarm" }, calendar.timeInMillis, recurrenceType, recData) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AlarmsTabScreen(activeAlarms: List<ScheduledAlarm>, onDelete: (ScheduledAlarm) -> Unit, onEdit: (ScheduledAlarm) -> Unit) {
    var alarmSubTab by remember { mutableStateOf(0) }; val currentTime = System.currentTimeMillis(); val upcoming = activeAlarms.filter { it.time > currentTime }.sortedBy { it.time }; val past = activeAlarms.filter { it.time <= currentTime }.sortedByDescending { it.time }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TabRow(selectedTabIndex = alarmSubTab) { Tab(selected = alarmSubTab == 0, onClick = { alarmSubTab = 0 }, text = { Text("Upcoming") }); Tab(selected = alarmSubTab == 1, onClick = { alarmSubTab = 1 }, text = { Text("Past") }) }
        Spacer(Modifier.height(8.dp))
        Text(if(alarmSubTab == 0) "Alarms that are upcoming" else "Alarms that are expired", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))
        if (alarmSubTab == 0) { androidx.compose.foundation.lazy.LazyColumn { if (upcoming.isEmpty()) item { Text("No upcoming alarms.") } else items(upcoming) { AlarmCard(it, onEdit, onDelete) } } }
        else { androidx.compose.foundation.lazy.LazyColumn { if (past.isEmpty()) item { Text("No past alarms.") } else items(past) { AlarmCard(it, onEdit, onDelete, isPast = true) } } }
    }
}

@Composable
fun AlarmCard(alarm: ScheduledAlarm, onEdit: (ScheduledAlarm) -> Unit, onDelete: (ScheduledAlarm) -> Unit, isPast: Boolean = false) {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = if (isPast) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) else CardDefaults.cardColors()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (alarm.recurrenceType != RecurrenceType.NONE || alarm.googleRecurrenceInfo != null) Icons.Default.Repeat else Icons.Default.Notifications, null, tint = if (isPast) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = alarm.message, style = MaterialTheme.typography.titleMedium, textDecoration = if (isPast) androidx.compose.ui.text.style.TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                    if (alarm.calendarEventId != null || alarm.googleEventId != null) { Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), modifier = Modifier.padding(start = 8.dp)) { Text(text = "SYNCED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) } }
                }
                val icon = when { alarm.googleRecurrenceInfo != null -> " ☁️"; alarm.recurrenceType != RecurrenceType.NONE -> " 🔄"; else -> "" }
                Text(text = "${sdf.format(Date(alarm.time))}$icon", style = MaterialTheme.typography.bodySmall)
            }
            if (!isPast && alarm.calendarEventId == null && alarm.googleEventId == null) IconButton(onClick = { onEdit(alarm) }) { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { onDelete(alarm) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}
