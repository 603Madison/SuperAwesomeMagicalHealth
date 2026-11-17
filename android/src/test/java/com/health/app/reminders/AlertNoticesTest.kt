package com.health.app.reminders

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.AlertSettings
import com.health.api.HealthAlert
import com.health.api.HealthAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Health alerts as the phone's own notifications: each once at each level, discreet on a locked screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlertNoticesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun alert(id: String, level: AlertLevel, title: String = "Your heart rate stayed high at rest") = HealthAlert(
        id, AlertKind.HIGH_HEART_RATE, level, title,
        "It was 128–151 bpm (mostly around 140) for 14 min, from 2:10 AM, while you were resting. Your usual at night is 52.",
        "If you have chest pain or pressure, call your local emergency number now.",
        start = 1_000, createdAt = 2_000,
    )

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun `each new alert is told once, and again only when it gets more serious`() {
        val notices = AlertNotices(context)
        val watch = alert("a1", AlertLevel.WATCH)
        assertEquals(1, notices.tell(HealthAlerts(listOf(watch)), canNotify = true))
        assertEquals(0, notices.tell(HealthAlerts(listOf(watch)), canNotify = true))
        assertEquals(1, posted().size)
        val n = posted().single()
        assertEquals("Your heart rate stayed high at rest", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("It was 128–151 bpm (mostly around 140) for 14 min, from 2:10 AM, while you were resting.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(AlertNotices.CHANNEL, n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("A health alert", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)

        // Worse now: told again, in the same place, loud, and it stays until swiped away.
        assertEquals(1, notices.tell(HealthAlerts(listOf(watch.copy(level = AlertLevel.URGENT))), canNotify = true))
        assertEquals(1, posted().size)
        val urgent = posted().single()
        assertEquals(AlertNotices.CHANNEL_URGENT, urgent.channelId)
        assertEquals("An urgent health alert", urgent.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, urgent.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(listOf("a1:WATCH", "a1:URGENT"), notices.told())
    }

    @Test
    fun `two channels, ordinary and urgent (with high importance)`() {
        AlertNotices(context).tell(HealthAlerts(listOf(alert("a", AlertLevel.DOCTOR))), canNotify = true)
        val channel = manager.getNotificationChannel(AlertNotices.CHANNEL)
        assertNotNull(channel)
        assertEquals("Health alerts", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        val urgent = manager.getNotificationChannel(AlertNotices.CHANNEL_URGENT)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, urgent.importance)
    }

    @Test
    fun `nothing is told with notifications off, the app not allowed to, or once it's seen or put away`() {
        val notices = AlertNotices(context)
        assertEquals(0, notices.tell(HealthAlerts(listOf(alert("a", AlertLevel.URGENT)), AlertSettings(notify = false)), canNotify = true))
        assertEquals(0, notices.tell(HealthAlerts(listOf(alert("a", AlertLevel.URGENT)), AlertSettings(enabled = false)), canNotify = true))
        assertEquals(0, notices.tell(HealthAlerts(listOf(alert("a", AlertLevel.URGENT).copy(seenAt = 5))), canNotify = true))
        assertEquals(0, notices.tell(HealthAlerts(listOf(alert("a", AlertLevel.URGENT).copy(dismissedAt = 5))), canNotify = true))
        // Not allowed to show notifications yet: kept to tell once it is.
        assertEquals(0, notices.tell(HealthAlerts(listOf(alert("a", AlertLevel.URGENT))), canNotify = false))
        assertTrue(posted().isEmpty())
        assertEquals(1, notices.tell(HealthAlerts(listOf(alert("a", AlertLevel.URGENT))), canNotify = true))
    }

    @Test
    fun `put away or seen, its notification goes (an urgent one stays until swiped when only seen)`() {
        val notices = AlertNotices(context)
        val urgent = alert("u", AlertLevel.URGENT)
        val doctor = alert("d", AlertLevel.DOCTOR)
        notices.tell(HealthAlerts(listOf(urgent, doctor)), canNotify = true)
        assertEquals(2, posted().size)
        notices.seen(listOf(urgent, doctor))
        assertEquals(listOf(AlertNotices.CHANNEL_URGENT), posted().map { it.channelId })
        notices.cancel(urgent)
        assertTrue(posted().isEmpty())
        notices.clear()
        assertTrue(notices.told().isEmpty())
    }
}
