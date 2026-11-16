package com.v2ray.ang.handler

import android.util.Log
import com.tencent.mmkv.MMKV
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockMakers
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SubscriptionIndexTest {
    private val mainValues = mutableMapOf<String, String>()
    private val subValues = mutableMapOf<String, String>()
    private val profileValues = mutableMapOf<String, String>()
    private val affiliationValues = mutableMapOf<String, String>()

    @BeforeEach
    fun prepareStorage() {
        for ((storage, values) in listOf(main to mainValues, subs to subValues, profiles to profileValues, affiliations to affiliationValues)) {
            reset(storage)
            whenever(storage.decodeString(any())).thenAnswer { values[it.getArgument<String>(0)] }
            whenever(storage.encode(any<String>(), any<String>())).thenAnswer {
                values[it.getArgument(0)] = it.getArgument(1)
                true
            }
            whenever(storage.allKeys()).thenAnswer { values.keys.toTypedArray() }
            doAnswer {
                values.remove(it.getArgument<String>(0))
                null
            }.whenever(storage).remove(any())
        }
    }

    @Test
    fun duplicateIdsKeepTheirFirstPositionWithoutWritingStorage() {
        val stored = """["second","first","second","third","first"]"""
        mainValues["SUB_IDS"] = stored

        assertEquals(listOf("second", "first", "third"), MmkvManager.decodeSubsList())
        assertEquals(stored, mainValues["SUB_IDS"])
        verify(main, never()).encode(any<String>(), any<String>())
    }

    @Test
    fun duplicateIndexEntriesProduceOnlyOneSubscriptionRow() {
        mainValues["SUB_IDS"] = """["b","a","b","a"]"""
        subValues["a"] = JsonUtil.toJson(SubscriptionItem(remarks = "Alpha"))
        subValues["b"] = JsonUtil.toJson(SubscriptionItem(remarks = "Beta"))

        val rows = MmkvManager.decodeSubscriptions()

        assertEquals(listOf("b", "a"), rows.map { it.guid })
        assertEquals(listOf("Beta", "Alpha"), rows.map { it.subscription.remarks })
    }

    @Test
    fun repeatedAndBlankNamesDoNotMergeDifferentSubscriptions() {
        mainValues["SUB_IDS"] = """["a","b","c","d"]"""
        listOf("a" to "Same", "b" to "Same", "c" to "", "d" to " ").forEach { (id, name) ->
            subValues[id] = JsonUtil.toJson(SubscriptionItem(remarks = name))
        }

        assertEquals(listOf("a", "b", "c", "d"), MmkvManager.decodeSubscriptions().map { it.guid })
    }

    @Test
    fun decodedIndexRemainsMutableAndCanBeSavedInANewOrder() {
        mainValues["SUB_IDS"] = """["a","b","a"]"""
        val ids = MmkvManager.decodeSubsList()
        ids.remove("a")
        ids.add(0, "c")
        MmkvManager.encodeSubsList(ids)

        assertEquals("""["c","b"]""", mainValues["SUB_IDS"])
        assertEquals(listOf("c", "b"), MmkvManager.decodeSubsList())
    }

    @Test
    fun missingBlankAndEmptyIndexesRemainEmpty() {
        assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        listOf("", " ", "[]", "null").forEach { stored ->
            mainValues["SUB_IDS"] = stored
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    @Test
    fun malformedIndexKeepsTheExistingEmptyFallback() {
        mockStatic(Log::class.java).use {
            mainValues["SUB_IDS"] = "{"
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    @Test
    fun shouldReadReleasedDelayOnlyRecordsWithoutInventingASpeed() {
        affiliationValues["node"] = """{"testDelayMillis":98}"""

        val result = MmkvManager.decodeServerAffiliationInfo("node")!!

        assertEquals(98L, result.testDelayMillis)
        assertEquals(0L, result.testSpeedBytesPerSecond)
        assertEquals("", result.testRequestId)
    }

    @Test
    fun shouldClearOldSpeedBeforeRetestingAndRejectLateOrDeletedResults() {
        addProfile("first", "group")
        addProfile("second", "group")
        affiliationValues["first"] = JsonUtil.toJson(ServerAffiliationInfo(98L, 8_000_000L))
        MmkvManager.prepareServerTest(listOf("first", "second"), "old", testSpeed = true)
        MmkvManager.prepareServerTest(listOf("first"), "new", testSpeed = true)

        assertEquals(0L, MmkvManager.decodeServerAffiliationInfo("first")!!.testSpeedBytesPerSecond)
        assertFalse(MmkvManager.encodeServerTestResult(RealPingResult("first", 20L, 9_000_000L), "old"))
        assertTrue(MmkvManager.encodeServerTestResult(RealPingResult("first", 50L, -1L), "new"))
        assertEquals(-1L, MmkvManager.decodeServerAffiliationInfo("first")!!.testSpeedBytesPerSecond)
        profileValues.remove("second")
        assertFalse(MmkvManager.encodeServerTestResult(RealPingResult("second", 20L, 1_000_000L), "old"))
    }

    @Test
    fun shouldPreserveDownloadSpeedWhenTestingOnlyDelay() {
        addProfile("node", "group")
        affiliationValues["node"] = JsonUtil.toJson(ServerAffiliationInfo(98L, 2_000_000L))
        MmkvManager.prepareServerTest(listOf("node"), "delay-only", testSpeed = false)

        assertTrue(MmkvManager.encodeServerTestResult(RealPingResult("node", 30L), "delay-only"))

        val persisted = MmkvManager.decodeServerAffiliationInfo("node")!!
        assertEquals(30L, persisted.testDelayMillis)
        assertEquals(2_000_000L, persisted.testSpeedBytesPerSecond)
    }

    @Test
    fun shouldRestoreEveryOldResultIfPreparingABatchFails() {
        addProfile("first", "group")
        addProfile("second", "group")
        affiliationValues["first"] = JsonUtil.toJson(ServerAffiliationInfo(20L, 3_000_000L))
        affiliationValues["second"] = JsonUtil.toJson(ServerAffiliationInfo(30L, 4_000_000L))
        val original = affiliationValues.toMap()
        whenever(affiliations.encode(eq("second"), any<String>())).thenReturn(false, true)

        assertThrows(ProfileStorageException::class.java) {
            MmkvManager.prepareServerTest(listOf("first", "second"), "test", testSpeed = true)
        }

        assertEquals(original, affiliationValues)
    }

    @Test
    fun shouldKeepHiddenAndNewNodesWhilePersistingTheSpeedOrder() {
        for (guid in listOf("slow", "hidden", "fast", "new")) addProfile(guid, "group")
        mainValues["SUB_SERVERS_group"] = """["slow","hidden","fast","new"]"""

        MmkvManager.reorderServerLists(listOf("removed", "fast", "slow"))

        assertEquals(listOf("fast", "hidden", "slow", "new"), MmkvManager.decodeServerList("group"))
        assertEquals("""["fast","hidden","slow","new"]""", mainValues["SUB_SERVERS_group"])
    }

    @Test
    fun shouldRestoreEarlierGroupsWhenSavingALaterGroupFails() {
        addProfile("a", "group-a")
        addProfile("b", "group-a")
        addProfile("c", "group-b")
        addProfile("d", "group-b")
        mainValues["SUB_SERVERS_group-a"] = """["a","b"]"""
        mainValues["SUB_SERVERS_group-b"] = """["c","d"]"""
        val original = mainValues.toMap()
        whenever(main.encode(eq("SUB_SERVERS_group-b"), any<String>())).thenReturn(false, true)

        assertThrows(ProfileStorageException::class.java) {
            MmkvManager.reorderServerLists(listOf("b", "a", "d", "c"))
        }

        assertEquals(original, mainValues)
    }

    private fun addProfile(guid: String, groupId: String) {
        val profile = ProfileItem(configType = EConfigType.VLESS, subscriptionId = groupId)
        profileValues[guid] = JsonUtil.toJson(profile)
    }

    companion object {
        // MMKV 的 lock/unlock 是 native 方法；子类替身可以覆盖它们，JVM 不会加载 Android JNI。
        private val main: MMKV = Mockito.mock(MMKV::class.java, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS))
        private val subs: MMKV = mock()
        private val settings: MMKV = mock()
        private val profiles: MMKV = mock()
        private val affiliations: MMKV = mock()

        @BeforeAll
        @JvmStatic
        fun initializeHandles() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("MAIN", MMKV.MULTI_PROCESS_MODE) }.thenReturn(main)
                it.`when`<MMKV> { MMKV.mmkvWithID("SUB", MMKV.MULTI_PROCESS_MODE) }.thenReturn(subs)
                it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                it.`when`<MMKV> { MMKV.mmkvWithID("PROFILE_FULL_CONFIG", MMKV.MULTI_PROCESS_MODE) }.thenReturn(profiles)
                it.`when`<MMKV> { MMKV.mmkvWithID("SERVER_AFF", MMKV.MULTI_PROCESS_MODE) }.thenReturn(affiliations)
                MmkvManager.decodeSubscriptions()
                MmkvManager.decodeSettingsString("test-initialize")
                MmkvManager.decodeServerConfig("test-initialize")
                MmkvManager.decodeServerAffiliationInfo("test-initialize")
            }
        }
    }
}
