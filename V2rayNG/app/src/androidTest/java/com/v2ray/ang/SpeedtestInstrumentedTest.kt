package com.v2ray.ang

import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 使用独立测试分组验证真实服务、JNI 和 UI；运行前通过 adb reverse 将 18080 接到可联网的 SOCKS 代理。
 * 例：adb reverse tcp:18080 tcp:10808。测试结束移除自己创建的节点并恢复原设置。
 * @author SHIXIAOYANG
 */
@RunWith(AndroidJUnit4::class)
class SpeedtestInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // 手动读屏场景保留已启用的 TalkBack，避免 UiAutomation 默认暂停服务。
    private val automation = if (InstrumentationRegistry.getArguments().getString("manualTalkback") == "true") {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    } else {
        instrumentation.uiAutomation
    }
    private val context = instrumentation.targetContext
    private val groupId = "speedtest-${UUID.randomUUID()}"
    private val guids = mutableListOf<String>()
    private val events = CopyOnWriteArrayList<Intent>()
    private val previousSettings = linkedMapOf<String, String?>()
    private var previousSelection: String? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var proxyStarted = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) events.add(intent)
        }
    }

    @Before
    fun prepareIsolatedGroup() {
        // 数据写入真实 MMKV，但只创建本用例所属分组，不读取用户节点的连接内容。
        previousSelection = MmkvManager.getSelectServer()
        for (key in listOf(AppConfig.CACHE_SUBSCRIPTION_ID, AppConfig.PREF_MODE, AppConfig.PREF_SOCKS_PORT)) {
            previousSettings[key] = MmkvManager.decodeSettingsString(key)
        }
        MmkvManager.encodeSubscription(groupId, SubscriptionItem(remarks = "Speedtest fixture"))
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, groupId)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
    }

    @After
    fun restoreFixture() {
        MessageHelper.sendMsg2TestService(context, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
        if (proxyStarted) LauncherManager.stopService(context)
        scenario?.close()
        context.unregisterReceiver(receiver)
        for (guid in guids) MmkvManager.removeServer(guid)
        MmkvManager.removeSubscription(groupId)
        if (previousSelection != null) MmkvManager.setSelectServer(previousSelection!!)
        for ((key, value) in previousSettings) {
            MmkvManager.encodeSettings(key, value)
        }
    }

    @Test
    fun shouldMeasurePersistAndSortFromTheMainMenu() {
        // 输入包含可用、连接失败与自定义配置，检查速度不会串到其他 GUID。
        val first = addProfile("Probe A", 18080)
        val second = addProfile("Probe B", 18080)
        val failed = addProfile("Probe failed", 1)
        val custom = addProfile("Probe custom", 1, EConfigType.CUSTOM)
        MmkvManager.setSelectServer(second)
        openMain()

        touchMenu(R.string.title_speed_test_all_server)
        awaitEvent(AppConfig.MSG_MEASURE_CONFIG_FINISH)

        val firstResult = MmkvManager.decodeServerAffiliationInfo(first)!!
        val secondResult = MmkvManager.decodeServerAffiliationInfo(second)!!
        assertTrue(firstResult.testDelayMillis > 0L)
        assertTrue(firstResult.testSpeedBytesPerSecond > 0L)
        assertTrue(secondResult.testSpeedBytesPerSecond > 0L)
        assertEquals(-1L, MmkvManager.decodeServerAffiliationInfo(failed)!!.testSpeedBytesPerSecond)
        assertEquals(RealPingResult.SPEED_UNSUPPORTED, MmkvManager.decodeServerAffiliationInfo(custom)!!.testSpeedBytesPerSecond)
        assertEquals(second, MmkvManager.getSelectServer())
        awaitNode("Probe A")

        val originalOrder = MmkvManager.decodeServerList(groupId)
        val speeds = mapOf(first to firstResult.testSpeedBytesPerSecond, second to secondResult.testSpeedBytesPerSecond)
        val expectedOrder = SpeedtestManager.sortBySpeed(originalOrder, speeds)
        touchMenu(R.string.title_sort_by_speed)
        awaitOrder(expectedOrder)

        // 重建 Activity 后重新从存储加载，排序、速度以及选中节点都必须保留。
        scenario!!.recreate()
        assertTrue(awaitNode("Probe A").contentDescription.toString().contains("MB/s"))
        assertEquals(expectedOrder, MmkvManager.decodeServerList(groupId))
        assertEquals(firstResult.testSpeedBytesPerSecond, MmkvManager.decodeServerAffiliationInfo(first)!!.testSpeedBytesPerSecond)
        assertEquals(second, MmkvManager.getSelectServer())
    }

    /** 验证触摸清理、持久化和重建后再次测速排序都只使用剩余 GUID。 */
    @Test
    fun shouldClearOnlyFailedNodesAndRetestTheRemainingNodesFromTheMainMenu() {
        // 构造不同状态，只把延迟已确认失败的一个节点作为清理目标。
        val healthy = addProfile("Cleanup healthy", 18080)
        val failed = addProfile("Cleanup failed", 1)
        val downloadFailed = addProfile("Keep download failure", 1)
        addProfile("Keep untested", 1)
        val cancelled = addProfile("Keep cancelled", 1)
        val unsupported = addProfile("Keep unsupported", 1, EConfigType.CUSTOM)
        storeResult(healthy, 20L, 1_000_000L)
        storeResult(failed, -1L, -1L)
        storeResult(downloadFailed, 20L, -1L)
        MmkvManager.prepareServerTest(listOf(cancelled), "cancelled-fixture", testSpeed = true)
        storeResult(unsupported, -1L, RealPingResult.SPEED_UNSUPPORTED)
        MmkvManager.setSelectServer(healthy)
        val remaining = MmkvManager.decodeServerList(groupId).filter { it != failed }
        openMain()

        // 通过真实菜单点击立即清理，不调用存储删除接口绕过 ViewModel。
        touchMenu(R.string.title_clear_invalid_servers)
        awaitOrder(remaining)
        awaitNodeAbsent("Cleanup failed")
        assertNull(MmkvManager.decodeServerConfig(failed))
        assertEquals(healthy, MmkvManager.getSelectServer())
        assertEquals(-1L, MmkvManager.decodeServerAffiliationInfo(downloadFailed)!!.testSpeedBytesPerSecond)
        assertEquals(0L, MmkvManager.decodeServerAffiliationInfo(cancelled)!!.testDelayMillis)
        assertEquals(RealPingResult.SPEED_UNSUPPORTED, MmkvManager.decodeServerAffiliationInfo(unsupported)!!.testSpeedBytesPerSecond)

        // Activity 重建后从同一持久化列表重测，服务回执必须恰好覆盖剩余节点。
        scenario!!.recreate()
        awaitNode("Cleanup healthy")
        touchMenu(R.string.title_speed_test_all_server)
        val finish = awaitEvent(AppConfig.MSG_MEASURE_CONFIG_FINISH)
        val testedGuids = mutableSetOf<String>()
        for (event in events) {
            if (event.getIntExtra("key", 0) != AppConfig.MSG_MEASURE_CONFIG_SUCCESS || requestId(event) != requestId(finish)) continue
            val result = event.serializable<RealPingResult>("content") ?: continue
            testedGuids.add(result.guid)
        }
        assertEquals(remaining.toSet(), testedGuids)
        assertNull(MmkvManager.decodeServerAffiliationInfo(failed))

        // 排序仍从剩余 GUID 读取速度，不能把已经删除的节点写回组索引。
        val speeds = linkedMapOf<String, Long>()
        for (guid in remaining) speeds[guid] = MmkvManager.decodeServerAffiliationInfo(guid)!!.testSpeedBytesPerSecond
        val expected = SpeedtestManager.sortBySpeed(remaining, speeds)
        touchMenu(R.string.title_sort_by_speed)
        awaitOrder(expected)
    }

    /** 验证手动订阅更新沿用完整响应，清理过的节点可以重新导入。 */
    @Test
    fun shouldReimportTheWholeSubscriptionWhenManuallyUpdatingAfterCleanup() {
        val contents = "socks://127.0.0.1:1#Restored-failed\nsocks://127.0.0.1:18080#Restored-healthy"
        val subscription = LocalSubscription(contents)
        try {
            // 使用本地订阅响应，测试不访问用户订阅 URL，也不依赖远端内容变化。
            MmkvManager.encodeSubscription(groupId, SubscriptionItem(
                remarks = "Cleanup subscription fixture", url = subscription.url, allowInsecureUrl = true
            ))
            assertEquals(2, AngConfigManager.importBatchConfig(contents, groupId, append = false).first)
            val original = MmkvManager.decodeServerList(groupId)
            guids.addAll(original)
            val failed = original.first { MmkvManager.decodeServerConfig(it)!!.remarks == "Restored-failed" }
            val healthy = original.first { it != failed }
            storeResult(failed, -1L, -1L)
            storeResult(healthy, 20L, 1_000_000L)
            MmkvManager.setSelectServer(healthy)
            openMain()

            touchMenu(R.string.title_clear_invalid_servers)
            awaitOrder(listOf(healthy))
            awaitNodeAbsent("Restored-failed")
            touchMenu(R.string.title_sub_update)
            awaitNode("Restored-failed")

            // 更新产生全新的 GUID，不能受此前本地清理记录限制。
            val restored = MmkvManager.decodeServerList(groupId)
            guids.addAll(restored)
            assertEquals(2, restored.size)
            assertFalse(restored.contains(failed))
            assertEquals(setOf("Restored-failed", "Restored-healthy"), restored.map { MmkvManager.decodeServerConfig(it)!!.remarks }.toSet())
            assertEquals(subscription.url, MmkvManager.decodeSubscription(groupId)!!.url)
            scenario!!.recreate()
            awaitNode("Restored-failed")
            assertEquals(restored, MmkvManager.decodeServerList(groupId))
        } finally {
            subscription.close()
        }
    }

    /** 验证菜单的键盘 Enter、Space 和 D-pad 中心键与触摸执行相同删除。 */
    @Test
    fun shouldActivateInvalidNodeCleanupWithKeyboardAndDpad() {
        for (keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER)) {
            // 每种按键使用新的节点和 Activity，避免前一次空列表掩盖操作失败。
            val failed = addProfile("Keyboard cleanup $keyCode", 1)
            storeResult(failed, -1L, -1L)
            openMain()
            touch(awaitNode(context.getString(R.string.acc_more)))
            val cleanup = awaitMenuAction(R.string.title_clear_invalid_servers)
            assertTrue(cleanup.isClickable)
            assertTrue(cleanup.isFocusable)
            assertTrue(cleanup.performAction(AccessibilityNodeInfo.ACTION_FOCUS))

            instrumentation.sendKeyDownUpSync(keyCode)
            awaitOrder(emptyList())

            assertNull(MmkvManager.decodeServerConfig(failed))
            scenario!!.close()
            scenario = null
        }
    }

    /** 传入 -e manualTalkback true 并启用 TalkBack，以滑动遍历焦点，再用设备双击或读屏快捷键激活。 */
    @Test
    fun shouldClearInvalidNodesWithTalkBack() {
        assumeTrue("此场景需显式传入 manualTalkback=true", InstrumentationRegistry.getArguments().getString("manualTalkback") == "true")
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assumeTrue("此场景需先启用设备上已安装的 TalkBack", enabledServices?.contains("com.google.android.marvin.talkback") == true)

        // 构造独立失败和可用节点，读屏激活必须只删除已确认失败的节点。
        val failed = addProfile("TalkBack cleanup failed", 1)
        val healthy = addProfile("TalkBack cleanup healthy", 1)
        storeResult(failed, -1L, -1L)
        storeResult(healthy, 20L, 1_000_000L)
        openMain()
        assertTrue(awaitMenuAction(R.string.acc_more).performAction(AccessibilityNodeInfo.ACTION_CLICK))

        // 从上一菜单行开始，等待设备右滑两次再左滑一次；文本子节点不能成为额外焦点。
        assertTrue(awaitMenuAction(R.string.title_speed_test_all_server).performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        awaitAccessibilityFocus(R.string.title_speed_test_all_server)
        awaitAccessibilityFocus(R.string.title_clear_invalid_servers, timeoutMillis = 20_000L)
        awaitAccessibilityFocus(R.string.title_sub_update, timeoutMillis = 20_000L)
        awaitAccessibilityFocus(R.string.title_clear_invalid_servers, timeoutMillis = 20_000L)

        // 等待设备双击或读屏快捷键由 TalkBack 派发；测试注入的 MotionEvent 会绕过读屏输入过滤。
        awaitOrder(listOf(healthy), timeoutMillis = 20_000L)
        awaitNodeAbsent("TalkBack cleanup failed")
        assertNull(MmkvManager.decodeServerConfig(failed))
        assertTrue(MmkvManager.decodeServerConfig(healthy) != null)
        scenario!!.recreate()
        awaitNode("TalkBack cleanup healthy")
        assertEquals(listOf(healthy), MmkvManager.decodeServerList(groupId))
    }

    @Test
    fun shouldBoundConcurrencyDeduplicateCancelAndImmediatelyRetest() {
        val proxy = StalledProxy()
        try {
            // SOCKS 握手保持等待，让三个原生测试同时停在可观察的网络阶段。
            val stalled = mutableListOf<String>()
            for (number in 1..6) stalled.add(addProfile("Pending $number", proxy.port))
            val retryGuid = addProfile("Retry", 18080)
            openMain()
            val requestId = UUID.randomUUID().toString()
            MmkvManager.prepareServerTest(stalled, requestId, testSpeed = true)
            val message = TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_START, groupId, stalled, testSpeed = true)

            MessageHelper.sendMsg2TestService(context, message, requestId)
            MessageHelper.sendMsg2TestService(context, message, requestId)
            assertTrue(proxy.entered.await(8, TimeUnit.SECONDS))
            SystemClock.sleep(250)
            assertEquals(SpeedtestManager.DOWNLOAD_CONCURRENCY, proxy.sockets.size)

            MessageHelper.sendMsg2TestService(context, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
            MessageHelper.sendMsg2TestService(context, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
            val retryId = startBatch(listOf(retryGuid), testSpeed = false, onlyTcp = true)
            awaitEvent(AppConfig.MSG_MEASURE_CONFIG_CANCEL, requestId)
            awaitEvent(AppConfig.MSG_MEASURE_CONFIG_FINISH, retryId)

            assertTrue("取消应关闭正在握手的 socket", proxy.closed.await(3, TimeUnit.SECONDS))
            assertFalse(events.any { it.getIntExtra("key", 0) == AppConfig.MSG_MEASURE_CONFIG_SUCCESS && requestId(it) == requestId })
            assertTrue(MmkvManager.decodeServerAffiliationInfo(retryGuid)!!.testDelayMillis >= 0L)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun shouldCompleteEmptyAndFailedBatchesAndAllowRepeatedStops() {
        val failed = addProfile("Unavailable", 1)
        openMain()
        val emptyId = startBatch(emptyList())
        awaitEvent(AppConfig.MSG_MEASURE_CONFIG_CANCEL, emptyId)

        // 该节点能创建临时核心，但建立代理连接失败，覆盖资源获取后的异常清理。
        val failedId = startBatch(listOf(failed))
        val result = awaitEvent(AppConfig.MSG_MEASURE_CONFIG_SUCCESS, failedId).serializable<RealPingResult>("content")!!
        awaitEvent(AppConfig.MSG_MEASURE_CONFIG_FINISH, failedId)
        assertEquals(-1L, result.delayMillis)
        assertEquals(-1L, result.speedBytesPerSecond)
        MessageHelper.sendMsg2TestService(context, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
        MessageHelper.sendMsg2TestService(context, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
    }

    /** 验证启动等待期间取消后，不会继续连接节点，且下一批仍可正常运行。 */
    @Test
    fun shouldCancelDuringStartupWaitAndAllowAnotherBatch() {
        val proxy = StalledProxy()
        try {
            // 在核心启动后的等待窗口内取消，监听器用于发现迟到的代理连接。
            val pending = addProfile("Cancel during startup", proxy.port)
            val retry = addProfile("Retry after startup cancellation", 18080)
            openMain()
            val requestId = startBatch(listOf(pending))
            SystemClock.sleep(200)

            MessageHelper.sendMsg2TestService(context, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
            awaitEvent(AppConfig.MSG_MEASURE_CONFIG_CANCEL, requestId)
            SystemClock.sleep(1_200)

            // 等待原本的启动窗口结束，确认旧批次没有恢复请求或写回结果。
            assertTrue(proxy.sockets.isEmpty())
            assertFalse(events.any { it.getIntExtra("key", 0) == AppConfig.MSG_MEASURE_CONFIG_SUCCESS && requestId(it) == requestId })
            val retryId = startBatch(listOf(retry), testSpeed = false, onlyTcp = true)
            awaitEvent(AppConfig.MSG_MEASURE_CONFIG_FINISH, retryId)
            assertTrue(MmkvManager.decodeServerAffiliationInfo(retry)!!.testDelayMillis >= 0L)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun shouldKeepTheRunningProxyAndSelectedNodeDuringMeasurement() {
        val connected = addProfile("Connected", 18080)
        val tested = addProfile("Measured", 18080)
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, "PROXY")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, "18081")
        MmkvManager.setSelectServer(connected)
        openMain()

        // 通过实际服务启动入口和守护进程回执判断运行状态，不读取 UI 进程中的核心单例。
        LauncherManager.startService(context)
        proxyStarted = true
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        assertProxyAvailable()
        val requestId = startBatch(listOf(tested))
        awaitEvent(AppConfig.MSG_MEASURE_CONFIG_FINISH, requestId)
        assertTrue(MmkvManager.decodeServerAffiliationInfo(tested)!!.testSpeedBytesPerSecond > 0L)
        assertEquals(connected, MmkvManager.getSelectServer())
        assertProxyAvailable()
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        proxyStarted = false
    }

    private fun addProfile(name: String, port: Int, type: EConfigType = EConfigType.SOCKS): String {
        val profile = ProfileItem(configType = type, remarks = name, subscriptionId = groupId,
            server = AppConfig.LOOPBACK, serverPort = port.toString())
        val guid = MmkvManager.encodeServerConfig("", profile)
        guids.add(guid)
        return guid
    }

    private fun storeResult(guid: String, delay: Long, speed: Long) {
        // 通过真实测速记录写入接口准备状态，保留请求 ID 和旧结果的清空规则。
        MmkvManager.prepareServerTest(listOf(guid), "cleanup-fixture", testSpeed = true)
        assertTrue(MmkvManager.encodeServerTestResult(RealPingResult(guid, delay, speed), "cleanup-fixture"))
    }

    private fun openMain() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitNode(context.getString(R.string.acc_more))
    }

    private fun startBatch(ids: List<String>, testSpeed: Boolean = true, onlyTcp: Boolean = false): String {
        val requestId = UUID.randomUUID().toString()
        MmkvManager.prepareServerTest(ids, requestId, testSpeed)
        val message = TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_START, groupId, ids, onlyTcp, testSpeed)
        MessageHelper.sendMsg2TestService(context, message, requestId)
        return requestId
    }

    private fun requestId(intent: Intent): String = intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty()

    private fun awaitEvent(key: Int, id: String? = null): Intent {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            for (event in events) {
                if (event.getIntExtra("key", 0) == key && (id == null || requestId(event) == id)) return event
            }
            SystemClock.sleep(25)
        }
        throw AssertionError("未收到服务回执：key=$key requestId=$id")
    }

    private fun awaitOrder(expected: List<String>, timeoutMillis: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (MmkvManager.decodeServerList(groupId) == expected) return
            SystemClock.sleep(25)
        }
        assertEquals(expected, MmkvManager.decodeServerList(groupId))
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            val node = findNode(root, label)
            if (node != null) return node
            SystemClock.sleep(50)
        }
        throw AssertionError("界面缺少可访问节点：$label")
    }

    private fun awaitNodeAbsent(label: String) {
        // 等待 ViewModel 刷新最终列表，不能仅凭存储删除完成就启动下一次 UI 操作。
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && findNode(root, label) == null) return
            SystemClock.sleep(50)
        }
        throw AssertionError("清理后仍显示节点：$label")
    }

    private fun awaitMenuAction(labelRes: Int): AccessibilityNodeInfo {
        // UiAutomation 包含不可操作的文本子节点，焦点和激活应落在承载同一标签的菜单行。
        var action = awaitNode(context.getString(labelRes))
        while (!action.isClickable) {
            action = action.parent ?: throw AssertionError("菜单缺少可操作的父节点：${context.getString(labelRes)}")
        }
        return action
    }

    private fun awaitAccessibilityFocus(labelRes: Int, timeoutMillis: Long = 5_000L): AccessibilityNodeInfo {
        // 等 TalkBack 更新最终焦点后才发出下一手势，避免读屏服务的异步回执串行错位。
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val action = awaitMenuAction(labelRes)
            if (action.isAccessibilityFocused) {
                println("TalkBack focus: ${context.getString(labelRes)}, enabled=${action.isEnabled}, clickable=${action.isClickable}")
                SystemClock.sleep(800L)
                return action
            }
            SystemClock.sleep(50L)
        }
        throw AssertionError("TalkBack 焦点未到达菜单：${context.getString(labelRes)}")
    }

    private fun findNode(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        // Compose 提供虚拟无障碍子节点，按树遍历，不能依赖 View 的文本搜索实现。
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val matches = node.text?.contains(label) == true || node.contentDescription?.contains(label) == true
            if (node.isVisibleToUser && matches) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                pending.add(child)
            }
        }
        return null
    }

    private fun touchMenu(labelRes: Int) {
        touch(awaitNode(context.getString(R.string.acc_more)))
        touch(awaitNode(context.getString(labelRes)))
    }

    private fun touch(node: AccessibilityNodeInfo) {
        // 用真实触摸事件激活菜单，而不是直接调用 ViewModel 绕过交互层。
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        instrumentation.sendPointerSync(down)
        instrumentation.sendPointerSync(up)
        down.recycle()
        up.recycle()
        instrumentation.waitForIdleSync()
    }

    private fun assertProxyAvailable() {
        val client = SpeedtestManager.createTestClient(SettingsManager.getSocksPort())
        try {
            val delay = runBlocking { SpeedtestManager.measureDelay(client, SettingsManager.getDelayTestUrl()) }
            assertTrue(delay > 0L)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    /** 本用例拥有单次订阅响应和后台线程，结束时由 close 释放，不使用用户网络数据。 */
    private class LocalSubscription(private val content: String) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName(AppConfig.LOOPBACK))
        private val executor = Executors.newSingleThreadExecutor()
        // close 从测试线程读取后台线程保存的连接，Volatile 保证能看见最新引用。
        @Volatile private var connection: Socket? = null
        val url = "http://127.0.0.1:${listener.localPort}/subscription"
        private val task = executor.submit(::serveSubscription)

        private fun serveSubscription() {
            try {
                val socket = listener.accept()
                connection = socket
                try {
                    // 读完请求头再发送完整订阅文本，避免测试成功依赖非标准 HTTP 行为。
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    var line = input.readLine()
                    while (!line.isNullOrEmpty()) line = input.readLine()
                    val body = content.toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    val output = socket.getOutputStream()
                    output.write(headers.toByteArray(Charsets.UTF_8))
                    output.write(body)
                    output.flush()
                } finally {
                    socket.close()
                }
            } catch (error: SocketException) {
                if (!listener.isClosed) throw error
            }
        }

        override fun close() {
            // 取消尚未到来的请求和等待线程，测试用网络资源不会留到下一个用例。
            listener.close()
            connection?.close()
            task.cancel(true)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private class StalledProxy : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName(AppConfig.LOOPBACK))
        private val executor = Executors.newCachedThreadPool()
        val sockets = CopyOnWriteArrayList<Socket>()
        val entered = CountDownLatch(SpeedtestManager.DOWNLOAD_CONCURRENCY)
        val closed = CountDownLatch(SpeedtestManager.DOWNLOAD_CONCURRENCY)
        val port: Int = listener.localPort
        private val acceptTask = executor.submit(::acceptClients)

        private fun acceptClients() {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    entered.countDown()
                    executor.execute { waitForDisconnect(socket) }
                }
            } catch (error: SocketException) {
                if (!listener.isClosed) throw error
            }
        }

        private fun waitForDisconnect(socket: Socket) {
            try {
                socket.soTimeout = 15_000
                val input = socket.getInputStream()
                // 只在客户端真正断开时计数，夹具清理不能让取消断言误通过。
                while (input.read() >= 0) { }
                closed.countDown()
            } catch (error: SocketException) {
                // close() 主动关闭等待中的 socket 是正常清理，不应使整个测试进程崩溃。
                if (!listener.isClosed) throw error
            } finally {
                socket.close()
            }
        }

        override fun close() {
            listener.close()
            for (socket in sockets) socket.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            acceptTask.get()
        }
    }
}
