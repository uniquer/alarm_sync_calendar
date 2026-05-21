package com.nen.alarmsynccalendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.nen.alarmsynccalendar.sync.SyncWorker
import com.nen.alarmsynccalendar.ui.theme.AlarmSyncCalendarTheme
import net.openid.appauth.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var authService: AuthorizationService

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).result
                if (account?.email != null) {
                    viewModel.addAccount(ConnectedCloudAccount(account.email!!, CloudProvider.GOOGLE))
                }
            }
        }

    private val outlookSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val response = AuthorizationResponse.fromIntent(result.data!!)
                if (response != null) {
                    viewModel.isSyncing = true
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

                            viewModel.addAccount(ConnectedCloudAccount(
                                email, CloudProvider.OUTLOOK, true,
                                tokenResponse.accessToken, tokenResponse.refreshToken
                            ))
                        } else {
                            viewModel.isSyncing = false
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authService = AuthorizationService(this)

        val syncPrefs = getSharedPreferences("sync_logs", Context.MODE_PRIVATE)
        if (!syncPrefs.contains("first_run_time")) {
            syncPrefs.edit().putLong("first_run_time", System.currentTimeMillis()).apply()
        }

        scheduleSync()
        checkBatteryOptimization()

        val permissions = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent {
            AlarmSyncCalendarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box {
                        MainScreen(
                            alarmScheduler = viewModel.alarmScheduler,
                            context = this@MainActivity,
                            activeAlarms = viewModel.activeAlarms,
                            cloudEvents = viewModel.cloudEvents,
                            isCloudSignedIn = viewModel.isCloudSignedIn,
                            connectedAccounts = viewModel.connectedAccounts,
                            lastSyncTime = viewModel.lastSyncTime,
                            onGoogleSignIn = { signInGoogle() },
                            onOutlookSignIn = { signInOutlook() },
                            onDisconnectAccount = { disconnectAccount(it) },
                            onTogglePrimary = { email, enabled -> viewModel.updatePrimaryEnable(email, enabled) },
                            onManualSync = { viewModel.refreshCloudEvents(isManual = true) },
                            onSave = { viewModel.saveAlarms() },
                            excludedEvents = viewModel.excludedEvents,
                            onRestoreExcluded = {
                                viewModel.excludedEvents.remove(it)
                                viewModel.saveExcluded()
                                viewModel.refreshCloudEvents(isManual = true)
                            },
                            onSaveExcluded = { viewModel.saveExcluded() },
                            onToggleAlarm = { event, enabled -> viewModel.toggleEventAlarm(event, enabled) },
                            isSyncing = viewModel.isSyncing
                        )

                        if (viewModel.isSyncing && viewModel.cloudEvents.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f))
                                    .clickable(enabled = false) {},
                                contentAlignment = Alignment.Center
                            ) {
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

    override fun onResume() {
        super.onResume()
        // Pick up any alarm changes written by SyncWorker while the app was in the background.
        viewModel.loadAlarms()
        val staleSyncThreshold = 15 * 60 * 1000L
        if (viewModel.isCloudSignedIn && System.currentTimeMillis() - viewModel.lastSyncTime > staleSyncThreshold) {
            viewModel.refreshCloudEvents()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        authService.dispose()
    }

    // ── Sign-in ───────────────────────────────────────────────────────────────

    private fun signInGoogle() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/calendar.readonly"))
            .build()
        val client = GoogleSignIn.getClient(this, options)
        client.signOut().addOnCompleteListener { googleSignInLauncher.launch(client.signInIntent) }
    }

    private fun signInOutlook() {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token")
        )
        val req = AuthorizationRequest.Builder(
            config,
            "acbc12d9-d41d-4df2-8517-57bdfdd3b0df",
            ResponseTypeValues.CODE,
            Uri.parse("msauth://com.nen.alarmsynccalendar/1NqMWNmdbXBPmEnKVGhIDOnHqaA%3D")
        ).setScopes("openid", "profile", "email", "offline_access", "Calendars.Read").build()
        outlookSignInLauncher.launch(authService.getAuthorizationRequestIntent(req))
    }

    private fun disconnectAccount(email: String) {
        val acc = viewModel.connectedAccounts.find { it.email == email } ?: return
        viewModel.disconnectAccount(email)
        if (acc.provider == CloudProvider.GOOGLE) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(this, gso).revokeAccess().addOnCompleteListener {
                GoogleSignIn.getClient(this, gso).signOut()
            }
        }
    }

    // ── Infrastructure ────────────────────────────────────────────────────────

    private fun scheduleSync() {
        val data = Data.Builder()
            .putString("trigger", "Timer Triggered (Periodic)")
            .build()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(data)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("CalendarSync", ExistingPeriodicWorkPolicy.UPDATE, req)

        // Also schedule the initial fallback alarm for the background sync loop
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, com.nen.alarmsynccalendar.sync.SyncTriggerReceiver::class.java)
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            999,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + (30 * 60 * 1000L),
                pendingIntent
            )
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        // Only prompt once — on Xiaomi (and other OEMs) isIgnoringBatteryOptimizations
        // always returns false regardless of the user's choice, so without this guard
        // the dialog fires on every launch.
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_prompted", false)) return
        prefs.edit().putBoolean("battery_opt_prompted", true).apply()

        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .apply { data = Uri.parse("package:$packageName") }
            )
        } catch (e: Exception) { /* setting not available on this device */ }
    }

    fun openSettings() {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .apply { data = Uri.fromParts("package", packageName, null) }
        )
    }

    fun openOEMSettings() {
        val m = android.os.Build.MANUFACTURER.lowercase()
        try {
            val i = Intent()
            when {
                m.contains("xiaomi") -> i.component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                m.contains("oppo") || m.contains("realme") -> i.component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
                else -> { openSettings(); return }
            }
            startActivity(i)
        } catch (e: Exception) { openSettings() }
    }
}
