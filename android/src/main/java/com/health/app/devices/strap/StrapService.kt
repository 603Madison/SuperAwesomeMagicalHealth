package com.health.app.devices.strap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.health.app.Container
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.attempt
import com.health.app.container
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Keeps the app running while the strap records ([StrapRecorder]), screen off and all night: a
 * foreground service, with a "Recording heart rate" notification and its Stop button. If Android
 * closes the app mid-recording it starts this again, and the recording carries on.
 */
class StrapService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = applicationContext.container
        if (intent?.action == ACTION_STOP) {
            c.strapRecorder.stop()
            finish()
            return START_NOT_STICKY
        }
        // Foreground first, always: Android expects it of a service started to be one.
        val shown = attempt {
            ServiceCompat.startForeground(this, NOTIFICATION, StrapNotification.build(this, c.strapStore.recording.value?.since, StrapLive(), c.zone()), TYPE)
        }
        // Started from Record, or again by Android (no intent) after it closed the app mid-recording.
        val wanted = intent != null || c.strapStore.recording.value != null
        if (shown.isFailure || !wanted || c.strapStore.strap.value == null || !c.settings.value.signedIn || !ColmiConduit.hasPermission(this)) {
            shown.exceptionOrNull()?.let { Log.w(TAG, "Couldn't keep recording in the background", it) }
            c.strapRecorder.stop()
            finish()
            return START_NOT_STICKY
        }
        c.strapRecorder.start()
        watch(c)
        return START_STICKY
    }

    /** Keeps the notification current, and the phone awake enough to reconnect and send; stops when the recording does. */
    private fun watch(c: Container) {
        if (watching?.isActive == true) return
        watching = scope.launch {
            launch {
                c.strapStore.recording.first { it == null }
                finish()
            }
            var shown: Pair<String, String>? = null
            while (true) {
                keepAwake()
                val since = c.strapStore.recording.value?.since
                val live = c.strapRecorder.live.value
                val words = StrapNotification.words(since, live, c.zone())
                if (words != shown && StrapNotification.canNotify(this@StrapService)) {
                    attempt { StrapNotification.show(this@StrapService, since, live, c.zone()) }
                    shown = words
                }
                delay(REFRESH_MS)
            }
        }
    }

    /**
     * A strap sends every second, which wakes the phone anyway; this keeps it awake between, so a
     * dropped strap is reconnected and readings sent on time. Renewed while recording, so it can't
     * outlast it by more than a few minutes.
     */
    private fun keepAwake() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "health:strap-recording")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        attempt { lock?.acquire(AWAKE_MS) }
    }

    private fun finish() {
        watching?.cancel()
        watching = null
        attempt { wakeLock?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        attempt { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StrapService"
        const val ACTION_START = "com.health.app.strap.START"
        const val ACTION_STOP = "com.health.app.strap.STOP"
        const val NOTIFICATION = 4401
        private const val REFRESH_MS = 15_000L
        private const val AWAKE_MS = 5 * 60_000L

        private val TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

        /** Starts recording (the app is on screen: Record was pressed, or the app opened mid-recording). */
        fun start(context: Context): Boolean = attempt {
            ContextCompat.startForegroundService(context, Intent(context, StrapService::class.java).setAction(ACTION_START))
        }.onFailure { Log.w(TAG, "Couldn't start recording", it) }.isSuccess

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, NOTIFICATION, Intent(context, StrapService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The ongoing notification while recording. */
object StrapNotification {
    private const val CHANNEL = "heart-rate-strap"

    /** Its title and line: since when, and the heart rate or what's keeping it. */
    fun words(since: Long?, live: StrapLive, zone: ZoneId): Pair<String, String> {
        val from = since?.let { "Since ${Format.timeOfDay(it, zone)}" }
        val now = when {
            !live.connected -> "looking for your strap…"
            !live.touching -> "the strap isn't touching skin"
            live.bpm != null -> "${live.bpm} bpm"
            else -> null
        }
        return "Recording heart rate" to (listOfNotNull(from, now).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { "Your strap is recording." })
    }

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun build(context: Context, since: Long?, live: StrapLive, zone: ZoneId): Notification {
        channel(context)
        val (title, text) = words(since, live, zone)
        val open = PendingIntent.getActivity(
            context, StrapService.NOTIFICATION,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, "devices"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // The heart rate stays off the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).build())
            .setContentIntent(open)
            .addAction(0, "Stop", StrapService.stopIntent(context))
            .build()
    }

    fun show(context: Context, since: Long?, live: StrapLive, zone: ZoneId) {
        @Suppress("MissingPermission") // canNotify checked
        NotificationManagerCompat.from(context).notify(StrapService.NOTIFICATION, build(context, since, live, zone))
    }

    private fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording heart rate", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while your heart rate strap records, with a button to stop."
                setSound(null, null)
            },
        )
    }
}
