package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object SpeedtestManager {

    // 移动端限制并发和流量；测速源与 Windows 端提供的 Cloudflare 下载源一致。
    const val DOWNLOAD_CONCURRENCY = 3
    const val DOWNLOAD_TEST_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
    internal const val DOWNLOAD_BYTE_LIMIT = 10_000_000L
    internal const val DOWNLOAD_DURATION_NANOS = 5_000_000_000L
    private const val DELAY_TEST_TIMEOUT_MILLIS = 5_000L
    private const val DELAY_SAMPLE_COUNT = 2
    private const val DELAY_SAMPLE_INTERVAL_MILLIS = 100L
    private const val SPEED_SAMPLE_INTERVAL_NANOS = 1_000_000_000L

    /** 为待测节点创建专用 HTTP 代理客户端，连接失败时不会回退到直连。 */
    fun createTestClient(httpPort: Int): OkHttpClient {
        require(httpPort in 1..65535) { "Invalid test proxy port" }
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, httpPort)))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(DELAY_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 与 Windows GetRealPingTime 一致，用同一连接池连续测量两次，返回最小正数延迟。
     * @param client 绑定待测节点的代理客户端，两次请求共享它的连接池。
     * @param url 用户配置的延迟测试地址。
     * @return 读完响应体所需的最小毫秒数；请求失败或总计超时则抛出异常。
     */
    suspend fun measureDelay(client: OkHttpClient, url: String): Long {
        // 五秒预算覆盖两次请求与间隔；局部超时是节点失败，用户取消仍向上传播。
        val minimumDelay = withTimeoutOrNull(DELAY_TEST_TIMEOUT_MILLIS) {
            var bestDelay = Long.MAX_VALUE
            for (attempt in 0 until DELAY_SAMPLE_COUNT) {
                val sampleDelay = executeProbe(client, url, download = false)
                if (sampleDelay > 0L && sampleDelay < bestDelay) {
                    bestDelay = sampleDelay
                }
                delay(DELAY_SAMPLE_INTERVAL_MILLIS)
            }
            if (bestDelay == Long.MAX_VALUE) throw IOException("Delay test returned no positive sample")
            bestDelay
        }
        return minimumDelay ?: throw IOException("Delay test exceeded ${DELAY_TEST_TIMEOUT_MILLIS} ms")
    }

    /** 经指定节点流式下载，按约一秒的采样窗口返回峰值字节/秒。 */
    suspend fun measureDownloadSpeed(client: OkHttpClient, url: String = DOWNLOAD_TEST_URL): Long {
        return executeProbe(client, url, download = true)
    }

    private suspend fun executeProbe(client: OkHttpClient, url: String, download: Boolean): Long {
        // 禁用缓存和压缩，防止把缓存命中或解压后的大小当作网络吞吐量。
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache, no-store")
            .header("Accept-Encoding", "identity")
            .build()
        val call = client.newCall(request)
        val startNanos = System.nanoTime()
        // 这里使用可取消协程桥接 OkHttp 回调，是为了取消测试时立即关闭下载连接。
        return suspendCancellableCoroutine { continuation ->
            enqueueProbe(call, download, startNanos, continuation)
        }
    }

    private fun enqueueProbe(
        call: Call,
        download: Boolean,
        startNanos: Long,
        continuation: CancellableContinuation<Long>,
    ) {
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // 响应体仅在 OkHttp 的后台线程读取，use 确保成功、失败和取消均关闭响应。
                val result = try {
                    response.use { consumeProbeResponse(it, download, startNanos) }
                } catch (error: Exception) {
                    continuation.resumeWith(Result.failure(error))
                    return
                }
                continuation.resumeWith(Result.success(result))
            }
        })
    }

    private fun consumeProbeResponse(response: Response, download: Boolean, startNanos: Long): Long {
        // 错误页和空下载均不是有效速度，不能覆盖为一次成功的测速。
        if (response.code != 200 && (download || response.code != 204)) {
            throw IOException("Unexpected test HTTP status: ${response.code}")
        }
        if (!download) {
            // Windows HttpClient.GetAsync 默认读完整个响应；读完也能让第二次请求复用连接。
            val input = response.body.byteStream()
            val buffer = ByteArray(32 * 1024)
            while (input.read(buffer) >= 0) { }
            val elapsedNanos = System.nanoTime() - startNanos
            return TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
        }
        val body = response.body
        return readDownloadSpeed(body.byteStream(), startedNanos = startNanos)
    }

    internal fun readDownloadSpeed(
        input: InputStream,
        byteLimit: Long = DOWNLOAD_BYTE_LIMIT,
        durationNanos: Long = DOWNLOAD_DURATION_NANOS,
        nanoTime: () -> Long = System::nanoTime,
        startedNanos: Long = nanoTime(),
    ): Long {
        require(byteLimit > 0 && durationNanos > 0) { "Invalid download test limits" }
        // 固定缓冲区读取并丢弃内容，不写磁盘，也不把整个测试文件放入内存。
        val buffer = ByteArray(32 * 1024)
        var receivedBytes = 0L
        var elapsedNanos = 0L
        var sampleStartedNanos = startedNanos
        var sampleBytes = 0L
        var peakBytesPerSecond = 0L
        while (receivedBytes < byteLimit && elapsedNanos < durationNanos) {
            val nextReadSize = minOf(buffer.size.toLong(), byteLimit - receivedBytes).toInt()
            val readCount = input.read(buffer, 0, nextReadSize)
            if (readCount < 0) break
            receivedBytes += readCount
            sampleBytes += readCount
            val now = nanoTime()
            elapsedNanos = now - startedNanos

            // 对齐 Windows Downloader 的一秒分段速率，再取最大值；首段包含请求建立时间。
            val sampleElapsedNanos = now - sampleStartedNanos
            if (sampleElapsedNanos >= SPEED_SAMPLE_INTERVAL_NANOS) {
                val sampleSpeed = (sampleBytes * 1_000_000_000.0 / sampleElapsedNanos).toLong()
                peakBytesPerSecond = maxOf(peakBytesPerSecond, sampleSpeed)
                sampleStartedNanos = now
                sampleBytes = 0L
            }
        }
        if (receivedBytes == 0L) throw IOException("Download test received no data")
        if (peakBytesPerSecond > 0L) return peakBytesPerSecond

        // 移动端文件可能不足一秒就达到流量上限；此时用实际窗口，避免成功下载仍显示未测。
        elapsedNanos = (nanoTime() - startedNanos).coerceAtLeast(1L)
        return (receivedBytes * 1_000_000_000.0 / elapsedNanos).toLong().coerceAtLeast(1L)
    }

    /** 按速度降序稳定排序；未测或失败的节点保留相互顺序，排在最后。 */
    fun sortBySpeed(guids: List<String>, speeds: Map<String, Long>): List<String> {
        return guids.sortedByDescending { (speeds[it] ?: 0L).coerceAtLeast(0L) }
    }

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(
            country = country,
            ipAddress = ip,
        )
    }
}
