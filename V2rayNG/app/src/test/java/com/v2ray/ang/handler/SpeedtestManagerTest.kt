package com.v2ray.ang.handler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SpeedtestManagerTest {

    @Test
    fun shouldSortFastestFirstAndKeepUnmeasuredAndFailedNodesLast() {
        val ids = listOf("unmeasured", "slow", "fast", "failed", "tied", "unsupported")
        val speeds = mapOf("slow" to 10L, "fast" to 100L, "failed" to -1L, "tied" to 100L, "unsupported" to -2L)

        val result = SpeedtestManager.sortBySpeed(ids, speeds)

        assertEquals(listOf("fast", "tied", "slow", "unmeasured", "failed", "unsupported"), result)
        assertEquals(emptyList<String>(), SpeedtestManager.sortBySpeed(emptyList(), speeds))
    }

    @Test
    fun shouldStopDownloadingAtTheByteLimitAndCalculateBytesPerSecond() {
        val input = ByteArrayInputStream(ByteArray(100))
        val times = listOf(0L, 1_000_000_000L, 1_000_000_000L).iterator()

        val speed = SpeedtestManager.readDownloadSpeed(input, byteLimit = 20L, nanoTime = times::next)

        assertEquals(20L, speed)
        assertEquals(80, input.available())
    }

    @Test
    fun shouldStopDownloadingWhenTheMeasurementWindowEnds() {
        val input = ByteArrayInputStream(ByteArray(100_000))
        val times = listOf(0L, 2_000_000_000L, 4_000_000_000L, 4_000_000_000L).iterator()

        val speed = SpeedtestManager.readDownloadSpeed(input, durationNanos = 3_000_000_000L, nanoTime = times::next)

        assertEquals(16_384L, speed)
        assertEquals(100_000 - 65_536, input.available())
    }

    /** 验证下载结果采用最快的一秒窗口。 */
    @Test
    fun shouldReportTheFastestOneSecondWindowInsteadOfTheWholeDownloadAverage() {
        // 第一段较慢，随后两段加速；结果应是最快分段，而不是全程平均值。
        val input = ByteArrayInputStream(ByteArray(3 * 32 * 1024))
        val times = listOf(0L, 2_000_000_000L, 3_000_000_000L, 4_000_000_000L).iterator()

        val speed = SpeedtestManager.readDownloadSpeed(input, nanoTime = times::next)

        assertEquals(32_768L, speed)
    }

    /** 验证不足一秒的尾段不会制造虚假的带宽峰值。 */
    @Test
    fun shouldIgnoreAnIncompleteTailAfterACompleteSpeedWindow() {
        // 最后 4 KiB 瞬间进入缓冲区，不应被当成超过已测窗口的带宽峰值。
        val input = ByteArrayInputStream(ByteArray(2 * 32 * 1024 + 4096))
        val times = listOf(0L, 2_000_000_000L, 3_000_000_000L, 3_001_000_000L).iterator()

        val speed = SpeedtestManager.readDownloadSpeed(input, nanoTime = times::next)

        assertEquals(32_768L, speed)
    }

    /** 验证很快下载完的小文件仍能获得有效速度。 */
    @Test
    fun shouldUseTheActualWindowWhenTheFileFinishesWithinOneSecond() {
        // 移动端达到文件上限时可能不足一秒，仍须返回有效速度。
        val input = ByteArrayInputStream(ByteArray(1024))
        val times = listOf(0L, 100_000_000L, 100_000_000L).iterator()

        val speed = SpeedtestManager.readDownloadSpeed(input, nanoTime = times::next)

        assertEquals(10_240L, speed)
    }

    /** 验证首个窗口包含建立下载请求的耗时。 */
    @Test
    fun shouldIncludeRequestSetupTimeInTheFirstSpeedWindow() {
        // 请求从零时刻开始，两秒后收到数据；不能把缓冲区读取时间误当成网络耗时。
        val input = ByteArrayInputStream(ByteArray(32 * 1024))
        val times = listOf(2_000_000_000L).iterator()

        val speed = SpeedtestManager.readDownloadSpeed(input, nanoTime = times::next, startedNanos = 0L)

        assertEquals(16_384L, speed)
    }

    @Test
    fun shouldRejectEmptyDownloadsAndInvalidLimits() {
        assertThrows(IOException::class.java) {
            SpeedtestManager.readDownloadSpeed(ByteArrayInputStream(byteArrayOf()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeedtestManager.readDownloadSpeed(ByteArrayInputStream(byteArrayOf(1)), byteLimit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) { SpeedtestManager.createTestClient(0) }
    }

    @Test
    fun shouldDownloadThroughTheSpecifiedProxyWithoutResolvingTheTargetDirectly() {
        val proxy = TestProxy(status = 200, body = ByteArray(2048))
        val client = SpeedtestManager.createTestClient(proxy.port)
        try {
            val speed = runBlocking { SpeedtestManager.measureDownloadSpeed(client, TEST_URL) }

            assertTrue(speed > 0L)
            assertTrue(proxy.requestLine.startsWith("GET $TEST_URL "))
            assertTrue(proxy.headers.any { it.equals("Accept-Encoding: identity", ignoreCase = true) })
        } finally {
            closeClient(client)
            proxy.close()
        }
    }

    @Test
    fun shouldRejectHttpErrorsAndNoContentAsDownloadResults() {
        for (status in listOf(503, 204, 200)) {
            assertDownloadFails(status)
        }
    }

    private fun assertDownloadFails(status: Int) {
        val proxy = TestProxy(status, byteArrayOf())
        val client = SpeedtestManager.createTestClient(proxy.port)
        try {
            assertThrows(IOException::class.java) { downloadBlocking(client) }
        } finally {
            closeClient(client)
            proxy.close()
        }
    }

    /** 验证两次空响应都被测量，结果采用有效的毫秒值。 */
    @Test
    fun shouldAcceptNoContentAsARealDelayResult() {
        val proxy = TestProxy(listOf(ProbeResponse(), ProbeResponse()))
        val client = SpeedtestManager.createTestClient(proxy.port)
        try {
            val delay = runBlocking { SpeedtestManager.measureDelay(client, TEST_URL) }

            assertTrue(delay >= 1L)
            assertEquals(2, proxy.requests.size)
        } finally {
            closeClient(client)
            proxy.close()
        }
    }

    /** 验证复用连接、读取完整响应及选择较小值的实际 HTTP 行为。 */
    @Test
    fun shouldReuseTheConnectionAndChooseTheSmallestCompleteResponseDelay() {
        // 响应头立即返回、响应体延后发送，验证计时覆盖完整响应和第二次连接复用。
        val responses = listOf(
            ProbeResponse(200, ByteArray(64 * 1024), bodyDelayMillis = 650L),
            ProbeResponse(200, ByteArray(64 * 1024), bodyDelayMillis = 60L),
        )
        val proxy = TestProxy(responses)
        val client = SpeedtestManager.createTestClient(proxy.port)
        try {
            val delay = measureDelayBlocking(client)

            assertEquals(2, proxy.requests.size)
            assertTrue(delay in 50L..400L, "应取较快的完整响应：delay=$delay")
            val gap = proxy.requestTimes[1] - proxy.responseTimes[0]
            assertTrue(gap >= TimeUnit.MILLISECONDS.toNanos(80L), "两次请求应有约 100 ms 间隔")
        } finally {
            closeClient(client)
            proxy.close()
        }
    }

    /** 验证第二次探测失败时不会发布第一次的成功值。 */
    @Test
    fun shouldFailTheMeasurementWhenTheSecondProbeFails() {
        // 保留错误响应校验；两次探测必须都完成后才能发布延迟。
        val proxy = TestProxy(listOf(ProbeResponse(), ProbeResponse(status = 503)))
        val client = SpeedtestManager.createTestClient(proxy.port)
        try {
            assertThrows(IOException::class.java) { measureDelayBlocking(client) }

            assertEquals(2, proxy.requests.size)
        } finally {
            closeClient(client)
            proxy.close()
        }
    }

    /** 验证节点超时作为普通失败返回，同时关闭网络连接。 */
    @Test
    fun shouldReportTheProbeDeadlineAsFailureAndCloseItsSocket() {
        // 节点不响应时，局部五秒超时应成为普通失败，不能取消整批测试。
        val proxy = TestProxy(200, byteArrayOf(), waitForCancellation = true)
        val client = SpeedtestManager.createTestClient(proxy.port)
        try {
            assertThrows(IOException::class.java) { measureDelayBlocking(client) }

            assertTrue(proxy.connectionClosed.await(2, TimeUnit.SECONDS))
        } finally {
            closeClient(client)
            proxy.close()
        }
    }

    /** 验证取消会打断采样间隔，阻止第二次请求。 */
    @Test
    fun shouldCancelBetweenSamplesWithoutSendingTheSecondRequest() {
        // 第一份响应结束后立即取消，等待间隔必须可以中断。
        val proxy = TestProxy(listOf(ProbeResponse(), ProbeResponse()))
        val client = SpeedtestManager.createTestClient(proxy.port)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val measurement = scope.async { SpeedtestManager.measureDelay(client, TEST_URL) }
            assertTrue(proxy.firstResponseSent.await(3, TimeUnit.SECONDS))

            runBlocking { measurement.cancelAndJoin() }

            assertTrue(measurement.isCancelled)
            assertEquals(1, proxy.requests.size)
        } finally {
            scope.cancel()
            closeClient(client)
            proxy.close()
        }
    }

    @Test
    fun shouldCloseTheNetworkConnectionImmediatelyWhenTheBatchIsCancelled() {
        val proxy = TestProxy(200, byteArrayOf(), waitForCancellation = true)
        val client = SpeedtestManager.createTestClient(proxy.port)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val download = scope.async { SpeedtestManager.measureDownloadSpeed(client, TEST_URL) }
            assertTrue(proxy.receivedRequest.await(3, TimeUnit.SECONDS))

            runBlocking { download.cancelAndJoin() }

            assertTrue(download.isCancelled)
            assertTrue(proxy.connectionClosed.await(1, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
            closeClient(client)
            proxy.close()
        }
    }

    private fun downloadBlocking(client: OkHttpClient): Long = runBlocking {
        SpeedtestManager.measureDownloadSpeed(client, TEST_URL)
    }

    private fun measureDelayBlocking(client: OkHttpClient): Long = runBlocking {
        SpeedtestManager.measureDelay(client, TEST_URL)
    }

    private fun closeClient(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private data class ProbeResponse(
        val status: Int = 204,
        val body: ByteArray = byteArrayOf(),
        val headerDelayMillis: Long = 5L,
        val bodyDelayMillis: Long = 0L,
    )

    // 代理只接受一条 TCP 连接；多份响应必须复用它，不能靠新建连接通过测试。
    private class TestProxy(
        private val responses: List<ProbeResponse>,
        private val waitForCancellation: Boolean = false,
    ) : AutoCloseable {
        constructor(status: Int, body: ByteArray, waitForCancellation: Boolean = false) :
            this(listOf(ProbeResponse(status, body)), waitForCancellation)

        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private var accepted: Socket? = null
        val port: Int = listener.localPort
        val receivedRequest = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val firstResponseSent = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val requestTimes = CopyOnWriteArrayList<Long>()
        val responseTimes = CopyOnWriteArrayList<Long>()
        var requestLine = ""
        val headers = CopyOnWriteArrayList<String>()
        private val task = executor.submit(::serve)

        private fun serve() {
            listener.soTimeout = 5000
            val socket = listener.accept()
            accepted = socket
            socket.soTimeout = 10_000
            socket.use { respond(it) }
        }

        private fun respond(socket: Socket) {
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()
            for (index in responses.indices) {
                requestLine = input.readLine() ?: return
                requests.add(requestLine)
                requestTimes.add(System.nanoTime())
                var header = input.readLine()
                while (!header.isNullOrEmpty()) {
                    headers.add(header)
                    header = input.readLine()
                }
                receivedRequest.countDown()
                if (waitForCancellation) {
                    if (input.read() == -1) connectionClosed.countDown()
                    return
                }

                // 分别控制响应头和响应体的时间，避免只测到响应头也能通过断言。
                val response = responses[index]
                val connection = if (index == responses.lastIndex) "close" else "keep-alive"
                Thread.sleep(response.headerDelayMillis)
                output.write("HTTP/1.1 ${response.status} Test\r\nContent-Length: ${response.body.size}\r\nConnection: $connection\r\n\r\n".toByteArray())
                output.flush()
                Thread.sleep(response.bodyDelayMillis)
                output.write(response.body)
                output.flush()
                responseTimes.add(System.nanoTime())
                firstResponseSent.countDown()
            }
        }

        override fun close() {
            listener.close()
            accepted?.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            if (task.isDone) task.get()
        }
    }

    companion object {
        private const val TEST_URL = "http://speedtest.invalid/download"
    }
}
