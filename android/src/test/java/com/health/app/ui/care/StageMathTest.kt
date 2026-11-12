package com.health.app.ui.care

import com.health.api.Contraction
import com.health.api.LifeStage
import com.health.api.Module
import com.health.api.ReportSectionKey
import com.health.api.Screener
import com.health.api.ScreenerDefinition
import com.health.api.ScoreBand
import com.health.server.modules.MindService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StageMathTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun every(apart: Long, lasting: Long, count: Int, endingAgo: Long = 2 * minute) =
        (0 until count).map { i -> now - endingAgo - (count - 1 - i) * apart }.map { Contraction(it, it + lasting) }

    @Test
    fun `contractions 5 minutes apart lasting a minute for an hour are the 5-1-1 pattern`() {
        val summary = StageMath.contractions(every(5 * minute, 65_000, 12), now)
        assertTrue(summary.fiveOneOne)
        assertEquals(12, summary.count)
        assertEquals(5 * minute, summary.averageIntervalMs)
    }

    @Test
    fun `further apart, shorter, or not yet an hour isn't`() {
        assertFalse(StageMath.contractions(every(9 * minute, 65_000, 7), now).fiveOneOne)
        assertFalse(StageMath.contractions(every(5 * minute, 30_000, 12), now).fiveOneOne)
        assertFalse(StageMath.contractions(every(5 * minute, 65_000, 6), now).fiveOneOne)
        // One still going doesn't count until it stops.
        assertEquals(0, StageMath.contractions(listOf(Contraction(now - minute)), now).count)
    }

    @Test
    fun `the EPDS is offered while pregnant or after a birth`() {
        assertEquals(listOf(Screener.PHQ9, Screener.GAD7), StageMath.screenersFor(LifeStage.CYCLING))
        assertEquals(listOf(Screener.PHQ9, Screener.GAD7), StageMath.screenersFor(null))
        assertTrue(Screener.EPDS in StageMath.screenersFor(LifeStage.PREGNANT))
        assertTrue(Screener.EPDS in StageMath.screenersFor(LifeStage.POSTPARTUM))
        assertFalse(Screener.EPDS in StageMath.screenersFor(LifeStage.MENOPAUSE))
    }

    @Test
    fun `the EPDS asks each question with its own answers, and tops out at 30`() {
        val epds = MindService.EPDS
        assertEquals(4, StageMath.optionsFor(epds, 0).size)
        assertEquals("As much as I always could", StageMath.optionsFor(epds, 0).first())
        assertEquals(30, StageMath.maxScore(epds))
        assertEquals(27, StageMath.maxScore(MindService.PHQ9))
        assertEquals(21, StageMath.maxScore(MindService.GAD7))
        assertFalse(StageMath.complete(epds, List(10) { null }))
        assertFalse(StageMath.complete(epds, List(10) { 4 }))
        assertTrue(StageMath.complete(epds, List(10) { 3 }))
    }

    @Test
    fun `what the app sends for the EPDS is what the server scores`() {
        // The phone sends the chosen answer's position; the server scores reversed questions itself.
        val answers = List(10) { 0 }
        val scored = MindService.score(com.health.api.ScreenerResult(Screener.EPDS, now, answers))
        // Questions 1, 2 and 4 count up from 0; the rest count down from 3.
        assertEquals(21, scored.score)
        assertTrue(scored.flagged)
        val calm = MindService.score(com.health.api.ScreenerResult(Screener.EPDS, now, listOf(0, 0, 3, 0, 3, 3, 3, 3, 3, 3)))
        assertEquals(0, calm.score)
        assertFalse(calm.flagged)
    }

    @Test
    fun `a screener with shared answers uses them for every question`() {
        val d = ScreenerDefinition(Screener.GAD7, "t", "a", "q", listOf("one", "two"), listOf("No", "Yes"), listOf(ScoreBand(0, "Low")), "s")
        assertEquals(listOf("No", "Yes"), StageMath.optionsFor(d, 1))
        assertEquals(2, StageMath.maxScore(d))
    }

    @Test
    fun `time since reads plainly`() {
        val today = LocalDate.of(2026, 11, 12)
        assertEquals("1 year, 3 months", CareFormat.since(LocalDate.of(2025, 8, 4), today))
        assertEquals("5 months", CareFormat.since(LocalDate.of(2026, 6, 1), today))
        assertEquals("3 weeks", CareFormat.since(LocalDate.of(2026, 10, 20), today))
        assertEquals("1 day", CareFormat.since(LocalDate.of(2026, 11, 11), today))
        assertEquals("2:05", CareFormat.clock(125_000))
        assertEquals("1 day before", CareFormat.before(1440))
        assertEquals("2 hours before", CareFormat.before(120))
    }

    @Test
    fun `reports offer the features that are on, and the overview`() {
        val sections = com.health.app.ui.care.reportSections(setOf(Module.SLEEP, Module.HORMONES))
        assertEquals(listOf(ReportSectionKey.OVERVIEW, ReportSectionKey.SLEEP, ReportSectionKey.HORMONES), sections)
        assertEquals("health-report-2026-08-14-to-2026-11-12.pdf", reportFileName("report", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 11, 12), "pdf"))
    }

    @Test
    fun `shared features read as a list`() {
        assertEquals("Activity, Sleep and Medications", featureList(setOf(Module.MEDICATIONS, Module.ACTIVITY, Module.SLEEP)))
        assertEquals("Nothing", featureList(emptySet()))
    }

    @Test
    fun `ranges read plainly`() {
        assertEquals("100–200 pg/mL", rangeText(com.health.api.LabTarget("Estradiol", "pg/mL", 100.0, 200.0), "pg/mL"))
        assertEquals("below 50 ng/dL", rangeText(com.health.api.LabTarget("Testosterone", "ng/dL", null, 50.0), "ng/dL"))
        assertEquals("not set", rangeText(null, "pg/mL"))
    }
}
