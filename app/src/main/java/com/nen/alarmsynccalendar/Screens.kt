package com.nen.alarmsynccalendar

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import com.nen.alarmsynccalendar.alarm.AlarmScheduler
import java.text.SimpleDateFormat
import java.util.*
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    alarmScheduler: AlarmScheduler, context: android.content.Context,
    activeAlarms: MutableList<ScheduledAlarm>, cloudEvents: List<EventInfo>, isCloudSignedIn: Boolean,
    connectedAccounts: List<ConnectedCloudAccount>, lastSyncTime: Long, 
    onGoogleSignIn: () -> Unit, onOutlookSignIn: () -> Unit, onDisconnectAccount: (String) -> Unit, 
    onTogglePrimary: (String, Boolean) -> Unit, onManualSync: () -> Unit, onSave: () -> Unit,
    excludedEvents: MutableList<ExcludedEvent>, onRestoreExcluded: (ExcludedEvent) -> Unit, onSaveExcluded: () -> Unit,
    onToggleAlarm: (EventInfo, Boolean) -> Unit, isSyncing: Boolean,
    appSettings: AppSettings, onUpdateSettings: (AppSettings) -> Unit,
    showLocationPrompt: Boolean, onDismissLocationPrompt: () -> Unit,
    openTabState: MutableState<String?>,
    onToggleSecondaryCalendar: ((String, String, Boolean) -> Unit)? = null,
    fetchAvailableCalendars: (suspend (ConnectedCloudAccount) -> List<com.nen.alarmsynccalendar.calendar.GoogleCalendarInfo>)? = null
) {
    var selectedTab by remember { mutableStateOf(0) }
    var alarmsSubTab by remember { mutableStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }
    var alarmToEdit by remember { mutableStateOf<ScheduledAlarm?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showAddAccountChoice by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val upcomingAlarmsState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pastAlarmsState = androidx.compose.foundation.lazy.rememberLazyListState()
    val calendarsState = androidx.compose.foundation.lazy.rememberLazyListState()

    val isAlarmsAtTop by remember(alarmsSubTab) {
        derivedStateOf {
            val state = if (alarmsSubTab == 0) upcomingAlarmsState else pastAlarmsState
            state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        }
    }

    val isCalendarsAtTop by remember {
        derivedStateOf {
            calendarsState.firstVisibleItemIndex == 0 && calendarsState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(openTabState.value) {
        if (openTabState.value == "past") {
            selectedTab = 0
            alarmsSubTab = 1
            openTabState.value = null
            (context as? MainActivity)?.intent?.removeExtra("OPEN_TAB")
        }
    }

    // Ticker for Google Maps API errors (place search + distance lookups) while the app is active
    LaunchedEffect(Unit) {
        com.nen.alarmsynccalendar.maps.MapsService.lastError.collect { error ->
            if (error != null) {
                com.nen.alarmsynccalendar.maps.MapsService.lastError.value = null
                snackbarHostState.showSnackbar(error, withDismissAction = true)
            }
        }
    }

    if (showAboutDialog) AboutDialog(
        onDismiss = { showAboutDialog = false },
        onOpenSettings = { (context as MainActivity).openSettings() },
        onOpenOEM = { (context as MainActivity).openOEMSettings() },
        onOpenMIUIPermissions = { (context as MainActivity).openMIUIPermissions() }
    )
    if (showEditDialog) AlarmEditDialog(alarmToEdit, { showEditDialog = false; alarmToEdit = null }, { t, tm, rt, rd ->
        var f = tm; if (f < System.currentTimeMillis() && rt != RecurrenceType.NONE) f = RecurrenceUtils.calculateNextOccurrence(f, rt, rd)
        if (f >= System.currentTimeMillis()) {
            if (alarmToEdit != null) alarmScheduler.cancelAlarm(alarmToEdit!!.id)
            val id = alarmToEdit?.id ?: (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            val n = ScheduledAlarm(id, f, t, recurrenceType = rt, recurrenceData = rd)
            alarmScheduler.scheduleAlarm(id, f, t); activeAlarms.removeAll { it.id == id }; activeAlarms.add(n)
            
            val diffMs = f - System.currentTimeMillis()
            val diffMinutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(diffMs)
            val diffHours = diffMinutes / 60
            val remainingMinutes = diffMinutes % 60
            val diffDays = diffHours / 24
            val remainingHours = diffHours % 24

            val diffMsg = when {
                diffDays > 0 -> "Alarm set for $diffDays days, $remainingHours hours, and $remainingMinutes minutes from now"
                diffHours > 0 -> "Alarm set for $remainingHours hours and $remainingMinutes minutes from now"
                else -> "Alarm set for $remainingMinutes minutes from now"
            }
            Toast.makeText(context, diffMsg, Toast.LENGTH_LONG).show()

            onSave(); showEditDialog = false; alarmToEdit = null
        } else {
            Toast.makeText(context, "Cannot set alarm in the past!", Toast.LENGTH_SHORT).show()
        }
    })
    
    if (showLocationPrompt) {
        AlertDialog(
            onDismissRequest = onDismissLocationPrompt,
            title = { Text("Set Starting Location?") },
            text = { Text("You haven't set a starting location yet. Setting it lets alarms for in-person events fire early enough based on the travel time from your location to the event's location.") },
            confirmButton = {
                Button(onClick = { onDismissLocationPrompt(); selectedTab = 2 }) {
                    Icon(Icons.Default.Place, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set Location")
                }
            },
            dismissButton = { TextButton(onClick = onDismissLocationPrompt) { Text("Cancel") } }
        )
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { CenterAlignedTopAppBar(title = { Text("CalAlarm Sync", style = MaterialTheme.typography.headlineMedium) }, actions = { IconButton(onClick = { showAboutDialog = true }) { Icon(Icons.Default.Info, null) } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) },
        bottomBar = { NavigationBar {
            NavigationBarItem(icon = { Icon(Icons.Default.Alarm, null) }, label = { Text("Alarms") }, selected = selectedTab == 0, onClick = { selectedTab = 0 })
            NavigationBarItem(icon = { Icon(Icons.Default.CalendarToday, null) }, label = { Text("Calendars") }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
            NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") }, selected = selectedTab == 2, onClick = { selectedTab = 2 })
        }},
        floatingActionButton = {
            if (selectedTab == 0 || selectedTab == 1) {
                val isExpanded = if (selectedTab == 0) isAlarmsAtTop else isCalendarsAtTop
                val label = if (selectedTab == 0) "Create Alarm" else "Add Calendar"
                ExtendedFloatingActionButton(
                    onClick = { if (selectedTab == 0) showEditDialog = true else showAddAccountChoice = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = label) },
                    text = { Text(label) },
                    expanded = isExpanded
                )
            }
        }
    ) { p ->
        Box(modifier = Modifier.padding(p).fillMaxSize()) {
            when (selectedTab) {
                0 -> {
                    AlarmsTabScreen(
                        activeAlarms = activeAlarms,
                        onDelete = { 
                            alarmScheduler.cancelAlarm(it.id); activeAlarms.remove(it); onSave()
                            if (it.googleEventId != null) {
                                val isSeries = it.googleRecurrenceInfo != null
                                val rootId = if (it.googleRecurrenceInfo != "true" && it.googleRecurrenceInfo != null) it.googleRecurrenceInfo else if (isSeries) it.googleEventId.split("_")[0] else it.googleEventId
                                excludedEvents.add(ExcludedEvent(rootId, it.message, isSeries, System.currentTimeMillis() + (90L * 24 * 60 * 60 * 1000)))
                                onSaveExcluded()
                            }
                        },
                        onEdit = { alarmToEdit = it; showEditDialog = true },
                        excludedEvents = excludedEvents,
                        onRestoreExcluded = onRestoreExcluded,
                        alarmScheduler = alarmScheduler,
                        onSave = onSave,
                        subTab = alarmsSubTab,
                        onSubTabChange = { alarmsSubTab = it },
                        upcomingListState = upcomingAlarmsState,
                        pastListState = pastAlarmsState
                    )
                }
                1 -> CalendarsTabScreen(cloudEvents, connectedAccounts, lastSyncTime, onDisconnectAccount, onTogglePrimary, onManualSync, alarmScheduler, activeAlarms, onSave, context, excludedEvents, onToggleAlarm, isSyncing, appSettings, onToggleSecondaryCalendar, fetchAvailableCalendars, listState = calendarsState)
                2 -> SettingsTabScreen(appSettings, onUpdateSettings)
            }
        }
    }
}

@Composable
fun AlarmsTabScreen(
    activeAlarms: MutableList<ScheduledAlarm>,
    onDelete: (ScheduledAlarm) -> Unit,
    onEdit: (ScheduledAlarm) -> Unit,
    excludedEvents: List<ExcludedEvent>,
    onRestoreExcluded: (ExcludedEvent) -> Unit,
    alarmScheduler: AlarmScheduler,
    onSave: () -> Unit,
    subTab: Int,
    onSubTabChange: (Int) -> Unit,
    upcomingListState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    pastListState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    var alarmToDelete by remember { mutableStateOf<ScheduledAlarm?>(null) }
    
    // Ticker to refresh screen every second for the countdown timer and layout movements
    var timeTicker by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            timeTicker = System.currentTimeMillis()
        }
    }
    val currentTime = timeTicker
    
    val upcoming = activeAlarms.filter { it.time > currentTime }.sortedBy { it.time }
    val past = activeAlarms.filter { it.time <= currentTime }.sortedByDescending { it.time }

    val context = androidx.compose.ui.platform.LocalContext.current
    val syncPrefs = remember { context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE) }
    var testAlarmDismissed by remember { mutableStateOf(syncPrefs.getBoolean("test_alarm_dismissed", false)) }
    var feedbackDialogShown by remember { mutableStateOf(false) }
    
    var showTestAlarmDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var showPermissionGuideDialog by remember { mutableStateOf(false) }
    
    // Auto-pop feedback dialog when a test alarm moves into the past
    LaunchedEffect(currentTime, upcoming, past) {
        val hasPastTest = past.any { it.message == "Test alarm to show on lock screen" }
        if (hasPastTest && !testAlarmDismissed && !feedbackDialogShown) {
            feedbackDialogShown = true
            showFeedbackDialog = true
        }
    }

    if (alarmToDelete != null) {
        AlertDialog(
            onDismissRequest = { alarmToDelete = null },
            title = { Text("Delete Alarm?") },
            text = { Text("Are you sure you want to delete this alarm?") },
            confirmButton = { Button(onClick = { onDelete(alarmToDelete!!); alarmToDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { alarmToDelete = null }) { Text("Cancel") } }
        )
    }

    if (showTestAlarmDialog) {
        AlertDialog(
            onDismissRequest = { showTestAlarmDialog = false },
            title = { Text("Prepare to Test") },
            text = { Text("A test alarm will be created 30 seconds from now. Please lock your device's screen and wait to see if the alarm ringer launches properly on top of the lock screen.") },
            confirmButton = {
                Button(onClick = {
                    showTestAlarmDialog = false
                    val triggerTime = System.currentTimeMillis() + 30_000L
                    val testAlarmId = 8888
                    alarmScheduler.scheduleAlarm(
                        id = testAlarmId,
                        timeInMillis = triggerTime,
                        message = "Test alarm to show on lock screen"
                    )
                    val newAlarm = ScheduledAlarm(
                        id = testAlarmId,
                        time = triggerTime,
                        message = "Test alarm to show on lock screen"
                    )
                    activeAlarms.add(newAlarm)
                    onSave()
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestAlarmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = {}, // Force user action
            title = { Text("Test Alarm Verification") },
            text = { Text("Did you see the full-screen alarm ringer overlay properly on your lock screen?") },
            confirmButton = {
                Button(onClick = {
                    showFeedbackDialog = false
                    testAlarmDismissed = true
                    syncPrefs.edit().putBoolean("test_alarm_dismissed", true).apply()
                    // Cleanup
                    alarmScheduler.cancelAlarm(8888)
                    onSave()
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFeedbackDialog = false
                    showPermissionGuideDialog = true
                }) {
                    Text("No")
                }
            }
        )
    }

    if (showPermissionGuideDialog) {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val isXiaomi = manufacturer.contains("xiaomi")
        val isKnownOem = isXiaomi || manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("vivo")
        
        AlertDialog(
            onDismissRequest = {
                showPermissionGuideDialog = false
                testAlarmDismissed = true
                syncPrefs.edit().putBoolean("test_alarm_dismissed", true).apply()
                alarmScheduler.cancelAlarm(8888)
                onSave()
            },
            title = { Text("Permission Settings Guide") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("On some custom Android skins (OEMs like Xiaomi, Oppo, Vivo, Realme), the system blocks background alarms from drawing overlays unless specific permissions are manually granted. Please enable:")
                    Spacer(Modifier.height(12.dp))
                    Text("• Other Permissions (Display pop-up window, Show on lock screen)\n• Auto-start / Startup apps\n• Battery optimization set to 'Unrestricted'", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    
                    // Action buttons
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (isXiaomi) {
                            Button(
                                onClick = { (context as MainActivity).openMIUIPermissions() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Other Permissions")
                            }
                        }
                        if (isKnownOem) {
                            Button(
                                onClick = { (context as MainActivity).openOEMSettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Auto-Start settings")
                            }
                        }
                        Button(
                            onClick = { (context as MainActivity).openSettings() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Battery Perms")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionGuideDialog = false
                    testAlarmDismissed = true
                    syncPrefs.edit().putBoolean("test_alarm_dismissed", true).apply()
                    alarmScheduler.cancelAlarm(8888)
                    onSave()
                }) {
                    Text("Done")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { onSubTabChange(0) }, text = { Text("Upcoming") })
            Tab(selected = subTab == 1, onClick = { onSubTabChange(1) }, text = { Text("Past") })
        }
        Spacer(Modifier.height(16.dp))
        
        if (subTab == 0) {
            // Show onboarding test alarm card for clean installs with no alarms
            if (!testAlarmDismissed && activeAlarms.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Verify Lock Screen Alarms", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Aggressive background managers on custom Android skins can block alarms. Run a 30-second test alarm to verify your ringer works when the screen is locked.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { showTestAlarmDialog = true }) {
                            Text("Run Test Alarm")
                        }
                    }
                }
            }
            
            if (upcoming.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(16.dp))
                        Text("No upcoming alarms", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Tap + to create a local alarm, or connect a calendar from the Calendars tab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(state = upcomingListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(upcoming) { AlarmCard(it, onEdit, { alarm -> alarmToDelete = alarm }) }
                }
            }
        } else if (subTab == 1) {
            if (past.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No past alarms.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                var showClearDialog by remember { mutableStateOf(false) }
                if (showClearDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearDialog = false },
                        title = { Text("Delete All Past Alarms?") },
                        text = { Text("Are you sure you want to delete all past alarms from the list?") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showClearDialog = false
                                    past.forEach { alarm ->
                                        alarmScheduler.cancelAlarm(alarm.id)
                                    }
                                    activeAlarms.removeAll { it.time <= currentTime }
                                    onSave()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete All")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showClearDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Delete All", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete All")
                        }
                    }
                    LazyColumn(state = pastListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                        items(past) { AlarmCard(it, onEdit, { alarm -> alarmToDelete = alarm }, isPast = true) }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarsTabScreen(
    cloudEvents: List<EventInfo>,
    accounts: List<ConnectedCloudAccount>,
    lastSyncTime: Long,
    onDisconnectAccount: (String) -> Unit,
    onTogglePrimary: (String, Boolean) -> Unit,
    onManualSync: () -> Unit,
    alarmScheduler: AlarmScheduler,
    activeAlarms: MutableList<ScheduledAlarm>,
    onSave: () -> Unit,
    context: Context,
    excludedEvents: List<ExcludedEvent>,
    onToggleAlarm: (EventInfo, Boolean) -> Unit,
    isSyncing: Boolean,
    appSettings: AppSettings = AppSettings(),
    onToggleSecondaryCalendar: ((String, String, Boolean) -> Unit)? = null,
    fetchAvailableCalendars: (suspend (ConnectedCloudAccount) -> List<com.nen.alarmsynccalendar.calendar.GoogleCalendarInfo>)? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault()); val dateSdf = SimpleDateFormat("EEE, MMM dd", Locale.getDefault())
    val syncSdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    var accountToDelete by remember { mutableStateOf<String?>(null) }
    val currentTime = System.currentTimeMillis()

    var activeTooltipTitle by remember { mutableStateOf<String?>(null) }
    var activeTooltipText by remember { mutableStateOf<String?>(null) }

    if (activeTooltipText != null) {
        AlertDialog(
            onDismissRequest = { activeTooltipText = null },
            title = { Text(activeTooltipTitle ?: "Permission Information") },
            text = { Text(activeTooltipText!!) },
            confirmButton = { TextButton(onClick = { activeTooltipText = null }) { Text("OK") } }
        )
    }

    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(animation = androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing)))

    if (accountToDelete != null) {
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = { Text("Delete Account?") },
            text = { Text("Are you sure you want to remove this account? All linked alarms created from this calendar will also be deleted.") },
            confirmButton = { Button(onClick = { onDisconnectAccount(accountToDelete!!); accountToDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { accountToDelete = null }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Calendars", style = MaterialTheme.typography.headlineSmall)
                if (lastSyncTime > 0) Text("Last sync: ${syncSdf.format(Date(lastSyncTime))} (60-day window)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onManualSync) { Icon(Icons.Default.Refresh, null, modifier = Modifier.graphicsLayer(rotationZ = if (isSyncing) angle else 0f)) }
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.height(16.dp))
        if (appSettings.enableSecondaryCalendars) {
            Text("Secondary calendars enabled. Check the secondary calendars under each account that should be synced.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else {
            Text("Only Primary calendar is synced, enable secondary calendars in settings to sync them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.height(16.dp))

        // Deleted diagnostics and restricted cards to place in SettingsTabScreen
        
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
            if (accounts.isEmpty()) {
                item { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No accounts connected. Tap + to add.") } }
            } else {
                items(accounts) { acc ->
                    var isExpanded by remember { mutableStateOf(false) }
                    val accountEvents = cloudEvents.filter {
                        it.accountEmail == acc.email && (if (it.isAllDay) (it.startTime + 24 * 60 * 60 * 1000L) > currentTime else (it.startTime - 5 * 60 * 1000L) > currentTime)
                    }.sortedBy { it.startTime }
                    
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Column {
                            Row(modifier = Modifier.padding(12.dp).clickable { isExpanded = !isExpanded }, verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(acc.email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${accountEvents.size} events in next 60 days", style = MaterialTheme.typography.labelSmall)
                                }
                                 val statusMsg = when (acc.syncStatus) {
                                     AccountSyncStatus.AUTH_ERROR -> "Authentication Error: Please disconnect and re-connect your account."
                                     AccountSyncStatus.TIMEOUT -> "Sync Timeout: The request took too long. Check your internet connection."
                                     AccountSyncStatus.NETWORK_ERROR -> "Network Error: Could not connect to servers. Showing cached events."
                                     else -> null
                                 }
                                 if (statusMsg != null) {
                                     IconButton(
                                         onClick = { Toast.makeText(context, statusMsg, Toast.LENGTH_LONG).show() },
                                         modifier = Modifier.padding(end = 8.dp).size(32.dp)
                                     ) {
                                         Icon(
                                             imageVector = when (acc.syncStatus) {
                                                 AccountSyncStatus.AUTH_ERROR -> Icons.Default.Error
                                                 AccountSyncStatus.TIMEOUT -> Icons.Default.Schedule
                                                 AccountSyncStatus.NETWORK_ERROR -> Icons.Default.CloudOff
                                                 else -> Icons.Default.Warning
                                             },
                                             contentDescription = statusMsg,
                                             tint = when (acc.syncStatus) {
                                                 AccountSyncStatus.AUTH_ERROR -> MaterialTheme.colorScheme.error
                                                 AccountSyncStatus.TIMEOUT -> Color(0xFFFF9800)
                                                 AccountSyncStatus.NETWORK_ERROR -> Color(0xFFFF6D00)
                                                 else -> Color.Gray
                                             },
                                             modifier = Modifier.size(24.dp)
                                         )
                                     }
                                 }
                                IconButton(onClick = { accountToDelete = acc.email }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            }
                            if (isExpanded) {
                                if (appSettings.enableSecondaryCalendars && fetchAvailableCalendars != null && onToggleSecondaryCalendar != null) {
                                    var availableCalendars by remember(acc.email, acc.cachedSecondaryCalendars) { mutableStateOf(acc.cachedSecondaryCalendars) }
                                    var isLoadingCalendars by remember { mutableStateOf(false) }
                                    var isSecondarySectionExpanded by remember { mutableStateOf(false) }

                                    LaunchedEffect(acc.email, appSettings.enableSecondaryCalendars) {
                                        if (appSettings.enableSecondaryCalendars && availableCalendars == null) {
                                            isLoadingCalendars = true
                                            val fetched = fetchAvailableCalendars(acc)
                                            if (fetched.isNotEmpty()) {
                                                availableCalendars = fetched
                                            }
                                            isLoadingCalendars = false
                                        }
                                    }

                                    LaunchedEffect(isSecondarySectionExpanded) {
                                        if (isSecondarySectionExpanded) {
                                            isLoadingCalendars = (availableCalendars == null)
                                            val fetched = fetchAvailableCalendars(acc)
                                            if (fetched.isNotEmpty()) {
                                                availableCalendars = fetched
                                            }
                                            isLoadingCalendars = false
                                        }
                                    }

                                    val secondaryCals = availableCalendars?.filter { !it.isPrimary } ?: emptyList()
                                    val syncedCount = secondaryCals.count { acc.safeSelectedSecondaryCalendarIds.contains(it.id) }
                                    val totalCount = secondaryCals.size

                                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { isSecondarySectionExpanded = !isSecondarySectionExpanded }
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isSecondarySectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (isSecondarySectionExpanded) "Collapse" else "Expand",
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (isLoadingCalendars && availableCalendars == null) "Secondary Calendars" else "Secondary Calendars ($syncedCount/$totalCount)",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        if (isSecondarySectionExpanded) {
                                            Spacer(Modifier.height(4.dp))
                                            if (isLoadingCalendars) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 28.dp)) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Loading calendars...", style = MaterialTheme.typography.bodySmall)
                                                }
                                            } else if (secondaryCals.isEmpty()) {
                                                Text(
                                                    text = "No secondary calendars found.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(start = 28.dp)
                                                )
                                            } else {
                                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                                    secondaryCals.forEach { cal ->
                                                        val isChecked = acc.safeSelectedSecondaryCalendarIds.contains(cal.id)
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    onToggleSecondaryCalendar(acc.email, cal.id, !isChecked)
                                                                }
                                                                .padding(vertical = 2.dp)
                                                        ) {
                                                            Checkbox(
                                                                checked = isChecked,
                                                                onCheckedChange = { checked ->
                                                                    onToggleSecondaryCalendar(acc.email, cal.id, checked)
                                                                }
                                                            )
                                                            Spacer(Modifier.width(8.dp))
                                                            Text(
                                                                text = cal.summary,
                                                                style = MaterialTheme.typography.bodyMedium
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Divider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                }

                                if (accountEvents.isEmpty()) {
                                    Text("No events found in primary calendar.", modifier = Modifier.padding(start = 48.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
                                } else {
                                    for (event in accountEvents) {
                                        val isAllDay = event.isAllDay
                                        val existing = if (isAllDay) null else activeAlarms.find { it.googleEventId == event.googleEventId }
                                        val seriesId = event.recurringEventId ?: event.googleEventId?.split("_")?.get(0)
                                        val isExcluded = excludedEvents.any { it.id == event.googleEventId || (seriesId != null && it.id == seriesId) }
                                        
                                        Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().alpha(if (isAllDay) 0.4f else if (isExcluded) 0.5f else 1f).clickable(enabled = !isAllDay) {
                                            onToggleAlarm(event, isExcluded || existing == null)
                                        }, verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                                if (isAllDay) {
                                                    Icon(Icons.Default.NotificationsOff, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                                } else if (isExcluded) {
                                                    Icon(Icons.Default.Block, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                                } else if (existing != null) {
                                                    Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                                } else if (event.isRecurring) {
                                                    Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (existing != null && !isExcluded && !isAllDay) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                    if (isAllDay) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                        ) {
                                                            Text(
                                                                "All Day",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    } else if (event.isRecurring) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.Repeat,
                                                            contentDescription = "Recurring Event",
                                                            tint = MaterialTheme.colorScheme.secondary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                                val dateText = if (isAllDay) dateSdf.format(Date(event.startTime)) else "${dateSdf.format(Date(event.startTime))} ${sdf.format(Date(event.startTime))}"
                                                Text(dateText, style = MaterialTheme.typography.labelSmall)
                                                TravelInfoRow(event.location, event.distanceKm, event.travelTimeMinutes, event.noDrivingRoute)
                                                if (!event.meetingLink.isNullOrBlank()) {
                                                    Spacer(Modifier.height(8.dp))
                                                    Button(
                                                        onClick = {
                                                            try {
                                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.meetingLink)).apply {
                                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                                }
                                                                context.startActivity(intent)
                                                            } catch (e: Exception) {
                                                                Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                        modifier = Modifier.height(32.dp),
                                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.VideoCall,
                                                            contentDescription = "Join Meeting",
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            text = "Join Meet",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            Switch(checked = existing != null && !isExcluded, onCheckedChange = { onToggleAlarm(event, it) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmCard(alarm: ScheduledAlarm, onEdit: (ScheduledAlarm) -> Unit, onDelete: (ScheduledAlarm) -> Unit, isPast: Boolean = false) {
    val sdf = SimpleDateFormat("EEE, MMM dd, HH:mm", Locale.getDefault())
    val context = androidx.compose.ui.platform.LocalContext.current
    
    var secondsLeft by remember(alarm.time) { mutableStateOf(((alarm.time - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)) }
    if (alarm.message == "Test alarm to show on lock screen" && secondsLeft > 0L) {
        LaunchedEffect(alarm.time) {
            while (secondsLeft > 0L) {
                kotlinx.coroutines.delay(200L)
                secondsLeft = ((alarm.time - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = if (isPast) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) else CardDefaults.cardColors()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Notifications, null, tint = if (isPast) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp).align(Alignment.TopStart))
                if (alarm.recurrenceType != RecurrenceType.NONE || alarm.googleRecurrenceInfo != null) {
                    Icon(Icons.Default.Repeat, null, tint = if (isPast) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp).align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape).padding(2.dp))
                }
            }
            Spacer(Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isLocal = alarm.googleEventId == null
                    Icon(
                        if (isLocal) Icons.Default.Smartphone else Icons.Default.CalendarToday,
                        null,
                        tint = if (isLocal) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(end = 8.dp).size(16.dp)
                    )
                    Text(text = alarm.message, style = MaterialTheme.typography.titleMedium, textDecoration = if (isPast) androidx.compose.ui.text.style.TextDecoration.LineThrough else null, modifier = Modifier.weight(1f), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (alarm.message == "Test alarm to show on lock screen" && secondsLeft > 0L) {
                    Spacer(Modifier.height(8.dp))
                    val progress = (secondsLeft.toFloat() / 30f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Triggering alarm in $secondsLeft seconds.\nPlease lock your screen! To verify alarm ringer works fine",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    val leadMinutes = alarm.eventStartTime?.let { ((it - alarm.time) / 60_000L).toInt() }?.takeIf { it > 0 }
                    Text(
                        text = sdf.format(Date(alarm.time)) + (leadMinutes?.let { " (${formatTravelTime(it)} before event)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TravelInfoRow(alarm.location, alarm.distanceKm, alarm.travelTimeMinutes, alarm.noDrivingRoute)
                if (!alarm.meetingLink.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(alarm.meetingLink)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoCall,
                            contentDescription = "Join Meeting",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Join Meet",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (!isPast && alarm.googleEventId == null) {
                FilledTonalIconButton(
                    onClick = { onEdit(alarm) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            FilledTonalIconButton(
                onClick = { onDelete(alarm) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit, onOpenOEM: () -> Unit, onOpenMIUIPermissions: () -> Unit) {
    val m = android.os.Build.MANUFACTURER.lowercase()
    val isXiaomi = m.contains("xiaomi")
    val isKnown = isXiaomi || m.contains("oppo") || m.contains("realme") || m.contains("vivo")
    val context = androidx.compose.ui.platform.LocalContext.current
    var showLogs by remember { mutableStateOf(false) }
    
    if (showLogs) {
        val logs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE).getString("history", "No logs found.") ?: ""
        val annotatedLogs = androidx.compose.ui.text.buildAnnotatedString {
            val lines = logs.split("\n")
            lines.forEachIndexed { index, line ->
                if (line.startsWith("[") && line.contains("]")) {
                    val closeBracketIndex = line.indexOf("]")
                    val timestamp = line.substring(0, closeBracketIndex + 1)
                    val rest = line.substring(closeBracketIndex + 1)
                    
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                    append(timestamp)
                    pop()
                    append(rest)
                } else {
                    append(line)
                }
                if (index < lines.lastIndex) {
                    append("\n")
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Background Sync Logs") },
            text = { 
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    item { Text(annotatedLogs, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                }
            },
            confirmButton = { TextButton(onClick = { showLogs = false }) { Text("Back") } },
            dismissButton = { TextButton(onClick = { context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE).edit().clear().apply(); showLogs = false }) { Text("Clear Logs", color = Color.Red) } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Text("CalAlarm Sync Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                    
                    Spacer(Modifier.height(16.dp))
                    Text("Device Reliability", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("For 100% reliability, ensure these are enabled in settings:", style = MaterialTheme.typography.bodySmall)
                    Text("• Show on Lock screen\n• Display over other apps\n• Auto-start\n• Battery: 'Unrestricted'", style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (isXiaomi) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = onOpenMIUIPermissions, modifier = Modifier.weight(1f)) {
                                    Text("Other Perms")
                                }
                                Button(onClick = onOpenOEM, modifier = Modifier.weight(1f)) {
                                    Text("Auto-Start")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                                    Text("Battery Perms")
                                }
                                OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.weight(1f)) {
                                    Text("View Logs")
                                }
                            }
                        } else if (isKnown) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = onOpenOEM, modifier = Modifier.weight(1f)) {
                                    Text("Auto-Start")
                                }
                                Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                                    Text("Battery Perms")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("View Logs")
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                                    Text("Battery Perms")
                                }
                                OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.weight(1f)) {
                                    Text("View Logs")
                                }
                            }
                        }
                    }
                    
                    val syncPrefs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
                    val lastExecutionTime = syncPrefs.getLong("last_execution_time", 0L)
                    val firstRunTime = syncPrefs.getLong("first_run_time", 0L)
                    val currentTime = System.currentTimeMillis()
                    val threshold = 200 * 60 * 1000L // 200 minutes (3h 20m doze buffer)
                    val snoozeUntil = syncPrefs.getLong("snooze_until", 0L)

                    val showWarning = if (currentTime < snoozeUntil) {
                        false
                    } else if (lastExecutionTime == 0L) {
                        firstRunTime > 0L && (currentTime - firstRunTime > threshold)
                    } else {
                        currentTime - lastExecutionTime > threshold
                    }

                    if (showWarning) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "⚠️ Background sync has not run recently. Please check that background permissions (Unrestricted battery, Auto-start) are enabled in App Info, and that the app is locked in Recents (padlock icon).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    Text("Project Links", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/uniquer/alarm_sync_calendar"))) }
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Source Code",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Source Code (GitHub)",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://genforgelab.com/"))) }
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Website",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Website",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    var showInputMode by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showInputMode) "Enter Time" else "Select Time",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { showInputMode = !showInputMode }) {
                    Icon(
                        imageVector = if (showInputMode) Icons.Default.Schedule else Icons.Default.Keyboard,
                        contentDescription = "Switch Input Mode"
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showInputMode) {
                    TimeInput(state = state)
                } else {
                    TimePicker(state = state)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditDialog(existingAlarm: ScheduledAlarm?, onDismiss: () -> Unit, onConfirm: (String, Long, RecurrenceType, Int?) -> Unit) {
    var title by remember { mutableStateOf(existingAlarm?.message ?: "") }; var recurrenceType by remember { mutableStateOf(existingAlarm?.recurrenceType ?: RecurrenceType.NONE) }
    val calendar = remember { Calendar.getInstance().apply { if (existingAlarm != null) timeInMillis = existingAlarm.time } }
    var selectedDate by remember { mutableStateOf(calendar.time) }; var selectedTime by remember { mutableStateOf(calendar.time) }
    var showTimePicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current; val dateSdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()); val timeSdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
    
    if (showTimePicker) {
        M3TimePickerDialog(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            onDismiss = { showTimePicker = false },
            onConfirm = { h, m ->
                calendar.set(Calendar.HOUR_OF_DAY, h)
                calendar.set(Calendar.MINUTE, m)
                selectedTime = calendar.time
                showTimePicker = false
            }
        )
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existingAlarm == null) "Manual Alarm" else "Edit Alarm") }, text = { LazyColumn { item {
        OutlinedTextField(value = title, onValueChange = { if (it.length <= 50) title = it }, label = { Text("Title (${title.length}/50)") }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().clickable { DatePickerDialog(context, { _, y, m, d -> calendar.set(y, m, d); selectedDate = calendar.time }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show() }.padding(8.dp)) { Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(16.dp)); Text("Date: ${dateSdf.format(selectedDate)}") }
        Row(modifier = Modifier.padding(8.dp).fillMaxWidth().clickable { showTimePicker = true }) { Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(16.dp)); Text("Time: ${timeSdf.format(selectedTime)}") }
        Spacer(Modifier.height(16.dp)); FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(RecurrenceType.NONE, RecurrenceType.DAILY, RecurrenceType.WEEKLY, RecurrenceType.MONTHLY).forEach { type -> FilterChip(selected = recurrenceType == type, onClick = { recurrenceType = type }, label = { Text(type.name) }) } }
        if (recurrenceType == RecurrenceType.WEEKLY) { Spacer(Modifier.height(8.dp)); FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { i, n -> AssistChip(onClick = { calendar.set(Calendar.DAY_OF_WEEK, i+1); selectedDate = calendar.time }, label = { Text(n) }, colors = if (calendar.get(Calendar.DAY_OF_WEEK) == i+1) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else AssistChipDefaults.assistChipColors()) } } }
    }}}, confirmButton = { Button(onClick = { val rd = if(recurrenceType == RecurrenceType.WEEKLY) calendar.get(Calendar.DAY_OF_WEEK) else if(recurrenceType == RecurrenceType.MONTHLY) calendar.get(Calendar.DAY_OF_MONTH) else null; onConfirm(title.ifBlank { "Manual" }, calendar.timeInMillis, recurrenceType, rd) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Formats travel minutes for display, e.g. 100 -> "1hr 40mins", 30 -> "30mins". */
fun formatTravelTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}hr ${m}mins"
        h > 0 -> "${h}hr"
        else -> "${m}mins"
    }
}

/** Location + driving distance/time line shown on in-person events and their alarms. */
@Composable
fun TravelInfoRow(location: String?, distanceKm: Double?, travelTimeMinutes: Int?, noDrivingRoute: Boolean? = null) {
    // Re-filter here too: alarms saved before the placeholder filter existed may
    // still carry values like "online" — never show those or the Map button.
    val physicalLocation = com.nen.alarmsynccalendar.calendar.MeetingUtils.extractPhysicalLocation(location) ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Place, contentDescription = "Location", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(physicalLocation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    val isRoomLike = com.nen.alarmsynccalendar.calendar.MeetingUtils.isRoomLikeLocation(physicalLocation)
    if (isRoomLike) {
        Text(
            "Travel check skipped — not a street address",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        return
    }
    val isLongTrip = noDrivingRoute == true || (distanceKm != null && distanceKm > LONG_TRIP_THRESHOLD_KM)
    if (isLongTrip || (distanceKm != null && travelTimeMinutes != null)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsCar, contentDescription = "Travel", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                when {
                    isLongTrip && distanceKm != null ->
                        String.format(Locale.getDefault(), "%.0f km • Alarm set 24hrs before to plan travel", distanceKm)
                    isLongTrip ->
                        "Long trip • Alarm set 24hrs before to plan travel"
                    else ->
                        String.format(Locale.getDefault(), "%.1f km • Travel Time: %s", distanceKm, formatTravelTime(travelTimeMinutes!!))
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isLongTrip) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
            )
        }
    }
    if (isLongTrip) return
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            val settings = AppSettings.load(context)
            val dest = java.net.URLEncoder.encode(physicalLocation, "UTF-8")
            // Omitting origin makes Google Maps default to the user's current position.
            val origin = if (settings.hasStartLocation) "&origin=${settings.startLocationLat},${settings.startLocationLng}" else ""
            val url = "https://www.google.com/maps/dir/?api=1$origin&destination=$dest&travelmode=driving"
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open Maps", Toast.LENGTH_SHORT).show()
            }
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Map,
            contentDescription = "Open in Maps",
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Map",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabScreen(settings: AppSettings, onUpdateSettings: (AppSettings) -> Unit) {
    var showClearConfirm by remember { mutableStateOf(false) }
    var showLocationSearch by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    var activeTooltipTitle by remember { mutableStateOf<String?>(null) }
    var activeTooltipText by remember { mutableStateOf<String?>(null) }

    if (activeTooltipText != null) {
        AlertDialog(
            onDismissRequest = { activeTooltipText = null },
            title = { Text(activeTooltipTitle ?: "Diagnostics Info") },
            text = { Text(activeTooltipText!!) },
            confirmButton = { TextButton(onClick = { activeTooltipText = null }) { Text("OK") } }
        )
    }

    if (showLocationSearch) {
        LocationSearchDialog(
            onDismiss = { showLocationSearch = false },
            onSelected = { details ->
                showLocationSearch = false
                val isChange = settings.hasStartLocation
                onUpdateSettings(settings.copy(
                    startLocationName = details.name,
                    startLocationLat = details.lat,
                    startLocationLng = details.lng
                ))
                Toast.makeText(
                    context,
                    if (isChange) "Starting location updated. Your alarms will be adjusted based on the new travel times."
                    else "Starting location set. Alarms for in-person events will now include travel time.",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Starting Location?") },
            text = { Text("Without a starting location, travel time can't be calculated. Alarms for in-person events will be rescheduled to fire at the prep buffer before the event, without any travel time.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onUpdateSettings(settings.copy(startLocationName = null, startLocationLat = null, startLocationLng = null))
                        Toast.makeText(context, "Starting location cleared. Alarms rescheduled without travel time.", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text("Configure when alarms fire before your calendar events.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(16.dp))

        val hasCalendarPermission = context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val hasNotificationsPermission = notificationManager.areNotificationsEnabled()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isBatteryIgnoringOptimizations = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) powerManager.isIgnoringBatteryOptimizations(context.packageName) else true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val hasExactAlarmPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

        val allPermissionsEnabled = hasCalendarPermission && hasNotificationsPermission && isBatteryIgnoringOptimizations && hasExactAlarmPermission
        var isDiagnosticsExpanded by remember { mutableStateOf(false) }

        val syncPrefs = context.getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        val lastExecutionTime = syncPrefs.getLong("last_execution_time", 0L)
        val firstRunTime = syncPrefs.getLong("first_run_time", 0L)
        val currentTime = System.currentTimeMillis()
        val threshold = 200 * 60 * 1000L // 200 minutes (3h 20m doze buffer)
        var snoozeUntil by remember { mutableStateOf(syncPrefs.getLong("snooze_until", 0L)) }

        val showWarning = if (currentTime < snoozeUntil) {
            false
        } else if (lastExecutionTime == 0L) {
            firstRunTime > 0L && (currentTime - firstRunTime > threshold)
        } else {
            currentTime - lastExecutionTime > threshold
        }

        if (showWarning) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Background Sync Restricted",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Background sync has not run recently. On some devices (like Xiaomi, Oppo), you must grant Other Permissions (Display pop-up window/Show on lock screen), enable Auto-start, set Battery to 'Unrestricted' in settings, and lock the app in Recents (padlock icon) to allow syncing.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                                val isKnown = manufacturer.contains("xiaomi") || manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("vivo")
                                if (isKnown) {
                                    (context as? MainActivity)?.openOEMSettings()
                                } else {
                                    (context as? MainActivity)?.openSettings()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Fix Settings")
                        }
                        
                        OutlinedButton(
                            onClick = {
                                val newSnooze = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                                syncPrefs.edit().putLong("snooze_until", newSnooze).apply()
                                snoozeUntil = newSnooze
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ) {
                            Text("Snooze for 24 hrs")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isDiagnosticsExpanded = !isDiagnosticsExpanded }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isDiagnosticsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isDiagnosticsExpanded) "Collapse" else "Expand"
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Background Sync Diagnostics",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (allPermissionsEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = if (allPermissionsEnabled) "All Permissions Enabled" else "Some Permissions Missing",
                        tint = if (allPermissionsEnabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isDiagnosticsExpanded) {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        DiagnosticItem(
                            name = "Calendar Access",
                            isEnabled = hasCalendarPermission,
                            tooltipTitle = "Calendar Access",
                            tooltipText = "Enables the App to read events from your connected local and cloud calendars so it can sync them to your device as physical alarms.",
                            onShowTooltip = { title, text -> activeTooltipTitle = title; activeTooltipText = text }
                        )
                        Spacer(Modifier.height(4.dp))
                        DiagnosticItem(
                            name = "Notifications",
                            isEnabled = hasNotificationsPermission,
                            tooltipTitle = "Notifications Permission",
                            tooltipText = "Enables the App to show a persistent status or alert when an alarm goes off, and notify you if a background sync fails or requires attention.",
                            onShowTooltip = { title, text -> activeTooltipTitle = title; activeTooltipText = text }
                        )
                        Spacer(Modifier.height(4.dp))
                        DiagnosticItem(
                            name = "Battery Optimization",
                            isEnabled = isBatteryIgnoringOptimizations,
                            tooltipTitle = "Battery Optimization",
                            tooltipText = "Disabling battery optimization (setting to 'Unrestricted') allows Android to run the background sync worker at the scheduled times, preventing it from being killed when the phone is idle.",
                            onShowTooltip = { title, text -> activeTooltipTitle = title; activeTooltipText = text }
                        )
                        Spacer(Modifier.height(4.dp))
                        DiagnosticItem(
                            name = "Exact Alarms",
                            isEnabled = hasExactAlarmPermission,
                            tooltipTitle = "Exact Alarms Permission",
                            tooltipText = "Allows the App to schedule the 2-hour fallback alarm with precise sub-second timing, ensuring a sync runs even if the standard Android WorkManager is delayed.",
                            onShowTooltip = { title, text -> activeTooltipTitle = title; activeTooltipText = text }
                        )
                        Spacer(Modifier.height(4.dp))
                        DiagnosticItem(
                            name = "Auto-Start (OEM Settings)",
                            isEnabled = null,
                            tooltipTitle = "Auto-Start (OEM Settings)",
                            tooltipText = "On certain custom Android skins (like Xiaomi/MIUI, Oppo, OnePlus), you must manually allow the app to Auto-Start. Otherwise, all background timers and system wakeup events will be blocked by the system.",
                            onShowTooltip = { title, text -> activeTooltipTitle = title; activeTooltipText = text }
                        )

                        Spacer(Modifier.height(16.dp))

                        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                        val isXiaomi = manufacturer.contains("xiaomi")
                        val isKnownOem = isXiaomi || manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("vivo")
                        val mainActivity = context as? MainActivity

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (isXiaomi) {
                                Button(
                                    onClick = { mainActivity?.openMIUIPermissions() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Other Perms")
                                }
                            }
                            if (isKnownOem) {
                                Button(
                                    onClick = { mainActivity?.openOEMSettings() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Auto-Start")
                                }
                            }
                            Button(
                                onClick = { mainActivity?.openSettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                    Text("Battery Perms")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Secondary Calendars", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.enableSecondaryCalendars,
                        onCheckedChange = { enabled ->
                            onUpdateSettings(settings.copy(enableSecondaryCalendars = enabled))
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enabling secondary calendars could sync more alarms. Ensure to enable only those calendars that need syncing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.enableSecondaryCalendars) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VideoCall, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Online Meetings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text("Alarm lead time for events that only have a meeting link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Lead time", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15).forEach { m ->
                        FilterChip(
                            selected = settings.onlineLeadMinutes == m,
                            onClick = { onUpdateSettings(settings.copy(onlineLeadMinutes = m)) },
                            label = { Text("$m min") }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("In-Person Meetings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "For events with a physical location, the alarm fires at:\nevent start − driving time − prep buffer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("Prep buffer", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15).forEach { m ->
                        FilterChip(
                            selected = settings.offlineBufferMinutes == m,
                            onClick = { onUpdateSettings(settings.copy(offlineBufferMinutes = m)) },
                            label = { Text("$m min") }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
                Text("Starting Location", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (settings.hasStartLocation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(settings.startLocationName ?: "Location set", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showLocationSearch = true }) { Text("Change") }
                        TextButton(onClick = { showClearConfirm = true }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    Text(
                        "Providing your location (home or office) helps determine the travel time offset to ring your alarms early enough for in-person events.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showLocationSearch = true }) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Set Location")
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun LocationSearchDialog(onDismiss: () -> Unit, onSelected: (com.nen.alarmsynccalendar.maps.PlaceDetails) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.nen.alarmsynccalendar.maps.PlaceSuggestion>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isResolving by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        searchError = null
        if (query.length < 3) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(400) // debounce keystrokes before hitting the Places API
        isSearching = true
        val result = com.nen.alarmsynccalendar.maps.MapsService.autocomplete(query)
        isSearching = false
        results = result.suggestions
        searchError = result.error
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Starting Location") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search address or place") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = searchError != null,
                    trailingIcon = {
                        if (isSearching || isResolving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Search, null)
                    }
                )
                Spacer(Modifier.height(8.dp))
                if (searchError != null) {
                    Text(searchError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else if (results.isEmpty() && query.length >= 3 && !isSearching) {
                    Text("No results found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(results) { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isResolving) {
                                    scope.launch {
                                        isResolving = true
                                        val result = com.nen.alarmsynccalendar.maps.MapsService.placeDetails(suggestion.placeId)
                                        isResolving = false
                                        val details = result.details
                                        if (details != null) onSelected(details)
                                        else searchError = result.error ?: "Could not fetch location details. Try again."
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(suggestion.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DiagnosticItem(
    name: String,
    isEnabled: Boolean?,
    tooltipTitle: String,
    tooltipText: String,
    onShowTooltip: (String, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onShowTooltip(tooltipTitle, tooltipText) }
            )
        }
        
        val (statusText, statusColor) = when (isEnabled) {
            true -> "Enabled" to Color(0xFF2E7D32)
            false -> "Not Enabled" to Color(0xFFC62828)
            null -> "Verify Manually" to Color(0xFFE65100)
        }
        
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = statusColor
        )
    }
}
