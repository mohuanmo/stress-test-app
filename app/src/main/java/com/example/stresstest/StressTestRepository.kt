package com.example.stresstest

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp5.ConnectionPool
import okhttp5.ConnectionSpec
import okhttp5.OkHttpClient
import okhttp5.Request
import okhttp5.Response
import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 压力测试仓库类 — 核心引擎
 *
 * 使用 OkHttp 5.0.0 实现高并发 HTTP 请求。
 * 采用 Kotlin Coroutines 管理并发任务，支持：
 * - 可配置的并发线程数（通过 Coroutine 协程数控制）
 * - 实时状态上报（通过 StateFlow）
 * - 暂停/继续/停止控制
 * - 响应时间统计（平均/最小/最大）
 *
 * OkHttp 5.0.0 说明：搜索确认于2025年7月发布，
 * Maven 坐标从 com.squareup.okhttp3 改为 com.squareup.okhttp5（独立Android包）
 */
class StressTestRepository {

    // ==================== 状态定义 ====================

    /**
     * 测试运行状态
     */
    enum class TestState {
        IDLE,       // 空闲
        RUNNING,    // 运行中
        PAUSED,     // 已暂停
        STOPPING,   // 正在停止
        COMPLETED   // 已完成
    }

    /**
     * 实时统计数据，通过 StateFlow 推送至 UI
     */
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

    /** OkHttp 客户端实例（连接池复用） */
    private var httpClient: OkHttpClient = createDefaultClient()

    /** 连接超时时间（毫秒） */
    private val connectTimeoutMs = 10_000L

    /** 读取超时时间（毫秒） */
    private val readTimeoutMs = 30_000L

    /** 请求尝试次数 */
    private val maxRetries = 2

    /** 设备保护监控器（由 ViewModel 传入） */
    private var protectionMonitor: DeviceProtectionMonitor? = null

    /** 用户设定的目标并发数（不会超过此值） */
    private var userTargetThreads: Int = 10

    /** 保护监控协程 Job */
    private var protectionJob: Job? = null

    // ==================== 运行时状态 ====================

    /** 协程作用域 — 用于管理所有测试协程的生命周期 */
    private var testScope: CoroutineScope? = null

    /** 暂停/继续控制 */
    private val pauseLock = Any()

    /** 当前测试状态 */
    private val _testState = MutableStateFlow(TestState.IDLE)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    /** 实时统计数据流 */
    private val _stats = MutableStateFlow(TestStats())
    val stats: StateFlow<TestStats> = _stats.asStateFlow()

    /** 所有请求结果列表（用于导出CSV） */
    private val _results = mutableListOf<StressTestResult>()

    /** 请求计数器 */
    private val requestCounter = AtomicLong(0)

    /** 是否正在运行 */
    private val isRunning = AtomicBoolean(false)

    /** 是否暂停 */
    private val isPaused = AtomicBoolean(false)

    /** 开始时间戳 */
    private var startTimeMs = 0L

    /** QPS 计算 */
    private val qpsCounter = AtomicInteger(0)

    // ==================== 公共接口 ====================

    /**
     * 绑定设备保护监控器
     * 必须在 startTest 之前调用
     */
    fun setProtectionMonitor(monitor: DeviceProtectionMonitor) {
        this.protectionMonitor = monitor
    }

    /**
     * 开始压力测试
     *
     * @param targetUrl 目标 URL
     * @param concurrentCount 并发线程数（1-1000）
     * @param durationSeconds 测试持续时间（秒）
     */
    fun startTest(
        targetUrl: String,
        concurrentCount: Int,
        durationSeconds: Int
    ) {
        userTargetThreads = concurrentCount
        // 如果已经在运行，先停止
        if (isRunning.get()) {
            stopTest()
        }

        // 重置状态
        resetState()

        // 初始化统计
        _testState.value = TestState.RUNNING
        isRunning.set(true)
        isPaused.set(false)
        startTimeMs = System.currentTimeMillis()

        // 创建 OkHttp 客户端（带连接池优化）
        httpClient = createOptimizedClient(concurrentCount)

        // 创建协程作用域
        testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // 启动 QPS 统计协程
        testScope?.launch {
            while (isActive && isRunning.get()) {
                delay(1000) // 每秒计算 QPS
                val currentQps = qpsCounter.getAndSet(0).toDouble()
                _stats.value = _stats.value.copy(qps = currentQps)
            }
        }

        // 启动耗时计时器
        testScope?.launch {
            while (isActive && isRunning.get()) {
                delay(1000)
                val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
                _stats.value = _stats.value.copy(elapsedSeconds = elapsed)

                // 检查是否达到持续时间
                if (elapsed >= durationSeconds && isRunning.get()) {
                    stopTest()
                }
            }
        }

        // 启动保护监控协程 — 每5秒检查一次设备健康状态
        testScope?.launch {
            while (isActive && isRunning.get()) {
                delay(5000) // 每5秒检查一次
                val monitor = protectionMonitor ?: continue
                val currentActive = _stats.value.totalRequests.toInt()
                val state = monitor.getProtectionState(currentActive)

                // 根据保护级别自动调整
                when (state.protectionLevel) {
                    DeviceProtectionMonitor.ProtectionLevel.SHUTDOWN -> {
                        // 紧急情况 — 停止测试
                        stopTest()
                        break
                    }
                    DeviceProtectionMonitor.ProtectionLevel.CRITICAL -> {
                        // 危险 — 暂停测试
                        if (!isPaused.get()) {
                            pauseTest()
                        }
                    }
                    DeviceProtectionMonitor.ProtectionLevel.WARNING -> {
                        // 警告 — 如果暂停了，恢复但降速
                        if (isPaused.get()) {
                            resumeTest()
                        }
                    }
                    DeviceProtectionMonitor.ProtectionLevel.NORMAL -> {
                        // 正常 — 如果暂停了，恢复
                        if (isPaused.get()) {
                            resumeTest()
                        }
                    }
                }
            }
        }

        // 启动并发请求任务
        repeat(concurrentCount) { threadIndex ->
            testScope?.launch {
                while (isActive && isRunning.get()) {
                    // 暂停检测
                    if (isPaused.get()) {
                        synchronized(pauseLock) {
                            if (isPaused.get()) {
                                pauseLock.wait()
                            }
                        }
                    }

                    // 发送 HTTP 请求
                    val result = executeRequest(threadIndex, targetUrl)
                    if (result != null) {
                        updateStatsAfterRequest(result)
                    }

                    // 短暂延迟，避免 CPU 满载（约 0-5ms 随机延迟）
                    delay((0..5).random().toLong())
                }
            }
        }
    }

    /**
     * 暂停测试
     */
    fun pauseTest() {
        if (isRunning.get() && !isPaused.get()) {
            isPaused.set(true)
            _testState.value = TestState.PAUSED
            _stats.value = _stats.value.copy(testState = TestState.PAUSED)
        }
    }

    /**
     * 继续测试
     */
    fun resumeTest() {
        if (isRunning.get() && isPaused.get()) {
            isPaused.set(false)
            _testState.value = TestState.RUNNING
            _stats.value = _stats.value.copy(testState = TestState.RUNNING)
            // 唤醒所有等待的协程
            synchronized(pauseLock) {
                pauseLock.notifyAll()
            }
        }
    }

    /**
     * 停止测试
     */
    fun stopTest() {
        if (!isRunning.get()) return

        _testState.value = TestState.STOPPING
        isRunning.set(false)
        isPaused.set(false)

        // 取消协程作用域
        testScope?.cancel()
        testScope = null

        // 唤醒暂停中的协程
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }

        _testState.value = TestState.COMPLETED
        _stats.value = _stats.value.copy(testState = TestState.COMPLETED)
    }

    /**
     * 获取结果列表（用于导出CSV）
     */
    fun getResults(): List<StressTestResult> = synchronized(_results) {
        _results.toList()
    }

    /**
     * 清除结果
     */
    fun clearResults() {
        synchronized(_results) {
            _results.clear()
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 创建默认 OkHttp 客户端
     */
    private fun createDefaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * 创建优化后的 OkHttp 客户端
     * 根据并发数调整连接池大小
     */
    private fun createOptimizedClient(concurrentCount: Int): OkHttpClient {
        // 连接池大小 = 并发数 + 10 个缓冲
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

    /**
     * 执行单个 HTTP 请求
     *
     * @param threadIndex 线程编号（仅用于标识）
     * @param url 请求 URL
     * @return StressTestResult? 请求结果
     */
    private fun executeRequest(threadIndex: Int, url: String): StressTestResult? {
        val requestId = requestCounter.incrementAndGet()
        val requestStartTime = System.nanoTime()

        // 构造 HTTP 请求
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "StressTestApp/1.0")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        var lastError: String? = null
        var lastStatusCode = -1

        // 重试逻辑
        for (attempt in 0..maxRetries) {
            try {
                val response: Response = httpClient.newCall(request).execute()
                val statusCode = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                lastStatusCode = statusCode

                // 计算响应时间（纳秒转毫秒）
                val responseTimeNs = System.nanoTime() - requestStartTime
                val responseTimeMs = responseTimeNs / 1_000_000

                // 构建结果
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
                // 网络错误，重试
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                break // 非 IO 错误不重试
            }
        }

        // 所有重试均失败
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

    /**
     * 请求完成后更新统计数据
     */
    private fun updateStatsAfterRequest(result: StressTestResult) {
        qpsCounter.incrementAndGet()

        synchronized(this) {
            val current = _stats.value
            val newTotal = current.totalRequests + 1
            val newSuccess = current.successCount + if (result.success) 1 else 0
            val newFailed = current.failedCount + if (!result.success) 1 else 0

            // 更新响应时间统计
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

        // 保存结果到列表
        synchronized(_results) {
            _results.add(result)
        }
    }

    /**
     * 重置所有状态
     */
    private fun resetState() {
        requestCounter.set(0)
        qpsCounter.set(0)
        isRunning.set(false)
        isPaused.set(false)
        startTimeMs = 0L

        _stats.value = TestStats()
        synchronized(_results) {
            _results.clear()
        }
    }
}
