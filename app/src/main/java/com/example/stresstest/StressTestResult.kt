package com.example.stresstest

/**
 * 压力测试结果数据类
 * 记录每次HTTP请求的完整结果信息
 *
 * @param requestId 请求编号（从1递增）
 * @param url 请求的目标URL
 * @param statusCode HTTP状态码（-1表示网络错误）
 * @param responseTimeMs 响应时间（毫秒）
 * @param success 是否成功（statusCode >= 200 && < 500 视为成功）
 * @param errorMessage 错误信息（成功时为null）
 * @param timestamp 请求完成的时间戳
 */
data class StressTestResult(
    val requestId: Long,
    val url: String,
    val statusCode: Int,
    val responseTimeMs: Long,
    val success: Boolean,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 将结果格式化为CSV行
     * 用于导出到CSV文件
     */
    fun toCsvLine(): String {
        return "$requestId,$url,$statusCode,$responseTimeMs,$success,${errorMessage ?: ""},$timestamp"
    }

    companion object {
        /**
         * CSV文件头
         */
        val CSV_HEADER = "请求编号,URL,状态码,响应时间(ms),成功,错误信息,时间戳"
    }
}
