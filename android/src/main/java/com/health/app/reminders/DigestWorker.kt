package com.health.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.health.api.DigestPrefs
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.container
import com.health.app.net.latestDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * The weekly digest: once a week, after the day and hour the person chose (when the server makes
 * it), fetches the latest and says it's ready. The notification never says more than that, so a
 * locked screen shows nothing personal.
 */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.value.signedIn) return Result.success()
        val prefs = c.profile.value?.digest ?: c.loadProfile()?.digest ?: return Result.retry()
        if (!prefs.enabled) return Result.success()
        val digest = try {
            c.api.latestDigest()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        val seen = applicationContext.getSharedPreferences("digest", Context.MODE_PRIVATE)
        if (seen.getString(LAST, null) == digest.weekStart) return Result.success()
        // Only a digest for the week just gone: an older one has been seen or doesn't matter now.
        val lastMonday = LocalDate.now(c.zone()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
        if (digest.weekStart < lastMonday.toString()) return Result.success()
        notify(applicationContext)
        seen.edit { putString(LAST, digest.weekStart) }
        return Result.success()
    }

    companion object {
        private const val NAME = "weekly-digest"
        private const val LAST = "last"
        const val CHANNEL = "weekly-digest"
        private const val ID = 7_000

        /** Schedules the weekly check for just after the person's digest time (or stops it). */
        fun schedule(context: Context, prefs: DigestPrefs?, zone: ZoneId, now: ZonedDateTime = ZonedDateTime.now(zone)) {
            val work = WorkManager.getInstance(context)
            if (prefs == null || !prefs.enabled) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<DigestWorker>(7, TimeUnit.DAYS, 6, TimeUnit.HOURS)
                .setInitialDelay(delayUntil(prefs, now).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** How long until 20 minutes after the next digest time (the server makes it on the hour). */
        fun delayUntil(prefs: DigestPrefs, now: ZonedDateTime): Duration {
            val day = DayOfWeek.of(prefs.day.coerceIn(1, 7))
            var next = now.with(TemporalAdjusters.nextOrSame(day)).withHour(prefs.hour.coerceIn(0, 23)).withMinute(20).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            return Duration.between(now, next)
        }

        fun notify(context: Context) {
            val c = context.container
            if (!c.reminders.canNotify()) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Weekly digest", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "When your week in health is ready to read."
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN, "digest"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Your week in health is ready")
                .setContentText("Tap to read it.")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission") // checked in canNotify()
            NotificationManagerCompat.from(context).notify(ID, notification)
        }
    }
}
