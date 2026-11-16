package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import libv2ray.CoreCallbackHandler

/**
 * 单次下载测试独占临时核心和 HTTP 客户端；它的生命周期与正在连接的 VPN 核心不同。
 * 由测试服务的后台协程调用，完成或取消后关闭 HTTP 请求并停止临时核心。
 * 现有原生库中的 SOCKS 握手可能等待自身超时退出，stopLoop 不保证立即断开这种连接。
 * @author SHIXIAOYANG
 */
object CoreSpeedtest {

    private const val CORE_STARTUP_DELAY_MILLIS = 1_000L

    /** 测量一个节点的真实延迟和下载速度，不改变用户当前选中的节点。 */
    suspend fun measure(context: Context, guid: String): RealPingResult {
        // 自定义配置可能包含任意监听器或路由；跳过它，不能将“不支持”记作失效节点。
        val profile = MmkvManager.decodeServerConfig(guid) ?: return RealPingResult(guid, -1L, -1L)
        if (profile.configType == EConfigType.CUSTOM) {
            return RealPingResult(guid, 0L, RealPingResult.SPEED_UNSUPPORTED)
        }
        // 生成独立端口和配置，失败时明确返回失败，不能使用当前连接代替待测节点。
        val port = Utils.findRandomFreePort()
        val config = CoreConfigManager.getV2rayConfig4DownloadTest(context, guid, port)
        if (!config.status) return RealPingResult(guid, -1L, -1L)
        val controller = CoreNativeManager.newCoreController(TestCallback())
        val client = SpeedtestManager.createTestClient(port)
        var delayMillis = -1L
        try {
            currentCoroutineContext().ensureActive()
            controller.startLoop(config.content, 0)
            // 与 Windows RunMixedTestAsync 一致，启动后等待一秒；取消等待也会进入 finally 释放核心。
            delay(CORE_STARTUP_DELAY_MILLIS)
            currentCoroutineContext().ensureActive()

            // 先验证代理可用，再下载文件；失败节点不会继续耗费下载流量。
            delayMillis = SpeedtestManager.measureDelay(client, SettingsManager.getDelayTestUrl())
            // Windows 为下载另建客户端；清空本次延迟探测的连接，避免下载复用预热连接。
            client.connectionPool.evictAll()
            val speed = SpeedtestManager.measureDownloadSpeed(client)
            return RealPingResult(guid, delayMillis, speed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Download test failed: component=CoreTestService guid=$guid", error)
            return RealPingResult(guid, delayMillis, -1L)
        } finally {
            // 取消会先关闭 HTTP 请求；这里仍在测试服务的 IO 协程中释放临时核心。
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            try {
                controller.stopLoop()
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Stop download test core failed: component=CoreTestService guid=$guid", error)
            }
        }
    }

    private class TestCallback : CoreCallbackHandler {
        override fun startup(): Long = 0L
        override fun shutdown(): Long = 0L
        // 测试核心的原生状态不代表主服务状态，也不把原生诊断文本发送给界面。
        override fun onEmitStatus(l: Long, s: String?): Long = 0L
    }
}
