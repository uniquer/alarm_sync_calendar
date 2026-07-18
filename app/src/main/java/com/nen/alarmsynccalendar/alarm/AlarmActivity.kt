package com.nen.alarmsynccalendar.alarm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import android.os.Build
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.net.Uri

import android.app.NotificationManager
import android.content.Intent
import android.app.KeyguardManager

class AlarmActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { dismissAlarm() }
    private var alarmId: Int = -1

    private var currentVolume = 0.0f
    private val volumeHandler = Handler(Looper.getMainLooper())
    private val volumeRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                if (currentVolume < 1.0f) {
                    currentVolume = (currentVolume + 0.10f).coerceAtMost(1.0f)
                    mp.setVolume(currentVolume, currentVolume)
                    volumeHandler.postDelayed(this, 1000L)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val message = intent.getStringExtra("ALARM_MESSAGE") ?: "Meeting Alarm!"
        val meetingLink = intent.getStringExtra("ALARM_MEETING_LINK")
        // Sanitize so stale placeholder locations ("online") never show at ring time
        val location = com.nen.alarmsynccalendar.calendar.MeetingUtils.extractPhysicalLocation(intent.getStringExtra("ALARM_LOCATION"))
        val travelMinutes = if (intent.hasExtra("ALARM_TRAVEL_MINUTES")) intent.getIntExtra("ALARM_TRAVEL_MINUTES", 0) else null
        val distanceKm = if (intent.hasExtra("ALARM_DISTANCE_KM")) intent.getDoubleExtra("ALARM_DISTANCE_KM", 0.0) else null
        val noRoute = intent.getBooleanExtra("ALARM_NO_ROUTE", false)
        alarmId = intent.getIntExtra("ALARM_ID", -1)

        // Wake the screen and show over lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start Ringtone with MediaPlayer for continuous looping
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(0.0f, 0.0f)
                prepare()
                start()
            }
            volumeHandler.post(volumeRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Start Vibration
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 500, 500), 0)
        }

        // Auto-dismiss the alarm after 2 minutes (120 seconds) to save battery
        autoDismissHandler.postDelayed(autoDismissRunnable, 2 * 60 * 1000L)

        setContent {
            AlarmScreen(
                message = message,
                meetingLink = meetingLink,
                location = location,
                travelMinutes = travelMinutes,
                isLongTrip = noRoute || (distanceKm != null && distanceKm > com.nen.alarmsynccalendar.LONG_TRIP_THRESHOLD_KM),
                onDismiss = { dismissAlarm() },
                onJoin = { link -> joinMeetingAndDismiss(link) },
                onCheckMap = { loc -> openMapAndDismiss(loc) }
            )
        }
    }

    private fun dismissAlarm() {
        try {
            volumeHandler.removeCallbacks(volumeRunnable)
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
            autoDismissHandler.removeCallbacks(autoDismissRunnable)
            
            if (alarmId != -1) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(alarmId)
            }
            val appIntent = Intent(this, com.nen.alarmsynccalendar.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_TAB", "past")
            }
            startActivity(appIntent)
            finishAndRemoveTask()
        } catch (e: Exception) {
            finish()
        }
    }

    private fun joinMeetingAndDismiss(meetingLink: String) {
        try {
            volumeHandler.removeCallbacks(volumeRunnable)
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
            autoDismissHandler.removeCallbacks(autoDismissRunnable)
            
            if (alarmId != -1) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(alarmId)
            }
            
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(meetingLink)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finishAndRemoveTask()
        } catch (e: Exception) {
            finish()
        }
    }

    private fun openMapAndDismiss(location: String) {
        try {
            volumeHandler.removeCallbacks(volumeRunnable)
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
            autoDismissHandler.removeCallbacks(autoDismissRunnable)

            if (alarmId != -1) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(alarmId)
            }

            val settings = com.nen.alarmsynccalendar.AppSettings.load(this)
            val dest = java.net.URLEncoder.encode(location, "UTF-8")
            // Omitting origin makes Google Maps default to the user's current position.
            val origin = if (settings.hasStartLocation) "&origin=${settings.startLocationLat},${settings.startLocationLng}" else ""
            val url = "https://www.google.com/maps/dir/?api=1$origin&destination=$dest&travelmode=driving"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finishAndRemoveTask()
        } catch (e: Exception) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        volumeHandler.removeCallbacks(volumeRunnable)
        mediaPlayer?.release()
        vibrator?.cancel()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
    }
}



@Composable
fun AlarmScreen(message: String, meetingLink: String?, location: String?, travelMinutes: Int?, isLongTrip: Boolean, onDismiss: () -> Unit, onJoin: (String) -> Unit, onCheckMap: (String) -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        surface = Color.Black,
        onSurface = Color.White,
        background = Color.Black,
        onBackground = Color.White
    )

    MaterialTheme(colorScheme = darkColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = message,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 48.sp,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (location != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when {
                                isLongTrip -> "Set 24hrs before to plan travel to $location"
                                travelMinutes != null -> "${com.nen.alarmsynccalendar.formatTravelTime(travelMinutes)} to $location"
                                else -> location
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (location != null && !isLongTrip && !com.nen.alarmsynccalendar.calendar.MeetingUtils.isRoomLikeLocation(location)) {
                        Button(
                            onClick = { onCheckMap(location) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1565C0), // Material Blue for navigation
                                contentColor = Color.White
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Map, null, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "CHECK MAP",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (!meetingLink.isNullOrBlank()) {
                        Button(
                            onClick = { onJoin(meetingLink) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32), // Premium Material Green
                                contentColor = Color.White
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VideoCall, null, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "JOIN THE MEET",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "DISMISS",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}
