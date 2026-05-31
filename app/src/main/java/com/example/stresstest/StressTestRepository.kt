package com.example.stresstest

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 压力测试仓库类 — 核心引擎
 *
 * 使用 OkHttp 4.x + Kotlin Coroutines 实现高并发 HTTP 请求。
 * 采用纯协程方式管理暂停/继续，不使用 Java 并发原语。
 */
class StressTestRepository {

    // ==================== 状态定义 ====================

    enum class TestState {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPING,
        COMPLETED
    }

    data class TestStats(
        val totalRequests: Long = 0,
        val successCount: Long = 0,
        val failedCount: Long = 0,
        val qps: Double = 0.0,
        val avgResponseTime: Double = 0.0,
        val minResponseTime: Long = Long.MAX_VALUE,
        val maxResponseTime: Long = 0,
        val elapsedSeconds: Long = 0,
        val testState: TestState = TestState.IDLE
    )

    // ==================== 配置 ====================

    private var httpClient: OkHttpClient = createDefaultClient()
    private val connectTimeoutMs = 10_000L
    private val readTimeoutMs = 30_000L
    private val maxRetries = 2

    // ==================== 运行时状态 ====================

    private var testScope: CoroutineScope? = null

    /** 暂停状态：通过协程轮询控制，不使用 wait/notify */
    private val isPaused = AtomicBoolean(false)

    private val _testState = MutableStateFlow(TestState.IDLE)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _stats = MutableStateFlow(TestStats())
    val stats: StateFlow<TestStats> = _stats.asStateFlow()

    private val _results = mutableListOf<StressTestResult>()
    private val requestCounter = AtomicLong(0)
    private val isRunning = AtomicBoolean(false)
    private var startTimeMs = 0L
    private val qpsCounter = AtomicInteger(0)

    // ==================== 公共接口 ====================

    fun startTest(
        targetUrl: String,
        concurrentCount: Int,
        durationSeconds: Int
    ) {
        if (isRunning.get()) {
            stopTest()
        }

        resetState()

        _testState.value = TestState.RUNNING
        isRunning.set(true)
        isPaused.set(false)
        startTimeMs = System.currentTimeMillis()

        httpClient = createOptimizedClient(concurrentCount)
        testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // QPS 统计协程
        testScope?.launch {
            while (isActive && isRunning.get()) {
                delay(1000)
                val currentQps = qpsCounter.getAndSet(0).toDouble()
                _stats.value = _stats.value.copy(qps = currentQps)
            }
        }

        // 耗时计时器
        testScope?.launch {
            while (isActive && isRunning.get()) {
                delay(1000)
                val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
                _stats.value = _stats.value.copy(elapsedSeconds = elapsed)
                if (elapsed >= durationSeconds && isRunning.get()) {
                    stopTest()
                }
            }
        }

        // 并发请求任务
        repeat(concurrentCount) { threadIndex: Int ->
            testScope?.launch {
                while (isActive && isRunning.get()) {
                    // 暂停检查：轮询直到恢复，不使用 Java wait
                    while (isPaused.get() && isRunning.get()) {
                        delay(50)
                        if (!isActive) return@launch
                    }
                    if (!isRunning.get()) break

                    val result = executeRequest(threadIndex, targetUrl)
                    if (result != null) {
                        updateStatsAfterRequest(result)
                    }

                    delay((0..5).random().toLong())
                }
            }
        }
    }

    fun pauseTest() {
        if (isRunning.get() && !isPaused.get()) {
            isPaused.set(true)
            _testState.value = TestState.PAUSED
            _stats.value = _stats.value.copy(testState = TestState.PAUSED)
        }
    }

    fun resumeTest() {
        if (isRunning.get() && isPaused.get()) {
            isPaused.set(false)
            _testState.value = TestState.RUNNING
            _stats.value = _stats.value.copy(testState = TestState.RUNNING)
        }
    }

    fun stopTest() {
        if (!isRunning.get()) return

        _testState.value = TestState.STOPPING
        isRunning.set(false)
        isPaused.set(false)

        testScope?.cancel()
        testScope = null

        _testState.value = TestState.COMPLETED
        _stats.value = _stats.value.copy(testState = TestState.COMPLETED)
    }

    fun getResults(): List<StressTestResult> = synchronized(_results) {
        _results.toList()
    }

    fun clearResults() {
        synchronized(_results) {
            _results.clear()
        }
    }

    // ==================== 内部方法 ====================

    private fun createDefaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private fun createOptimizedClient(concurrentCount: Int): OkHttpClient {
        val poolSize = maxOf(concurrentCount + 10, 20)
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(poolSize, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionSpecs(listOf(
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.CLEARTEXT
            ))
            .build()
    }

    private fun executeRequest(threadIndex: Int, url: String): StressTestResult? {
        val requestId = requestCounter.incrementAndGet()
        val requestStartTime = System.nanoTime()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "StressTestApp/1.0")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        var lastError: String? = null
        var lastStatusCode = -1

        for (attempt in 0..maxRetries) {
            try {
                val response: Response = httpClient.newCall(request).execute()
                val statusCode = response.code
                response.close()

                lastStatusCode = statusCode

                val responseTimeNs = System.nanoTime() - requestStartTime
                val responseTimeMs = responseTimeNs / 1_000_000

                return StressTestResult(
                    requestId = requestId,
                    url = url,
                    statusCode = statusCode,
                    responseTimeMs = responseTimeMs,
                    success = statusCode in 200..499 || statusCode in 500..599,
                    errorMessage = null,
                    timestamp = System.currentTimeMillis()
                )
            } catch (e: IOException) {
                lastError = e.message ?: "Unknown IO error"
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                break
            }
        }

        val responseTimeNs = System.nanoTime() - requestStartTime
        val responseTimeMs = responseTimeNs / 1_000_000

        return StressTestResult(
            requestId = requestId,
            url = url,
            statusCode = lastStatusCode,
            responseTimeMs = responseTimeMs,
            success = false,
            errorMessage = lastError,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun updateStatsAfterRequest(result: StressTestResult) {
        qpsCounter.incrementAndGet()

        synchronized(this) {
            val current = _stats.value
            val newTotal = current.totalRequests + 1
            val newSuccess = current.successCount + if (result.success) 1 else 0
            val newFailed = current.failedCount + if (!result.success) 1 else 0

            val newAvg = if (current.totalRequests > 0) {
                (current.avgResponseTime * current.totalRequests + result.responseTimeMs) / newTotal
            } else {
                result.responseTimeMs.toDouble()
            }
            val newMin = minOf(current.minResponseTime, result.responseTimeMs)
            val newMax = maxOf(current.maxResponseTime, result.responseTimeMs)

            _stats.value = current.copy(
                totalRequests = newTotal,
                successCount = newSuccess,
                failedCount = newFailed,
                avgResponseTime = newAvg,
                minResponseTime = newMin,
                maxResponseTime = newMax
            )
        }

        synchronized(_results) {
            _results.add(result)
        }
    }

    private fun resetState() {
        _testState.value = TestState.IDLE
        _stats.value = TestStats()
        synchronized(_results) {
            _results.clear()
        }
        requestCounter.set(0)
        qpsCounter.set(0)
    }
}
