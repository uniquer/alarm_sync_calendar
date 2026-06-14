package com.nen.alarmsynccalendar.alarm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
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
                    currentVolume = (currentVolume + 0.05f).coerceAtMost(1.0f)
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

        // Auto-dismiss the alarm after 1 minute to save battery
        autoDismissHandler.postDelayed(autoDismissRunnable, 1 * 60 * 1000L)

        setContent {
            AlarmScreen(
                message = message,
                meetingLink = meetingLink,
                onDismiss = { dismissAlarm() },
                onJoin = { link -> joinMeetingAndDismiss(link) }
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
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(homeIntent)
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

    override fun onDestroy() {
        super.onDestroy()
        volumeHandler.removeCallbacks(volumeRunnable)
        mediaPlayer?.release()
        vibrator?.cancel()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
    }
}



@Composable
fun AlarmScreen(message: String, meetingLink: String?, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
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
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    text = message,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 56.sp
                )
                Spacer(modifier = Modifier.height(60.dp))
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
