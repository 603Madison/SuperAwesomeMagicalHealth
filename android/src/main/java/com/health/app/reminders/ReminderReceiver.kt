package com.health.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.MedForm
import com.health.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where reminders' alarms and buttons land: an alarm shows the doses due and sets the next one;
 * Taken records the dose (or keeps it to send later); Snooze brings it back in ten minutes; a
 * restart, an update or a new time zone sets the alarm again.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        when (intent.action) {
            ACTION_FIRE -> {
                val at = intent.getLongExtra(EXTRA_AT, 0)
                if (at > 0) c.reminders.fire(at)
                c.reminders.reschedule()
            }
            ACTION_SNOOZED -> doseOf(intent)?.let { c.reminders.notify(listOf(it)) }
            ACTION_SNOOZE -> doseOf(intent)?.let { c.reminders.snooze(it) }
            ACTION_TAKEN -> {
                val dose = doseOf(intent) ?: return
                // No more reminders for it, here or again later.
                c.reminders.marked(dose)
                val site = c.medsCache.nextSite(dose.medicationId)?.takeIf { dose.form == MedForm.INJECTION || dose.form == MedForm.PATCH }
                val log = DoseLog(dose.medicationId, System.currentTimeMillis(), DoseStatus.TAKEN, scheduledFor = dose.at, site = site)
                val pending = goAsync()
                scope.launch {
                    try {
                        c.api.logDose(log)
                        c.dataChanged()
                    } catch (e: Exception) {
                        // No server right now: it goes with the next sync.
                        Log.i(TAG, "Keeping a dose to send later: ${e.message}")
                        c.doseOutbox.add(log)
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                c.reminders.reschedule()
        }
    }

    companion object {
        private const val TAG = "Reminders"
        const val ACTION_FIRE = "com.health.app.reminders.FIRE"
        const val ACTION_SNOOZED = "com.health.app.reminders.SNOOZED"
        const val ACTION_SNOOZE = "com.health.app.reminders.SNOOZE"
        const val ACTION_TAKEN = "com.health.app.reminders.TAKEN"
        const val EXTRA_AT = "at"
        private const val EXTRA_ID = "medication"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DOSE = "dose"
        private const val EXTRA_FORM = "form"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun put(intent: Intent, dose: PlannedDose) {
            intent.putExtra(EXTRA_ID, dose.medicationId).putExtra(EXTRA_NAME, dose.name).putExtra(EXTRA_DOSE, dose.dose)
                .putExtra(EXTRA_FORM, dose.form.name).putExtra(EXTRA_AT, dose.at)
        }

        fun doseOf(intent: Intent): PlannedDose? {
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            return PlannedDose(
                medicationId = id,
                name = intent.getStringExtra(EXTRA_NAME) ?: "your medication",
                dose = intent.getStringExtra(EXTRA_DOSE),
                form = MedForm.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_FORM) } ?: MedForm.OTHER,
                at = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis()),
            )
        }
    }
}
