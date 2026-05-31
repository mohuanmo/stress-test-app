package com.example.stresstest

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.stresstest.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 主Activity — 压力检测工具主界面
 *
 * MVVM 架构：
 * - View: MainActivity（绑定 UI 事件 + 观察 ViewModel 数据变化）
 * - ViewModel: StressTestViewModel（管理状态）
 * - Repository: StressTestRepository（核心引擎）
 *
 * configChanges 声明确保屏幕旋转时 Activity 重建但 ViewModel 存活。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: StressTestViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewBinding 初始化
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ViewModel 初始化（ViewModelProvider 保证屏幕旋转不丢失状态）
        viewModel = ViewModelProvider(this)[StressTestViewModel::class.java]

        // 初始化 UI 控件和事件监听
        setupViews()

        // 观察 ViewModel 数据变化
        setupObservers()
    }

    /**
     * 设置UI控件的事件监听
     */
    private fun setupViews() {
        // === 并发线程数滑块 ===
        binding.sliderThreads.addOnChangeListener { _, value, _ ->
            val intValue = value.toInt()
            binding.etConcurrentThreads.setText(intValue.toString())
            binding.tvThreadsValue.text = intValue.toString()
        }

        // === 并发线程数输入框同步 ===
        binding.etConcurrentThreads.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.etConcurrentThreads.text?.toString()
                    ?.toIntOrNull()
                    ?.coerceIn(1, 1000) ?: 10
                binding.sliderThreads.value = value.toFloat()
                binding.tvThreadsValue.text = value.toString()
                binding.etConcurrentThreads.setText(value.toString())
            }
        }

        // === 持续时间滑块 ===
        binding.sliderDuration.addOnChangeListener { _, value, _ ->
            val intValue = value.toInt()
            binding.etDuration.setText(intValue.toString())
            binding.tvDurationValue.text = "${intValue}s"
        }

        // === 持续时间输入框同步 ===
        binding.etDuration.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.etDuration.text?.toString()
                    ?.toIntOrNull()
                    ?.coerceIn(5, 300) ?: 30
                binding.sliderDuration.value = value.toFloat()
                binding.tvDurationValue.text = "${value}s"
                binding.etDuration.setText(value.toString())
            }
        }

        // === 开始按钮 ===
        binding.btnStart.setOnClickListener {
            val url = binding.etTargetUrl.text.toString()
            val threads = binding.etConcurrentThreads.text.toString()
            val duration = binding.etDuration.text.toString()

            val started = viewModel.startTest(url, threads, duration)
            if (started) {
                // 切换按钮状态
                binding.btnStart.visibility = View.GONE
                binding.btnPauseResume.visibility = View.VISIBLE
                binding.btnStop.visibility = View.VISIBLE
                binding.btnPauseResume.text = getString(R.string.btn_pause)
            }
        }

        // === 暂停/继续按钮 ===
        binding.btnPauseResume.setOnClickListener {
            val currentState = viewModel.testState.value
            if (currentState == StressTestRepository.TestState.RUNNING) {
                viewModel.pauseTest()
                binding.btnPauseResume.text = getString(R.string.btn_resume)
            } else if (currentState == StressTestRepository.TestState.PAUSED) {
                viewModel.resumeTest()
                binding.btnPauseResume.text = getString(R.string.btn_pause)
            }
        }

        // === 停止按钮 ===
        binding.btnStop.setOnClickListener {
            viewModel.stopTest()
        }

        // === 导出CSV按钮 ===
        binding.btnExportCsv.setOnClickListener {
            viewModel.exportCsv()
        }

        // === 初始默认值 ===
        binding.sliderThreads.value = 10f
        binding.sliderDuration.value = 30f
        binding.etConcurrentThreads.setText("10")
        binding.etDuration.setText("30")
        binding.tvThreadsValue.text = "10"
        binding.tvDurationValue.text = "30s"
    }

    /**
     * 观察 ViewModel 中的 StateFlow 变化
     * 实时更新 UI 统计信息
     */
    private fun setupObservers() {
        // === 实时统计数据 ===
        lifecycleScope.launch {
            viewModel.stats.collectLatest { stats ->
                binding.tvTotalRequests.text = formatNumber(stats.totalRequests)
                binding.tvSuccess.text = formatNumber(stats.successCount)
                binding.tvFailed.text = formatNumber(stats.failedCount)
                binding.tvQps.text = "%.1f".format(stats.qps)
                binding.tvElapsed.text = formatDuration(stats.elapsedSeconds)

                // 响应时间
                binding.tvAvgTime.text = if (stats.avgResponseTime > 0) {
                    "%.1f".format(stats.avgResponseTime)
                } else {
                    "-"
                }
                binding.tvMinTime.text = if (stats.minResponseTime < Long.MAX_VALUE) {
                    "${stats.minResponseTime}"
                } else {
                    "-"
                }
                binding.tvMaxTime.text = if (stats.maxResponseTime > 0) {
                    "${stats.maxResponseTime}"
                } else {
                    "-"
                }
            }
        }

        // === 测试状态变化 ===
        lifecycleScope.launch {
            viewModel.testState.collectLatest { state ->
                when (state) {
                    StressTestRepository.TestState.IDLE -> {
                        binding.tvStatus.text = getString(R.string.status_idle)
                        binding.tvStatus.setTextColor(getColor(R.color.status_success))
                    }
                    StressTestRepository.TestState.RUNNING -> {
                        binding.tvStatus.text = getString(R.string.status_running)
                        binding.tvStatus.setTextColor(getColor(R.color.status_running))
                    }
                    StressTestRepository.TestState.PAUSED -> {
                        binding.tvStatus.text = getString(R.string.status_paused)
                        binding.tvStatus.setTextColor(getColor(R.color.status_paused))
                    }
                    StressTestRepository.TestState.STOPPING -> {
                        binding.tvStatus.text = "正在停止…"
                    }
                    StressTestRepository.TestState.COMPLETED -> {
                        binding.tvStatus.text = getString(R.string.status_completed)
                        binding.tvStatus.setTextColor(getColor(R.color.status_success))

                        // 恢复按钮状态
                        binding.btnStart.visibility = View.VISIBLE
                        binding.btnPauseResume.visibility = View.GONE
                        binding.btnStop.visibility = View.GONE

                        // 显示报告卡片
                        binding.cardReport.visibility = View.VISIBLE
                        updateReportSummary()
                    }
                }
            }
        }

        // === 输入错误提示 ===
        lifecycleScope.launch {
            viewModel.inputError.collectLatest { error ->
                if (error != null) {
                    Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                    viewModel.clearInputError()
                }
            }
        }

        // === CSV 导出状态 ===
        lifecycleScope.launch {
            viewModel.exportState.collectLatest { state ->
                when (state) {
                    StressTestViewModel.ExportState.SUCCESS -> {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.export_success),
                            Snackbar.LENGTH_LONG
                        ).show()
                        viewModel.resetExportState()
                    }
                    StressTestViewModel.ExportState.FAILED -> {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.export_failed, "请检查存储权限"),
                            Snackbar.LENGTH_LONG
                        ).show()
                        viewModel.resetExportState()
                    }
                    else -> { /* IDLE / EXPORTING */ }
                }
            }
        }

    }

    /**
     * 更新测试报告摘要
     */
    private fun updateReportSummary() {
        val reportLayout = binding.layoutReportSummary
        reportLayout.removeAllViews()

        val stats = viewModel.stats.value
        val successRate = if (stats.totalRequests > 0) {
            "%.1f".format(stats.successCount.toDouble() / stats.totalRequests * 100)
        } else {
            "0.0"
        }

        val items = listOf(
            "总请求数" to formatNumber(stats.totalRequests),
            "成功" to formatNumber(stats.successCount),
            "失败" to formatNumber(stats.failedCount),
            "成功率" to "$successRate%",
            "平均 QPS" to "%.1f".format(stats.qps),
            "平均响应时间" to "%.1f ms".format(stats.avgResponseTime),
            "最大响应时间" to "${stats.maxResponseTime} ms",
            "最小响应时间" to "${if (stats.minResponseTime < Long.MAX_VALUE) stats.minResponseTime else 0} ms",
            "测试时长" to formatDuration(stats.elapsedSeconds)
        )

        for ((label, value) in items) {
            val row = layoutInflater.inflate(
                com.google.android.material.R.layout.support_simple_spinner_dropdown_item,
                reportLayout,
                false
            )
            // 由于无法直接访问系统布局的 TextView，使用简单方式
            val textView = android.widget.TextView(this)
            textView.text = "$label: $value"
            textView.setPadding(0, 4, 0, 4)
            textView.textSize = 14f
            reportLayout.addView(textView)
        }
    }

    /**
     * 格式化大数（如 1234567 -> "1,234,567"）
     */
    private fun formatNumber(number: Long): String {
        return String.format("%,d", number)
    }

    /**
     * 格式化时长（如 125 -> "02:05"）
     */
    private fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }
}
