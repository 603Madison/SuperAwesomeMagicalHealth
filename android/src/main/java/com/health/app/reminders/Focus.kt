package com.health.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.FocusKind
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.attempt
import com.health.app.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/*
 * The focus timer (Autism & ADHD) while it runs: kept on the phone so it survives the app
 * closing, shown in an ongoing notification with the time so far (naming the task only when the
 * person shows names in reminders), and (if the person wants) a gentle chime every so often
 * suggesting a break. Nothing is sent until it's finished.
 */

/** A focus session under way. */
@Serializable
data class RunningFocus(
    val start: Long,
    val kind: FocusKind = FocusKind.FOCUS,
    val task: String? = null,
    val estimateMinutes: Int? = null,
    val breaks: Int = 0,
    /** A chime every this many minutes, or none. */
    val chimeEveryMinutes: Int? = null,
) {
    /** The next chime after [now], or null. */
    fun nextChime(now: Long): Long? {
        val every = chimeEveryMinutes?.takeIf { it > 0 }?.times(60_000L) ?: return null
        val n = ((now - start) / every) + 1
        return start + n * every
    }
}

/** The running session, kept on the phone. */
class FocusStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val owner = System.identityHashCode(context)

    private val _running = MutableStateFlow(read())
    val running: StateFlow<RunningFocus?> = _running.asStateFlow()

    private fun read(): RunningFocus? = prefs.getString(KEY, null)?.let { attempt { ApiJson.decodeFromString(RunningFocus.serializer(), it) }.getOrNull() }

    fun set(focus: RunningFocus?) {
        prefs.edit { if (focus == null) remove(KEY) else putString(KEY, ApiJson.encodeToString(RunningFocus.serializer(), focus)) }
        _running.value = focus
    }

    companion object {
        private const val KEY = "running"

        @Volatile
        private var instance: FocusStore? = null

        fun of(context: Context): FocusStore {
            val app = context.applicationContext ?: context
            val owner = System.identityHashCode(app)
            return instance?.takeIf { it.owner == owner } ?: synchronized(this) {
                instance?.takeIf { it.owner == owner } ?: FocusStore(app).also { instance = it }
            }
        }
    }
}

/** The ongoing notification and the break chimes. */
object FocusAlerts {
    private const val CHANNEL_ONGOING = "focus-timer"
    private const val CHANNEL_CHIME = "focus-breaks"
    private const val ID_ONGOING = 4301
    private const val ID_CHIME = 4302
    private const val REQUEST_CHIME = 4303
    const val ACTION_CHIME = "com.health.app.reminders.FOCUS_CHIME"

    /** Starts (or updates) the session: its notification (with the task's name only when [showNames]) and its next chime. */
    fun start(context: Context, focus: RunningFocus, canNotify: Boolean, showNames: Boolean, now: Long = System.currentTimeMillis()) {
        FocusStore.of(context).set(focus)
        if (canNotify) showOngoing(context, focus, showNames)
        scheduleChime(context, focus, now)
    }

    /**
     * The ongoing notification's title and text: the task's name only with names on (as the
     * reminders), else what kind of session it is.
     */
    fun words(focus: RunningFocus, showNames: Boolean): Pair<String, String> {
        val task = focus.task?.takeIf { it.isNotBlank() && showNames }
        val text = listOfNotNull(
            focus.kind.label.takeIf { task != null },
            focus.estimateMinutes?.let { "you guessed $it min" },
            focus.chimeEveryMinutes?.let { "a chime every $it min" },
        ).joinToString(" · ").ifEmpty { "Tap to finish when you're ready." }
        return (task ?: focus.kind.label) to text
    }

    /** Ends it here: the notification and chimes go. */
    fun stop(context: Context) {
        FocusStore.of(context).set(null)
        context.getSystemService(AlarmManager::class.java).cancel(chimeIntent(context))
        NotificationManagerCompat.from(context).cancel(ID_ONGOING)
        NotificationManagerCompat.from(context).cancel(ID_CHIME)
    }

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Focus timer", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while a focus session runs."
                    setSound(null, null)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_CHIME) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CHIME, "Focus breaks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A gentle chime suggesting a short break, if you chose one."
                    enableVibration(false)
                },
            )
        }
    }

    private fun openIntent(context: Context) = PendingIntent.getActivity(
        context, ID_ONGOING,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, "neuro/focus"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showOngoing(context: Context, focus: RunningFocus, showNames: Boolean) {
        channels(context)
        val (title, text) = words(focus, showNames)
        val notification = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(focus.start)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ONGOING).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Focus timer").setWhen(focus.start).setUsesChronometer(true).setShowWhen(true).build(),
            )
            .setContentIntent(openIntent(context))
            .build()
        @Suppress("MissingPermission") // the caller checked
        NotificationManagerCompat.from(context).notify(ID_ONGOING, notification)
    }

    private fun scheduleChime(context: Context, focus: RunningFocus, now: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(chimeIntent(context))
        val at = focus.nextChime(now) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, chimeIntent(context))
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, chimeIntent(context))
        }
    }

    /** The chime: a short, quiet notification, gone again in a minute; and the next one set. */
    fun chime(context: Context, canNotify: Boolean, now: Long = System.currentTimeMillis()) {
        val focus = FocusStore.of(context).running.value ?: return
        if (canNotify) {
            channels(context)
            val minutes = ((now - focus.start) / 60_000).toInt()
            val notification = NotificationCompat.Builder(context, CHANNEL_CHIME)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Time for a short break?")
                .setContentText("$minutes minutes in. A stretch, some water, a look out of the window. Or carry on: your call.")
                .setAutoCancel(true)
                .setTimeoutAfter(60_000L)
                .setContentIntent(openIntent(context))
                .build()
            @Suppress("MissingPermission") // checked in canNotify
            NotificationManagerCompat.from(context).notify(ID_CHIME, notification)
        }
        scheduleChime(context, focus, now)
    }

    private fun chimeIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CHIME,
        Intent(context, FocusReceiver::class.java).setAction(ACTION_CHIME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Where the break chimes' alarms land. */
class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FocusAlerts.ACTION_CHIME) FocusAlerts.chime(context, context.container.reminders.canNotify())
    }
}
