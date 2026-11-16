package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class CoreTestService : Service() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    // 服务持有批次的父 Job；取消时先阻止结果发布，等待子任务清理后再结束请求。
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate + CoroutineName("CoreTests"))
    private val activeRequests = ConcurrentHashMap<String, Job>()
    private var latestStartId = 0
    private var destroyed = false
    private var stopping = false
    private val cancelAction by lazy {
        val intent = Intent(this, CoreTestService::class.java).putExtra(
            "content",
            TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        val pendingIntent = PendingIntent.getService(
            this,
            NotificationChannelType.CORE_TEST.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Action.Builder(
            R.drawable.ic_stop_24dp,
            getString(R.string.action_cancel),
            pendingIntent
        ).build()
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        destroyed = true
        stopping = true
        cancelWorkers()
        serviceJob.cancel()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            getString(R.string.title_real_ping_all_server),
            cancelAction
        )
        latestStartId = startId
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> handleMeasureStart(
                message, startId, intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty()
            )
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel()
            else -> {
                NotificationHelper.stopForeground(this); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(message: TestServiceMessage, startId: Int, requestId: String) {
        if (stopping || requestId.isBlank()) {
            stopSelf(startId)
            return
        }
        if (activeRequests.containsKey(requestId)) return
        val title = if (message.testSpeed) R.string.title_speed_test_all_server else R.string.title_real_ping_all_server
        NotificationHelper.updateNotification(NotificationChannelType.CORE_TEST, this, getString(R.string.app_name), getString(title))
        // LAZY 先登记再执行，极快的失败也能找到并取消所属请求。
        val requestJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            runMeasure(message, requestId)
        }
        activeRequests[requestId] = requestJob
        requestJob.start()
    }

    private suspend fun runMeasure(message: TestServiceMessage, requestId: String) {
        try {
            val guids = message.serverGuids.distinct()
            if (guids.isEmpty()) {
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "", requestId)
                return
            }
            // 进入前台后才在 IO 线程初始化原生环境、读取配置并启动测试。
            withContext(Dispatchers.IO) {
                CoreNativeManager.initCoreEnv(this@CoreTestService)
                startWorker(message, requestId, guids, currentCoroutineContext()[Job])
            }
            currentCoroutineContext().ensureActive()
            finishBatch(message, guids)
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, "0", requestId)
        } catch (cancelled: CancellationException) {
            if (!destroyed) MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "", requestId)
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Run test batch failed: component=CoreTestService groupId=${message.subscriptionId}", error)
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_CANCEL, getString(R.string.toast_failure), requestId)
        } finally {
            activeRequests.remove(requestId)
            // Android 可能已接收下一次启动、但尚未回调 onStartCommand；按启动编号停止，避免误停新请求。
            if (!destroyed && activeRequests.isEmpty()) {
                val stopped = stopSelfResult(latestStartId)
                if (stopped) NotificationHelper.stopForeground(this)
            }
        }
    }

    private fun startWorker(message: TestServiceMessage, requestId: String, guids: List<String>, parentJob: Job?) {
        val worker = RealPingWorkerService(
            context = this,
            guids = guids,
            onlyTcp = message.onlyTcp,
            testSpeed = message.testSpeed,
            parentJob = parentJob,
            onEvent = { event -> publishWorkerEvent(event, requestId) },
        )
        worker.start()
    }

    private suspend fun publishWorkerEvent(event: RealPingEvent, requestId: String) {
        if (activeRequests[requestId]?.isActive != true) return
        try {
            // MMKV 写入仍在后台；发送结果回到主线程，与取消命令按顺序执行。
            if (event is RealPingEvent.Result) {
                val result = RealPingResult(event.guid, event.delayMillis, event.speedBytesPerSecond)
                if (!MmkvManager.encodeServerTestResult(result, requestId)) return
            }
            withContext(Dispatchers.Main.immediate) {
                if (activeRequests[requestId]?.isActive == true) handleWorkerEvent(event, requestId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Publish batch event failed: component=CoreTestService requestId=$requestId", error)
            withContext(Dispatchers.Main.immediate) {
                MessageHelper.sendMsg2UI(this@CoreTestService, AppConfig.MSG_MEASURE_CONFIG_CANCEL, getString(R.string.toast_failure), requestId)
                activeRequests[requestId]?.cancel()
            }
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, requestId: String) {
        when (event) {
            is RealPingEvent.Progress -> {
                NotificationHelper.updateNotification(
                    channelType = NotificationChannelType.CORE_TEST,
                    context = this,
                    title = getString(R.string.app_name),
                    content = getString(R.string.connection_running_task_left, event.text)
                )
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, event.text, requestId)
            }

            is RealPingEvent.Result -> {
                val result = RealPingResult(event.guid, event.delayMillis, event.speedBytesPerSecond)
                MessageHelper.sendMsg2UI(
                    this,
                    AppConfig.MSG_MEASURE_CONFIG_SUCCESS,
                    result,
                    requestId,
                )
            }

            // 父协程等到所有核心释放后，才执行自动排序并发送完成事件。
            is RealPingEvent.Finish -> Unit
        }
    }

    private suspend fun finishBatch(message: TestServiceMessage, guids: List<String>) {
        if (message.subscriptionId.isEmpty()) return
        val sortAfterTest = withContext(Dispatchers.IO) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)) {
                // 搜索结果之外的节点没有参加本轮测试，不能因旧结果被顺带删除。
                for (guid in guids) MmkvManager.removeInvalidServer(guid)
            }
            MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)
        }
        if (!sortAfterTest) return
        if (message.testSpeed) {
            AngConfigManager.sortBySpeed(guids)
        } else {
            withContext(Dispatchers.IO) { AngConfigManager.sortByTestResultsForSub(message.subscriptionId) }
        }
    }

    private fun handleMeasureCancel() {
        stopping = true
        cancelWorkers()
        NotificationHelper.stopForeground(this)
        stopSelf()
    }

    private fun cancelWorkers() {
        // Reply for each cancelled request, never for a newer UI request that has not started here.
        for ((requestId, job) in activeRequests) {
            job.cancel()
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "", requestId)
        }
        activeRequests.clear()
    }
}
