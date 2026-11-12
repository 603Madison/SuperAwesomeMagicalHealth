package com.health.server

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.LabResult
import com.health.api.LeakSize
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Metric
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.Profile
import com.health.api.Sample
import com.health.api.SleepSession
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Not a test: writes a sample report for looking at (run with SAMH_REPORT_PREVIEW=path). */
class ReportPreviewTest {
    @Test
    fun preview() = runBlocking {
        val out = System.getenv("SAMH_REPORT_PREVIEW") ?: return@runBlocking
        val services = testServices()
        services.bootstrap()
        val userId = services.users.byUsername("admin")!!.id
        services.profiles.put(userId, Profile(timezone = "UTC", modules = Module.DEFAULT + Module.MEDICATIONS + Module.MIND + Module.CONTINENCE, birthYear = 1990, heightCm = 170.0))
        val today = LocalDate.now(ZoneOffset.UTC)
        val device = DeviceInfo(DeviceKind.COLMI_RING, "ring", "Colmi R02")
        for (back in 1..30L) {
            val d = today.minusDays(back)
            val noon = d.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            val bed = d.minusDays(1).atTime(23, (back % 4 * 10).toInt()).toInstant(ZoneOffset.UTC).toEpochMilli()
            services.ingest.ingest(
                userId,
                IngestBatch(
                    device,
                    listOf(
                        Sample(Metric.STEPS, noon, noon + 3_600_000, 6000.0 + (back * 997 % 5000)),
                        Sample(Metric.RESTING_HEART_RATE, noon, null, 56.0 + back % 5),
                        Sample(Metric.HRV, noon, null, 45.0 + back % 9),
                        Sample(Metric.WEIGHT, noon, null, 68.0 - back * 0.05),
                    ),
                    sleep = listOf(SleepSession(bed, bed + (6.5 + (back % 5) * 0.4).times(3_600_000).toLong())),
                ),
            )
            services.modules.mind.saveMood(userId, MoodEntry(at = noon, mood = (2 + back % 4).toInt()))
            services.modules.continence.save(userId, ContinenceEntry(noon, ContinenceKind.URINATION, volumeMl = 300))
            if (back % 3 == 0L) services.modules.continence.save(userId, ContinenceEntry(noon + 60_000, ContinenceKind.LEAK, leak = LeakSize.SMALL, triggers = listOf("cough")))
        }
        services.modules.meds.save(userId, Medication("Vitamin D", dose = "1000 IU", purpose = "Low vitamin D"))
        services.modules.meds.save(userId, Medication("Estradiol", dose = "2 mg", schedule = MedSchedule(times = listOf("08:00", "20:00"))))
        for ((back, v) in listOf(25L to 310.0, 10L to 420.0, 3L to 380.0)) {
            services.modules.meds.saveLab(userId, LabResult("Estradiol", v, "pmol/L", today.minusDays(back).atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), low = 250.0, high = 600.0))
        }
        File(out).writeBytes(services.platform.reports.pdf(userId, today.minusDays(30), today, null))
    }
}
