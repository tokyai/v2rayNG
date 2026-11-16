package com.v2ray.ang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
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
import org.junit.Assert.assertTrue
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

    private fun awaitOrder(expected: List<String>) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (MmkvManager.decodeServerList(groupId) == expected) return
            SystemClock.sleep(25)
        }
        assertEquals(expected, MmkvManager.decodeServerList(groupId))
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = findNode(root, label)
            if (node != null) return node
            SystemClock.sleep(50)
        }
        throw AssertionError("界面缺少可访问节点：$label")
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
