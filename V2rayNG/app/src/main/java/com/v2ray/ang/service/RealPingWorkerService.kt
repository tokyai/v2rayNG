package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreSpeedtest
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()

    suspend fun <T> run(configType: EConfigType, block: () -> T): T {
        // Custom profiles bypass speed-test trimming and start complete Xray configs.
        // Parallel teardown can abort the native probe process, so serialize their
        // JNI measurements globally across batches.
        return if (configType == EConfigType.CUSTOM) {
            customConfigMutex.withLock { block() }
        } else {
            block()
        }
    }
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * 每批测试有自己的取消边界；由调用服务的 Job 管理生命周期。
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val testSpeed: Boolean = false,
    parentJob: Job? = null,
    private val onEvent: suspend (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob(parentJob)
    private val concurrency = when {
        testSpeed -> SpeedtestManager.DOWNLOAD_CONCURRENCY
        onlyTcp -> SettingsManager.getRealPingConcurrency() * 2
        else -> SettingsManager.getRealPingConcurrency()
    }
    private val permits = Semaphore(concurrency.coerceAtLeast(1))
    private val scope = CoroutineScope(job + Dispatchers.IO + CoroutineName("ServerTestBatch"))
    private val started = AtomicBoolean(false)

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(guids.size)

    /** 启动一次批量测试；重复调用不会创建第二批网络请求。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // 信号量覆盖整个节点测试，HTTP 挂起期间也不会放大下载并发。
        val jobs = ArrayList<Job>(guids.size)
        for (guid in guids) {
            jobs.add(scope.launch { runWithPermit(guid) })
        }
        scope.launch { awaitTests(jobs) }
    }

    private suspend fun runWithPermit(guid: String) {
        permits.withPermit { runTest(guid) }
    }

    private suspend fun runTest(guid: String) {
        runningCount.incrementAndGet()
        try {
            val result = measure(guid)
            currentCoroutineContext().ensureActive()
            onEvent(RealPingEvent.Result(guid, result.delayMillis, result.speedBytesPerSecond))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Publish test result failed: component=RealPingWorkerService guid=$guid", error)
        } finally {
            val remaining = totalCount.decrementAndGet()
            val running = runningCount.decrementAndGet()
            if (scope.isActive) onEvent(RealPingEvent.Progress("$running / $remaining"))
        }
    }

    private suspend fun measure(guid: String): RealPingResult {
        try {
            if (testSpeed) return CoreSpeedtest.measure(context, guid)
            val delayMillis = if (onlyTcp) startTcping(guid) else startRealPing(guid)
            return RealPingResult(guid, delayMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Measure server failed: component=RealPingWorkerService guid=$guid", error)
            return RealPingResult(guid, -1L, if (testSpeed) -1L else null)
        }
    }

    private suspend fun awaitTests(jobs: List<Job>) {
        try {
            jobs.joinAll()
            currentCoroutineContext().ensureActive()
            onEvent(RealPingEvent.Finish("0"))
        } finally {
            // 正常结束也要完成批次 Job，调用服务才能等待所有子任务释放资源。
            job.complete()
        }
    }

    /** 取消排队及正在执行的探测，下载请求和临时核心随后在各自 finally 中释放。 */
    fun cancel() {
        job.cancel()
    }

    private suspend fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)
            if (tcpTime <= -1L) {
                return retFailure
            }
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return RealPingExecutionLimiter.run(config.configType) {
            CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
        }
    }

    private fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)

            return tcpTime
        }

        return retFailure
    }
}
