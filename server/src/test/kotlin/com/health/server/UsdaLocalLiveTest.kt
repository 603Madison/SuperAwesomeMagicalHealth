package com.health.server

import com.health.server.food.UsdaKind
import com.health.server.food.UsdaLocal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * USDA's real downloads, end to end: only with SAMH_USDA_LIVE=1 (it downloads about 215 MB and
 * reads about 3.5 GB of JSON). Prints what each choice downloads and takes on disk, and how long.
 */
class UsdaLocalLiveTest {
    @Test
    fun `the real release, everyday foods and then everything`(): Unit = runBlocking {
        assumeTrue(System.getenv("SAMH_USDA_LIVE") == "1")
        val dir = Files.createTempDirectory("samh-usda-live").toFile()
        val s = testServices(
            config = ServerConfig(dataDir = dir, adminPassword = "x", outboundLoopback = true),
            usdaDownloadsUrl = UsdaLocal.PAGE_URL,
        )
        s.settings.update { it.copy(usdaLocal = it.usdaLocal.copy(use = true)) }
        for (packaged in listOf(false, true)) {
            val started = System.currentTimeMillis()
            assertTrue(s.usdaLocal.status().error ?: "", s.usdaLocal.update(packaged))
            val status = s.usdaLocal.status()
            val rt = Runtime.getRuntime()
            println(
                "USDA live: packaged=$packaged release=${status.release} foods=${status.foods} disk=${status.bytes} bytes " +
                    "in ${(System.currentTimeMillis() - started) / 1000} s; heap used ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB of max ${rt.maxMemory() / 1_000_000} MB",
            )
            println("USDA live: banana → " + s.usdaLocal.search("banana", UsdaKind.EVERYDAY, 8).joinToString { "${it.name} (${it.serving})" })
            if (packaged) {
                println("USDA live: greek yogurt → " + s.usdaLocal.search("greek yogurt", UsdaKind.PACKAGED, 15).joinToString { "${it.name} · ${it.brand} (${it.serving})" })
                val chobani = s.usdaLocal.barcode("0894700010038")
                println("USDA live: barcode 0894700010038 → ${chobani?.name} · ${chobani?.brand} · ${chobani?.barcode}")
                val t = System.nanoTime()
                repeat(20) { s.usdaLocal.search("cheddar cheese", UsdaKind.PACKAGED, 15) }
                println("USDA live: a packaged search takes ${(System.nanoTime() - t) / 20 / 1_000_000} ms")
            }
        }
        assertEquals(setOf("usda-foods.db"), dir.list()!!.filter { it.startsWith("usda") }.toSet())
        dir.deleteRecursively()
    }
}
