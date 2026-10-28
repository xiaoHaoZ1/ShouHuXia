package io.github.xiaohaoz1.jiarenxia.guardian.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环形事件日志：device-protected 文件 guard_events.log，
 * 每行 "MM-dd HH:mm:ss | tag | msg"，超过 300 行截尾保留最近。
 */
class EventLog(context: Context) {

    private val file: File = File(
        context.createDeviceProtectedStorageContext().filesDir,
        "guard_events.log",
    )

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun append(tag: String, msg: String) {
        try {
            file.appendText("${fmt.format(Date())} | $tag | $msg\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (t: Throwable) {
            // 日志失败不影响业务
        }
    }

    @Synchronized
    fun tail(n: Int): List<String> =
        try {
            if (file.exists()) file.readLines().filter { it.isNotBlank() }.takeLast(n)
            else emptyList()
        } catch (t: Throwable) {
            emptyList()
        }

    companion object {
        const val MAX_LINES = 300
    }
}
