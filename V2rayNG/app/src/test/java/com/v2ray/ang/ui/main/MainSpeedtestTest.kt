package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainSpeedtestTest {

    @Test
    fun shouldStartWithoutSpeedResultsAndKeepExistingDelayRequestsCompatible() {
        assertFalse(MainUiState().isTesting)
        assertEquals(0L, server("node").testSpeedBytesPerSecond)
        assertFalse(TestServiceMessage(key = 1).testSpeed)
        assertTrue(TestServiceMessage(key = 1, testSpeed = true).testSpeed)
    }

    @Test
    fun shouldApplyResultsByGuidAfterReorderingAndIgnoreRemovedNodes() {
        val servers = listOf(server("second"), server("first"))
        val updates = mapOf(
            "first" to RealPingResult("first", 40L, 3_000_000L),
            "deleted" to RealPingResult("deleted", 10L, 8_000_000L),
        )

        val result = applyTestResults(servers, updates)

        assertEquals(listOf("second", "first"), result.map { it.guid })
        assertEquals(0L, result.first().testSpeedBytesPerSecond)
        assertEquals(40L, result.last().testDelayMillis)
        assertEquals(3_000_000L, result.last().testSpeedBytesPerSecond)
    }

    @Test
    fun shouldKeepSpeedWhenOnlyDelayIsTestedAndClearItForCombinedRetests() {
        val server = server("node").copy(testDelayMillis = 80L, testSpeedBytesPerSecond = 2_000_000L)
        val onlyDelay = mapOf("node" to RealPingResult("node", 60L))
        val combinedReset = mapOf("node" to RealPingResult("node", 0L, 0L))

        assertEquals(2_000_000L, applyTestResults(listOf(server), onlyDelay).single().testSpeedBytesPerSecond)
        assertEquals(0L, applyTestResults(listOf(server), combinedReset).single().testSpeedBytesPerSecond)
        assertEquals(emptyList<ServersCache>(), applyTestResults(emptyList(), onlyDelay))
    }

    @Test
    fun shouldReplacePreviousSpeedWithFailureAndKeepRowAndCacheInSync() {
        val server = server("node").copy(testSpeedBytesPerSecond = 9_000_000L)
        val row = ServerRowUiModel("node", server.profile, "Node", "", "VLESS", 30L, "", 9_000_000L)
        val failed = mapOf("node" to RealPingResult("node", 35L, -1L))

        val result = applyTestResults(listOf(server), failed).single()
        val rowResult = applyTestResultsToRows(listOf(row), failed).single()

        assertEquals(-1L, result.testSpeedBytesPerSecond)
        assertEquals(result.testSpeedBytesPerSecond, rowResult.testSpeedBytesPerSecond)
        assertEquals(result.testDelayMillis, rowResult.testDelayMillis)
    }

    private fun server(guid: String): ServersCache {
        return ServersCache(guid, ProfileItem(configType = EConfigType.VLESS))
    }
}
