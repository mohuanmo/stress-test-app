package com.example.stresstest

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 压力测试 ViewModel — MVVM 架构核心
 *
 * 负责管理UI状态、协调压力引擎与保护监控器之间的关系。
 * 使用 AndroidViewModel 以获取 Application 上下文。
 *
 * 生命周期感知：屏幕旋转时不丢失测试状态（Activity 重建但 ViewModel 存活）。
 */
class StressTestViewModel(application: Application) : AndroidViewModel(application) {

    // ==================== 核心组件 ====================

    /** 压力测试引擎 */
    private val repository = StressTestRepository()

    /** 设备保护监控器 */
    private val protectionMonitor = DeviceProtectionMonitor(application)

    // ==================== UI 状态流 ====================

    /** 测试状态 */
    val testState: StateFlow<StressTestRepository.TestState> = repository.testState

    /** 实时统计数据 */
    val stats: StateFlow<StressTestRepository.TestStats> = repository.stats

    /** 保护状态（设备健康信息） */
    private val _protectionState = MutableStateFlow(
        DeviceProtectionMonitor.ProtectionState()
    )
    val protectionState: StateFlow<DeviceProtectionMonitor.ProtectionState> =
        _protectionState.asStateFlow()

    /** UI 输入校验错误信息 */
    private val _inputError = MutableStateFlow<String?>(null)
    val inputError: StateFlow<String?> = _inputError.asStateFlow()

    /** CSV导出状态 */
    private val _exportState = MutableStateFlow<ExportState>(ExportState.IDLE)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /** 保护监控更新协程 */
    private var protectionUpdateJob: Job? = null

    // CSV导出状态
    enum class ExportState {
        IDLE,       // 空闲
        EXPORTING,  // 导出中
        SUCCESS,    // 导出成功
        FAILED      // 导出失败
    }

    init {
        // 绑定保护监控器到引擎
        repository.setProtectionMonitor(protectionMonitor)

        // 启动保护状态定时更新（每3秒刷新一次UI上的温度/内存信息）
        startProtectionUpdates()
    }

    // ==================== 公共方法 ====================

    /**
     * 开始压力测试
     *
     * @param urlStr 目标 URL
     * @param threadsStr 并发线程数（字符串，用于校验）
     * @param durationStr 测试持续时间（字符串，用于校验）
     * @return true=成功启动, false=输入校验失败
     */
    fun startTest(urlStr: String, threadsStr: String, durationStr: String): Boolean {
        // 输入校验
        val url = validateUrl(urlStr) ?: return false
        val threads = validateThreads(threadsStr) ?: return false
        val duration = validateDuration(durationStr) ?: return false

        // 检查设备状态 — 如果电量过低，拒绝启动
        val deviceState = protectionMonitor.getProtectionState()
        if (deviceState.protectionLevel == DeviceProtectionMonitor.ProtectionLevel.SHUTDOWN) {
            _inputError.value = "设备电量不足 ${deviceState.batteryLevel}%，请充电后再测试"
            return false
        }

        _inputError.value = null
        repository.startTest(url, threads, duration)
        return true
    }

    /**
     * 暂停测试
     */
    fun pauseTest() {
        repository.pauseTest()
    }

    /**
     * 继续测试
     */
    fun resumeTest() {
        repository.resumeTest()
    }

    /**
     * 停止测试
     */
    fun stopTest() {
        repository.stopTest()
    }

    /**
     * 导出 CSV 报告到 Download 目录
     *
     * Android 10+ 使用 MediaStore API
     * Android 9- 使用 WRITE_EXTERNAL_STORAGE 写入 Download
     */
    fun exportCsv() {
        _exportState.value = ExportState.EXPORTING

        viewModelScope.launch {
            try {
                val results = repository.getResults()
                if (results.isEmpty()) {
                    _exportState.value = ExportState.FAILED
                    return@launch
                }

                // 构建 CSV 内容
                val csvContent = buildCsvContent(results)
                val filename = "stress_test_report_${System.currentTimeMillis()}.csv"

                val context = getApplication<Application>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: 使用 MediaStore
                    saveToMediaStore(context, filename, csvContent)
                } else {
                    // Android 9-: 直接写入 Download 目录
                    saveToLegacyDownload(context, filename, csvContent)
                }

                _exportState.value = ExportState.SUCCESS
            } catch (e: Exception) {
                _exportState.value = ExportState.FAILED
            }
        }
    }

    /**
     * 清除输入错误
     */
    fun clearInputError() {
        _inputError.value = null
    }

    /**
     * 重置导出状态
     */
    fun resetExportState() {
        _exportState.value = ExportState.IDLE
    }

    override fun onCleared() {
        super.onCleared()
        protectionUpdateJob?.cancel()
        repository.stopTest()
    }

    // ==================== 私有方法 ====================

    /**
     * URL 校验
     */
    private fun validateUrl(urlStr: String): String? {
        val trimmed = urlStr.trim()
        if (trimmed.isBlank()) {
            _inputError.value = "请输入目标 URL"
            return null
        }
        // 补全协议
        val url = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }
        // 简单格式检查
        if (!url.matches(Regex("^https?://[\\w.-]+(:\\d+)?(/.*)?$"))) {
            _inputError.value = "URL 格式不正确，请输入类似 192.168.0.1 或 https://example.com"
            return null
        }
        return url
    }

    /**
     * 并发线程数校验
     */
    private fun validateThreads(threadsStr: String): Int? {
        val threads = threadsStr.trim().toIntOrNull()
        if (threads == null || threads < 1 || threads > 1000) {
            _inputError.value = "并发线程数需在 1-1000 之间"
            return null
        }
        return threads
    }

    /**
     * 持续时间校验
     */
    private fun validateDuration(durationStr: String): Int? {
        val duration = durationStr.trim().toIntOrNull()
        if (duration == null || duration < 1 || duration > 300) {
            _inputError.value = "持续时间需在 1-300 秒之间"
            return null
        }
        return duration
    }

    /**
     * 启动保护状态定时更新
     * 每3秒刷新保护状态，用于UI显示温度/内存等信息
     */
    private fun startProtectionUpdates() {
        protectionUpdateJob = viewModelScope.launch {
            while (true) {
                delay(3000)
                val currentActive = stats.value.totalRequests.toInt()
                _protectionState.value = protectionMonitor.getProtectionState(currentActive)
            }
        }
    }

    /**
     * 构建 CSV 内容
     */
    private fun buildCsvContent(results: List<StressTestResult>): ByteArray {
        val sb = StringBuilder()
        sb.appendLine(StressTestResult.CSV_HEADER)
        // 使用 BOM 以支持 Excel 直接打开 UTF-8 CSV
        val bom = "\uFEFF"
        val content = bom + results.joinToString("\n") { it.toCsvLine() }
        return content.toByteArray(Charsets.UTF_8)
    }

    /**
     * Android 10+ MediaStore 保存
     */
    private fun saveToMediaStore(context: Context, filename: String, csvData: ByteArray) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("无法创建 MediaStore 条目")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(csvData)
            outputStream.flush()
        } ?: throw Exception("无法打开输出流")
    }

    /**
     * Android 9- 文件系统保存
     */
    private fun saveToLegacyDownload(context: Context, filename: String, csvData: ByteArray) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, filename)
        FileOutputStream(file).use { fos ->
            fos.write(csvData)
            fos.flush()
        }
    }
}
