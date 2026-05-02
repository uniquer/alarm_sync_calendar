package com.example.alarmsynccalendar

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
import com.example.alarmsynccalendar.alarm.AlarmScheduler
import com.example.alarmsynccalendar.calendar.CalendarScanner
import com.example.alarmsynccalendar.ui.theme.AlarmSyncCalendarTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

import androidx.work.*
import com.example.alarmsynccalendar.sync.SyncWorker
import java.util.concurrent.TimeUnit

data class ScheduledAlarm(
    val id: Int,
    val time: Long,
    val message: String,
    val calendarEventId: Long? = null,
    val sourceRuleId: Int? = null,
    val manualLeadTimeMinutes: Int? = null
)

data class AutoScheduleRule(
    val id: Int,
    val organizerQuery: String,
    val leadTimeMinutes: Int,
    val isEnabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var calendarScanner: CalendarScanner
    private val activeAlarms = mutableStateListOf<ScheduledAlarm>()
    private val activeRules = mutableStateListOf<AutoScheduleRule>()
    private val gson = Gson()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("MainActivity", "onCreate called")
        alarmScheduler = AlarmScheduler(this)
        calendarScanner = CalendarScanner(this)
        loadAlarms()
        loadRules()
        scheduleSync()

        checkBatteryOptimization()

        val permissions = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent {
            AlarmSyncCalendarTheme {
                MainScreen(
                    alarmScheduler = alarmScheduler,
                    calendarScanner = calendarScanner,
                    context = this,
                    activeAlarms = activeAlarms,
                    activeRules = activeRules,
                    onSave = { saveAlarms() },
                    saveRules = { saveRules() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("MainActivity", "onResume called - refreshing alarms")
        loadAlarms()
        loadRules()
    }

    private fun scheduleSync() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build())
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CalendarSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun saveAlarms() {
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val json = gson.toJson(activeAlarms.toList())
        android.util.Log.d("MainActivity", "Saving ${activeAlarms.size} alarms to prefs")
        prefs.edit().putString("alarm_list", json).apply()
    }

    private fun saveRules() {
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val json = gson.toJson(activeRules.toList())
        android.util.Log.d("MainActivity", "Saving ${activeRules.size} rules to prefs")
        prefs.edit().putString("rule_list", json).apply()
    }

    private fun loadAlarms() {
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val json = prefs.getString("alarm_list", null)
        android.util.Log.d("MainActivity", "Loading alarms: $json")
        if (json != null) {
            try {
                val type = object : TypeToken<List<ScheduledAlarm>>() {}.type
                val list: List<ScheduledAlarm> = gson.fromJson(json, type)
                activeAlarms.clear()
                activeAlarms.addAll(list)
                android.util.Log.d("MainActivity", "Successfully loaded ${list.size} alarms")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error parsing alarms JSON", e)
                // Do NOT remove the list, just log it. Maybe it can be recovered or fixed.
            }
        }
    }

    private fun loadRules() {
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val json = prefs.getString("rule_list", null)
        android.util.Log.d("MainActivity", "Loading rules: $json")
        if (json != null) {
            try {
                val type = object : TypeToken<List<AutoScheduleRule>>() {}.type
                val list: List<AutoScheduleRule> = gson.fromJson(json, type)
                activeRules.clear()
                activeRules.addAll(list)
                android.util.Log.d("MainActivity", "Successfully loaded ${list.size} rules")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error parsing rules JSON", e)
            }
        }
    }

    fun openSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    fun openOEMSettings() {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        try {
            val intent = Intent()
            when {
                manufacturer.contains("xiaomi") -> {
                    intent.component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                    intent.component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
                manufacturer.contains("vivo") -> {
                    intent.component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    intent.component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                }
                else -> {
                    openSettings()
                    return
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            openSettings()
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pkg = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                val intent = Intent().apply {
                    action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$pkg")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    alarmScheduler: AlarmScheduler,
    calendarScanner: CalendarScanner,
    context: android.content.Context,
    activeAlarms: MutableList<ScheduledAlarm>,
    activeRules: MutableList<AutoScheduleRule>,
    onSave: () -> Unit,
    saveRules: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }
    var alarmToEdit by remember { mutableStateOf<ScheduledAlarm?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var ruleToEdit by remember { mutableStateOf<AutoScheduleRule?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false },
            onOpenSettings = { (context as MainActivity).openSettings() },
            onOpenOEM = { (context as MainActivity).openOEMSettings() }
        )
    }

    if (showEditDialog) {
        AlarmEditDialog(
            existingAlarm = alarmToEdit,
            onDismiss = { 
                showEditDialog = false
                alarmToEdit = null
            },
            onConfirm = { title, time ->
                if (time < System.currentTimeMillis()) {
                    Toast.makeText(context, "Cannot set alarm in the past!", Toast.LENGTH_SHORT).show()
                } else {
                    if (alarmToEdit != null) {
                        alarmScheduler.cancelAlarm(alarmToEdit!!.id)
                        val index = activeAlarms.indexOfFirst { it.id == alarmToEdit!!.id }
                        val updated = alarmToEdit!!.copy(time = time, message = title)
                        alarmScheduler.scheduleAlarm(updated.id, time, title)
                        if (index != -1) activeAlarms[index] = updated
                    } else {
                        val id = System.currentTimeMillis().toInt()
                        val newAlarm = ScheduledAlarm(id, time, title)
                        alarmScheduler.scheduleAlarm(id, time, title)
                        activeAlarms.add(newAlarm)
                    }
                    onSave()
                    showEditDialog = false
                    alarmToEdit = null
                }
            }
        )
    }

    if (showRuleDialog) {
        RuleEditDialog(
            existingRule = ruleToEdit,
            onDismiss = {
                showRuleDialog = false
                ruleToEdit = null
            },
            onConfirm = { query, leadTime ->
                val rule = if (ruleToEdit != null) {
                    val index = activeRules.indexOfFirst { it.id == ruleToEdit!!.id }
                    val updated = ruleToEdit!!.copy(organizerQuery = query, leadTimeMinutes = leadTime)
                    if (index != -1) activeRules[index] = updated
                    updated
                } else {
                    val newRule = AutoScheduleRule(System.currentTimeMillis().toInt(), query, leadTime)
                    activeRules.add(newRule)
                    newRule
                }
                saveRules()
                
                // Immediate Execution: Run the rule as soon as it is created/edited
                val count = runRule(rule, calendarScanner, alarmScheduler, activeAlarms)
                if (count > 0) {
                    onSave() // Save the newly created alarms to SharedPreferences
                    Toast.makeText(context, "Auto-scheduled $count alarms immediately!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Rule saved. No matching events found in next 90 days.", Toast.LENGTH_SHORT).show()
                }
                
                showRuleDialog = false
                ruleToEdit = null
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("CalAlarm Sync", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    label = { Text("Alarms") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                    label = { Text("Calendars") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                    label = { Text("Auto") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0 || selectedTab == 2) {
                FloatingActionButton(onClick = {
                    if (selectedTab == 0) {
                        alarmToEdit = null
                        showEditDialog = true
                    } else {
                        ruleToEdit = null
                        showRuleDialog = true
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> AlarmsTabScreen(
                    activeAlarms = activeAlarms,
                    onDelete = { alarm ->
                        alarmScheduler.cancelAlarm(alarm.id)
                        activeAlarms.remove(alarm)
                        onSave()
                    },
                    onEdit = { alarm ->
                        alarmToEdit = alarm
                        showEditDialog = true
                    }
                )
                1 -> CalendarsTabScreen(
                    calendarScanner = calendarScanner,
                    alarmScheduler = alarmScheduler,
                    activeAlarms = activeAlarms,
                    onSave = onSave,
                    context = context
                )
                2 -> AutoScheduleTabScreen(
                    activeRules = activeRules,
                    activeAlarms = activeAlarms,
                    onSaveRules = { saveRules() },
                    onSaveAlarms = { onSave() },
                    calendarScanner = calendarScanner,
                    alarmScheduler = alarmScheduler,
                    context = context
                )
            }
        }
    }
}

@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOEM: () -> Unit
) {
    val manufacturer = remember { android.os.Build.MANUFACTURER.lowercase() }
    val isKnownOEM = remember {
        manufacturer.contains("xiaomi") || 
        manufacturer.contains("oppo") || 
        manufacturer.contains("realme") || 
        manufacturer.contains("vivo") || 
        manufacturer.contains("huawei") || 
        manufacturer.contains("honor")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About & Privacy") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Text(
                        "CalAlarm Sync automatically synchronizes your calendar events with system alarms.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    Text("Device Optimization", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "For 100% reliability on ${android.os.Build.MANUFACTURER} devices, ensure Battery Saver is 'Unrestricted' and Auto-start is enabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = if (isKnownOEM) onOpenOEM else onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Text(if (isKnownOEM) "Fix Device Issues" else "Open App Settings")
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    Text("Privacy Policy", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "This app processes all calendar data locally on your device. " +
                        "No personal details... are ever collected or transmitted.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Version 1.1", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Source Code:", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "https://github.com/uniquer/alarm_sync_calendar",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditDialog(
    existingRule: AutoScheduleRule?,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var query by remember { mutableStateOf(existingRule?.organizerQuery ?: "") }
    var leadTimeMinutes by remember { mutableStateOf(existingRule?.leadTimeMinutes ?: 5) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingRule == null) "Create Rule" else "Edit Rule") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Organizer Name or Email") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. karthik, xyz@abc.com") }
                )
                Spacer(Modifier.height(16.dp))
                Text("Alarm Lead Time", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(0, 5, 10, 15).forEach { mins ->
                        FilterChip(
                            selected = leadTimeMinutes == mins,
                            onClick = { leadTimeMinutes = mins },
                            label = { Text("${mins}m") }
                        )
                    }
                }
                Text("Default is 5 minutes before the event.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { if (query.isNotBlank()) onConfirm(query, leadTimeMinutes) }) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoScheduleTabScreen(
    activeRules: MutableList<AutoScheduleRule>,
    activeAlarms: MutableList<ScheduledAlarm>,
    onSaveRules: () -> Unit,
    onSaveAlarms: () -> Unit,
    calendarScanner: CalendarScanner,
    alarmScheduler: AlarmScheduler,
    context: android.content.Context
) {
    var selectedRule by remember { mutableStateOf<AutoScheduleRule?>(null) }

    if (selectedRule != null) {
        // Show list of alarms for this specific rule
        val ruleAlarms = activeAlarms.filter { it.sourceRuleId == selectedRule!!.id }
        
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedRule = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text("Alarms for: ${selectedRule!!.organizerQuery}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            
            if (ruleAlarms.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No alarms generated by this rule yet.")
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(ruleAlarms) { alarm ->
                        AlarmCard(alarm, onEdit = {}, onDelete = {
                            alarmScheduler.cancelAlarm(alarm.id)
                            activeAlarms.remove(alarm)
                            onSaveAlarms()
                        })
                    }
                }
            }
        }
    } else {
        // Show list of rules
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Text("Automation Rules", style = MaterialTheme.typography.headlineSmall)
                Text("Events matching these rules will be scheduled automatically.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
            }
            
            if (activeRules.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("No rules yet. Click + to add one.")
                    }
                }
            } else {
                items(activeRules) { rule ->
                    RuleCard(
                        rule = rule,
                        activeAlarms = activeAlarms,
                        onToggle = { isEnabled ->
                            val index = activeRules.indexOfFirst { it.id == rule.id }
                            if (index != -1) {
                                activeRules[index] = rule.copy(isEnabled = isEnabled)
                                onSaveRules()
                            }
                        },
                        onDelete = {
                            activeRules.remove(rule)
                            onSaveRules()
                        },
                        onRun = {
                            if (rule.isEnabled) {
                                val count = runRule(rule, calendarScanner, alarmScheduler, activeAlarms)
                                if (count > 0) {
                                    onSaveAlarms()
                                    Toast.makeText(context, "Scheduled $count alarms!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No new events found.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Enable the rule first to run it.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClick = { selectedRule = rule }
                    )
                }
            }
        }
    }
}

@Composable
fun RuleCard(
    rule: AutoScheduleRule,
    activeAlarms: List<ScheduledAlarm>,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    onClick: () -> Unit
) {
    val scheduledCount = activeAlarms.count { it.sourceRuleId == rule.id }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        colors = if (rule.isEnabled) CardDefaults.cardColors() 
                 else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (rule.isEnabled) Icons.Default.PlayCircleFilled else Icons.Default.PauseCircleFilled, 
                    contentDescription = null, 
                    tint = if (rule.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.organizerQuery, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (rule.isEnabled) "ACTIVE" else "PAUSED",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (rule.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(" • ", style = MaterialTheme.typography.labelSmall)
                        Text("Lead: ${rule.leadTimeMinutes}m", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = "$scheduledCount events scheduled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle
                )
                
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

fun runRule(
    rule: AutoScheduleRule,
    scanner: CalendarScanner,
    scheduler: AlarmScheduler,
    activeAlarms: MutableList<ScheduledAlarm>
): Int {
    if (!rule.isEnabled) return 0
    val events = scanner.getEventsForNextThreeMonths()
    var scheduledCount = 0
    
    events.forEach { event ->
        val organizerMatch = event.organizer?.contains(rule.organizerQuery, ignoreCase = true) == true
        val titleMatch = event.title.contains(rule.organizerQuery, ignoreCase = true)
        
        if (organizerMatch || titleMatch) {
            val alarmTime = event.startTime - (rule.leadTimeMinutes * 60 * 1000)
            
            // Check if alarm for this event already exists
            val existingIndex = activeAlarms.indexOfFirst { it.calendarEventId == event.id }
            
            if (existingIndex != -1) {
                val existing = activeAlarms[existingIndex]
                // Overwrite if time is different
                if (existing.time != alarmTime) {
                    scheduler.cancelAlarm(existing.id)
                    scheduler.scheduleAlarm(existing.id, alarmTime, event.title)
                    activeAlarms[existingIndex] = existing.copy(time = alarmTime, sourceRuleId = rule.id)
                    scheduledCount++
                }
            } else {
                // Create new
                val id = (event.id.toInt() + rule.id).hashCode()
                scheduler.scheduleAlarm(id, alarmTime, event.title)
                activeAlarms.add(ScheduledAlarm(id, alarmTime, event.title, event.id, rule.id))
                scheduledCount++
            }
        }
    }
    return scheduledCount
}

@Composable
fun AlarmEditDialog(
    existingAlarm: ScheduledAlarm?,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit
) {
    var title by remember { mutableStateOf(existingAlarm?.message ?: "") }
    val calendar = remember { 
        Calendar.getInstance().apply { 
            if (existingAlarm != null) timeInMillis = existingAlarm.time 
        }
    }
    var selectedDate by remember { mutableStateOf(calendar.time) }
    var selectedTime by remember { mutableStateOf(calendar.time) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val dateSdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingAlarm == null) "Create Alarm" else "Edit Alarm") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Alarm Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        val datePicker = DatePickerDialog(context, { _, y, m, d ->
                            calendar.set(y, m, d)
                            selectedDate = calendar.time
                        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
                        
                        // Restrict only to future dates
                        datePicker.datePicker.minDate = System.currentTimeMillis() - 1000
                        datePicker.show()
                    }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Text(text = "Date: ${dateSdf.format(selectedDate)}")
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        TimePickerDialog(context, { _, h, min ->
                            calendar.set(Calendar.HOUR_OF_DAY, h)
                            calendar.set(Calendar.MINUTE, min)
                            selectedTime = calendar.time
                        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                    }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Text(text = "Time: ${timeSdf.format(selectedTime)}")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(title.ifBlank { "Manual Alarm" }, calendar.timeInMillis) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AlarmsTabScreen(
    activeAlarms: List<ScheduledAlarm>,
    onDelete: (ScheduledAlarm) -> Unit,
    onEdit: (ScheduledAlarm) -> Unit
) {
    val currentTime = System.currentTimeMillis()
    val upcoming = activeAlarms.filter { it.time > currentTime }.sortedBy { it.time }
    val past = activeAlarms.filter { it.time <= currentTime }.sortedByDescending { it.time }

    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Upcoming Alarms", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        if (upcoming.isEmpty()) {
            item { Text("No upcoming alarms.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
        } else {
            items(upcoming) { alarm ->
                AlarmCard(alarm, onEdit, onDelete)
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Past Alarms", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (past.isEmpty()) {
            item { Text("No past alarms.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
        } else {
            items(past) { alarm ->
                AlarmCard(alarm, onEdit, onDelete, isPast = true)
            }
        }
    }
}

@Composable
fun AlarmCard(
    alarm: ScheduledAlarm,
    onEdit: (ScheduledAlarm) -> Unit,
    onDelete: (ScheduledAlarm) -> Unit,
    isPast: Boolean = false
) {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (isPast) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                 else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Notifications, 
                contentDescription = null,
                tint = if (isPast) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = alarm.message, 
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (isPast) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                    if (alarm.calendarEventId != null) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "SYNCED",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = sdf.format(Date(alarm.time)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!isPast && alarm.calendarEventId == null) {
                IconButton(onClick = { onEdit(alarm) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = { onDelete(alarm) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsTabScreen(
    calendarScanner: CalendarScanner,
    alarmScheduler: AlarmScheduler,
    activeAlarms: MutableList<ScheduledAlarm>,
    onSave: () -> Unit,
    context: android.content.Context
) {
    var allCalendars by remember { mutableStateOf(emptyList<com.example.alarmsynccalendar.calendar.CalendarInfo>()) }
    var showPrimaryOnly by remember { mutableStateOf(true) }
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }
    var events by remember { mutableStateOf(emptyList<com.example.alarmsynccalendar.calendar.EventInfo>()) }
    val selectedEventIds = remember { mutableStateListOf<Long>() }
    var showLeadTimeDialog by remember { mutableStateOf(false) }

    if (showLeadTimeDialog) {
        var leadTimeMinutes by remember { mutableStateOf(5) }
        AlertDialog(
            onDismissRequest = { showLeadTimeDialog = false },
            title = { Text("Set Alarm Lead Time") },
            text = {
                Column {
                    Text("Choose how many minutes before the event to trigger the alarm.")
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(0, 5, 10, 15).forEach { mins ->
                            FilterChip(
                                selected = leadTimeMinutes == mins,
                                onClick = { leadTimeMinutes = mins },
                                label = { Text("${mins}m") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val selectedEvents = events.filter { selectedEventIds.contains(it.id) }
                    selectedEvents.forEach { event ->
                        val alarmTime = event.startTime - (leadTimeMinutes * 60 * 1000)
                        val id = event.id.toInt() + System.currentTimeMillis().toInt()
                        alarmScheduler.scheduleAlarm(id, alarmTime, event.title)
                        activeAlarms.add(ScheduledAlarm(id, alarmTime, event.title, event.id, manualLeadTimeMinutes = leadTimeMinutes))
                    }
                    onSave()
                    Toast.makeText(context, "Set ${selectedEventIds.size} Alarms!", Toast.LENGTH_SHORT).show()
                    selectedEventIds.clear()
                    showLeadTimeDialog = false
                }) {
                    Text("Set Alarms")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeadTimeDialog = false }) { Text("Cancel") }
            }
        )
    }

    LaunchedEffect(Unit) {
        allCalendars = calendarScanner.getLocalCalendars()
    }

    val filteredCalendars = if (showPrimaryOnly) {
        allCalendars.filter { it.isPrimary }
    } else {
        allCalendars
    }

    val groupedCalendars = filteredCalendars.groupBy { it.accountType }

    LaunchedEffect(selectedCalendarId) {
        selectedCalendarId?.let {
            events = calendarScanner.getEventsForCalendar(it)
            selectedEventIds.clear()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (selectedCalendarId == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Calendars", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                
                IconButton(onClick = { 
                    allCalendars = calendarScanner.getLocalCalendars()
                    Toast.makeText(context, "Refreshed", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }

                Text("Primary", style = MaterialTheme.typography.bodyMedium)
                Checkbox(
                    checked = showPrimaryOnly,
                    onCheckedChange = { showPrimaryOnly = it }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            androidx.compose.foundation.lazy.LazyColumn {
                groupedCalendars.forEach { (type, calendarsForType) ->
                    item {
                        Text(
                            text = type.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(calendarsForType) { calendar ->
                        Card(
                            onClick = { selectedCalendarId = calendar.id },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(calendar.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(calendar.accountName, style = MaterialTheme.typography.bodySmall)
                                }
                                if (calendar.isPrimary) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                        Text("Primary", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedCalendarId = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text("Meetings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                
                if (selectedEventIds.isNotEmpty()) {
                    Button(onClick = {
                        showLeadTimeDialog = true
                    }) {
                        Text("Set Alarms (${selectedEventIds.size})")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (events.isEmpty()) {
                Text("No upcoming meetings.")
            } else {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                val dateSdf = SimpleDateFormat("MMM dd", Locale.getDefault())
                
                androidx.compose.foundation.lazy.LazyColumn {
                    items(events) { event ->
                        val isSelected = selectedEventIds.contains(event.id)
                        val alreadySet = activeAlarms.any { it.calendarEventId == event.id && it.time > System.currentTimeMillis() }
                        
                        Card(
                            onClick = {
                                if (!alreadySet) {
                                    if (isSelected) selectedEventIds.remove(event.id)
                                    else selectedEventIds.add(event.id)
                                }
                            },
                            colors = if (alreadySet) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                     else if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                     else CardDefaults.cardColors(),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        if (alreadySet) {
                                            Icon(
                                                Icons.Default.AlarmOn, 
                                                contentDescription = "Alarm Set",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        "${dateSdf.format(Date(event.startTime))}, ${sdf.format(Date(event.startTime))} - ${sdf.format(Date(event.endTime))}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    event.organizer?.let {
                                        Text(
                                            text = "Organizer: $it",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                if (!alreadySet) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            if (it) selectedEventIds.add(event.id)
                                            else selectedEventIds.remove(event.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}