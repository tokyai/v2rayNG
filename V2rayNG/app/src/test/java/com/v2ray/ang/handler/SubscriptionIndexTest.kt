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
import org.junit.jupiter.api.Assertions.assertNull
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SubscriptionIndexTest {
    private val mainValues = mutableMapOf<String, String>()
    private val subValues = mutableMapOf<String, String>()
    private val profileValues = mutableMapOf<String, String>()
    private val affiliationValues = mutableMapOf<String, String>()
    private val rawValues = mutableMapOf<String, String>()

    @BeforeEach
    fun prepareStorage() {
        for ((storage, values) in listOf(main to mainValues, subs to subValues, profiles to profileValues, affiliations to affiliationValues, raw to rawValues)) {
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
        // 只有节点数据存储执行批量删除；订阅和主索引替身不需要拦截该 native 方法。
        for ((storage, values) in listOf(profiles to profileValues, affiliations to affiliationValues, raw to rawValues)) {
            // 批量删除必须真的改变替身数据，才能验证删除失败后的跨存储回滚。
            doAnswer {
                val keys = it.getArgument<Array<String>>(0)
                for (key in keys) values.remove(key)
                null
            }.whenever(storage).removeValuesForKeys(any())
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

    /** 保留未测、取消、下载失败和不支持的节点，且不跨组删除。 */
    @Test
    fun shouldClearOnlyDelayFailuresInTheRequestedSubscription() {
        // 输入包含重复索引、归属其他组的 GUID，以及不同测速状态。
        addTestProfile("failed", "group", -1L, -1L)
        addTestProfile("timed-out", "group", -10L)
        addTestProfile("healthy", "group", 20L, 2_000_000L)
        addTestProfile("download-failed", "group", 30L, -1L)
        addTestProfile("untested", "group", 0L)
        addTestProfile("cancelled", "group", 0L)
        addTestProfile("unsupported", "group", -1L, RealPingResult.SPEED_UNSUPPORTED)
        addTestProfile("other-failed", "other-group", -1L, -1L)
        addProfile("no-result", "group")
        val remaining = listOf("healthy", "download-failed", "untested", "cancelled", "unsupported", "no-result", "other-failed")
        mainValues["SUB_SERVERS_group"] = JsonUtil.toJson(listOf("failed") + remaining + listOf("failed", "timed-out"))
        mainValues["SUB_SERVERS_other-group"] = """["other-failed"]"""
        mainValues["SELECTED_SERVER"] = "healthy"
        subValues["group"] = JsonUtil.toJson(SubscriptionItem(url = "https://example.test/subscription"))
        val originalSubscriptions = subValues.toMap()

        val count = MmkvManager.clearInvalidServers("group")

        assertEquals(2, count)
        assertEquals(remaining, MmkvManager.decodeServerList("group"))
        assertEquals(listOf("other-failed"), MmkvManager.decodeServerList("other-group"))
        assertEquals("healthy", MmkvManager.getSelectServer())
        assertEquals(originalSubscriptions, subValues)
        for (guid in listOf("failed", "timed-out")) {
            assertNull(MmkvManager.decodeServerConfig(guid))
            assertNull(MmkvManager.decodeServerAffiliationInfo(guid))
            assertNull(MmkvManager.decodeServerRaw(guid))
        }
        assertEquals(-1L, MmkvManager.decodeServerAffiliationInfo("download-failed")!!.testSpeedBytesPerSecond)
        assertEquals("raw-healthy", MmkvManager.decodeServerRaw("healthy"))
        verify(main, times(1)).encode(eq("SUB_SERVERS_group"), any<String>())
    }

    /** 删除当前选中失效节点时清除选中项，再次清理返回零。 */
    @Test
    fun shouldClearDeletedSelectionAndMakeRepeatedCleanupANoOp() {
        addTestProfile("failed", "group", -1L)
        mainValues["SUB_SERVERS_group"] = """["failed","failed"]"""
        mainValues["SELECTED_SERVER"] = "failed"

        assertEquals(1, MmkvManager.clearInvalidServers("group"))
        assertEquals(0, MmkvManager.clearInvalidServers("group"))

        assertNull(MmkvManager.getSelectServer())
        assertTrue(MmkvManager.decodeServerList("group").isEmpty())
        verify(main, times(1)).encode(eq("SUB_SERVERS_group"), any<String>())
    }

    /** 缺少明确组 ID 时拒绝清理，并保留原有节点。 */
    @Test
    fun shouldRejectBlankCleanupGroupsWithoutChangingStorage() {
        addTestProfile("failed", "group", -1L)
        mainValues["SUB_SERVERS_group"] = """["failed"]"""
        val originalProfiles = profileValues.toMap()

        for (groupId in listOf("", " ")) {
            assertThrows(IllegalArgumentException::class.java) { MmkvManager.clearInvalidServers(groupId) }
        }

        assertEquals(originalProfiles, profileValues)
        assertEquals(listOf("failed"), MmkvManager.decodeServerList("group"))
        verify(main, never()).encode(any<String>(), any<String>())
    }

    /** 空分组和没有已确认失败结果的分组不产生写盘。 */
    @Test
    fun shouldKeepEmptyHealthyAndReleasedUntestedProfilesWithoutWriting() {
        addProfile("old-untested", "group")
        affiliationValues["old-untested"] = """{"testDelayMillis":0}"""
        addTestProfile("healthy", "group", 20L)
        mainValues["SUB_SERVERS_group"] = """["old-untested","healthy"]"""

        assertEquals(0, MmkvManager.clearInvalidServers("empty-group"))
        assertEquals(0, MmkvManager.clearInvalidServers("group"))

        assertEquals(listOf("old-untested", "healthy"), MmkvManager.decodeServerList("group"))
        verify(main, never()).encode(any<String>(), any<String>())
    }

    /** 分组索引保存失败时恢复整批原记录和选中项。 */
    @Test
    fun shouldRestoreCleanupWhenPublishingTheRemainingIndexFails() {
        addTestProfile("failed", "group", -1L)
        mainValues["SUB_SERVERS_group"] = """["failed"]"""
        mainValues["SELECTED_SERVER"] = "failed"
        val originalMain = mainValues.toMap()
        val originalProfiles = profileValues.toMap()
        val originalAffiliations = affiliationValues.toMap()
        val originalRaw = rawValues.toMap()
        whenever(main.encode(eq("SUB_SERVERS_group"), any<String>())).thenReturn(false, true)

        assertThrows(ProfileStorageException::class.java) { MmkvManager.clearInvalidServers("group") }

        assertEquals(originalMain, mainValues)
        assertEquals(originalProfiles, profileValues)
        assertEquals(originalAffiliations, affiliationValues)
        assertEquals(originalRaw, rawValues)
    }

    /** 第一份数据已删、后续存储报错时仍恢复所有数据和原顺序。 */
    @Test
    fun shouldRestoreAllPayloadsAndSelectionWhenBatchRemovalFailsPartway() {
        addTestProfile("first-failed", "group", -1L)
        addTestProfile("second-failed", "group", -1L)
        mainValues["SUB_SERVERS_group"] = """["second-failed","first-failed"]"""
        mainValues["SELECTED_SERVER"] = "first-failed"
        val originalMain = mainValues.toMap()
        val originalProfiles = profileValues.toMap()
        val originalAffiliations = affiliationValues.toMap()
        val originalRaw = rawValues.toMap()
        doAnswer {
            affiliationValues.remove("first-failed")
            throw IllegalStateException("Injected affiliation removal failure")
        }.whenever(affiliations).removeValuesForKeys(any())

        assertThrows(IllegalStateException::class.java) { MmkvManager.clearInvalidServers("group") }

        assertEquals(originalMain, mainValues)
        assertEquals(originalProfiles, profileValues)
        assertEquals(originalAffiliations, affiliationValues)
        assertEquals(originalRaw, rawValues)
    }

    /** 清理后丢弃迟到结果，后续准备和排序不能重新加入已删除 GUID。 */
    @Test
    fun shouldRejectLateResultsAndKeepDeletedNodesOutOfRetestingAndSorting() {
        addTestProfile("failed", "group", -1L)
        addTestProfile("healthy", "group", 20L)
        mainValues["SUB_SERVERS_group"] = """["failed","healthy"]"""
        MmkvManager.clearInvalidServers("group")

        val accepted = MmkvManager.encodeServerTestResult(RealPingResult("failed", 10L, 1_000_000L), "test")
        MmkvManager.prepareServerTest(listOf("failed", "healthy"), "retry", testSpeed = true)
        MmkvManager.reorderServerLists(listOf("failed", "healthy"))

        assertFalse(accepted)
        assertNull(MmkvManager.decodeServerAffiliationInfo("failed"))
        assertEquals(listOf("healthy"), MmkvManager.decodeServerList("group"))
        assertEquals("retry", MmkvManager.decodeServerAffiliationInfo("healthy")!!.testRequestId)
    }

    /** 延迟排序读取旧列表后发生清理，写回时也只能保留当前仍存在的节点。 */
    @Test
    fun shouldKeepDeletedNodesOutOfAnOverlappingDelaySort() {
        addTestProfile("failed", "group", -1L)
        addTestProfile("slower", "group", 40L)
        addTestProfile("faster", "group", 20L)
        mainValues["SUB_SERVERS_group"] = """["failed","slower","faster"]"""
        var cleanupStarted = false

        // 在排序收到旧索引之前完成清理，确定性模拟读取与写回之间的并发窗口。
        doAnswer {
            val snapshot = mainValues["SUB_SERVERS_group"]
            if (!cleanupStarted) {
                cleanupStarted = true
                assertEquals(1, MmkvManager.clearInvalidServers("group"))
            }
            snapshot
        }.whenever(main).decodeString("SUB_SERVERS_group")

        AngConfigManager.sortByTestResultsForSub("group")

        assertEquals(listOf("faster", "slower"), MmkvManager.decodeServerList("group"))
        assertNull(MmkvManager.decodeServerConfig("failed"))
    }

    /** 全量更新订阅允许重新导入清理过的节点，不保存永久过滤名单。 */
    @Test
    fun shouldAllowTheWholeSubscriptionToBeImportedAgainAfterCleanup() {
        addTestProfile("failed", "group", -1L)
        addTestProfile("healthy", "group", 20L)
        mainValues["SUB_SERVERS_group"] = """["failed","healthy"]"""
        subValues["group"] = JsonUtil.toJson(SubscriptionItem(remarks = "Subscription", url = "https://example.test/subscription"))
        val originalSubscription = subValues["group"]
        val originalFailed = MmkvManager.decodeServerConfig("failed")!!
        val originalHealthy = MmkvManager.decodeServerConfig("healthy")!!
        MmkvManager.clearInvalidServers("group")

        val fullSubscription = linkedMapOf("new-failed" to originalFailed, "new-healthy" to originalHealthy)
        MmkvManager.saveServerProfiles(fullSubscription, emptyMap(), "group", append = false)

        assertEquals(fullSubscription.keys, MmkvManager.decodeServerList("group").toSet())
        assertEquals(originalFailed, MmkvManager.decodeServerConfig("new-failed"))
        assertEquals(originalHealthy, MmkvManager.decodeServerConfig("new-healthy"))
        assertEquals(originalSubscription, subValues["group"])
        assertNull(MmkvManager.decodeServerAffiliationInfo("new-failed"))
    }

    private fun addTestProfile(guid: String, groupId: String, delay: Long, speed: Long = 0L) {
        // 每个节点保留完整、附属和原始三份记录，回滚断言逐份核对。
        addProfile(guid, groupId)
        affiliationValues[guid] = JsonUtil.toJson(ServerAffiliationInfo(delay, speed, "test"))
        rawValues[guid] = "raw-$guid"
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
        // 批量删除也是 native 方法；使用子类替身覆盖它，inline 替身无法拦截 JNI 调用。
        private val profiles: MMKV = Mockito.mock(MMKV::class.java, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS))
        private val affiliations: MMKV = Mockito.mock(MMKV::class.java, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS))
        private val raw: MMKV = Mockito.mock(MMKV::class.java, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS))

        @BeforeAll
        @JvmStatic
        fun initializeHandles() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("MAIN", MMKV.MULTI_PROCESS_MODE) }.thenReturn(main)
                it.`when`<MMKV> { MMKV.mmkvWithID("SUB", MMKV.MULTI_PROCESS_MODE) }.thenReturn(subs)
                it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                it.`when`<MMKV> { MMKV.mmkvWithID("PROFILE_FULL_CONFIG", MMKV.MULTI_PROCESS_MODE) }.thenReturn(profiles)
                it.`when`<MMKV> { MMKV.mmkvWithID("SERVER_AFF", MMKV.MULTI_PROCESS_MODE) }.thenReturn(affiliations)
                it.`when`<MMKV> { MMKV.mmkvWithID("SERVER_RAW", MMKV.MULTI_PROCESS_MODE) }.thenReturn(raw)
                MmkvManager.decodeSubscriptions()
                MmkvManager.decodeSettingsString("test-initialize")
                MmkvManager.decodeServerConfig("test-initialize")
                MmkvManager.decodeServerAffiliationInfo("test-initialize")
                MmkvManager.decodeServerRaw("test-initialize")
            }
        }
    }
}
