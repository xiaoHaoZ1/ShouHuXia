package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.xiaohaoz1.jiarenxia.guardian.core.CallLogResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.maskNumber

/**
 * 通话记录判定：报警/演练拨号结束后，查"自己拨出的那通"是否接通。
 * 只读拨出记录、只在内存中比对、不落盘任何通话内容；日志只写结果与打码号码。
 * Direct Boot 未解锁 / 无权限 / 任何异常 → UNAVAILABLE（引擎按未接通继续，不漏报）。
 */
class CallLogReader(private val context: Context) {

    fun queryOutgoingCall(number: String, since: Long): CallLogResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CALL_LOG 未授权")
            return CallLogResult.UNAVAILABLE
        }
        val rows = mutableListOf<CallLogMatch.Row>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                ),
                CallLog.Calls.TYPE + " = ? AND " + CallLog.Calls.DATE + " >= ?",
                arrayOf(
                    CallLog.Calls.OUTGOING_TYPE.toString(),
                    since.toString(),
                ),
                CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                val iNumber = c.getColumnIndex(CallLog.Calls.NUMBER)
                val iDuration = c.getColumnIndex(CallLog.Calls.DURATION)
                val iDate = c.getColumnIndex(CallLog.Calls.DATE)
                var count = 0
                // 最多看前 20 行（刚拨出的几通足够覆盖）
                while (count < 20 && c.moveToNext()) {
                    count++
                    rows += CallLogMatch.Row(
                        number = c.getString(iNumber) ?: "",
                        durationSec = c.getLong(iDuration),
                        date = c.getLong(iDate),
                    )
                }
            } ?: return CallLogResult.UNAVAILABLE
        } catch (t: Throwable) {
            Log.e(TAG, "查通话记录失败 " + maskNumber(number), t)
            return CallLogResult.UNAVAILABLE
        }
        val result = CallLogMatch.pick(rows, number) { a, b -> sameNumber(a, b) }
        Log.d(TAG, "通话记录查询 " + maskNumber(number) + " result=$result rows=${rows.size}")
        return result
    }

    /** 号码匹配：系统比较 + 数字尾部兜底（应对 +86 / 空格 / 横杠）。 */
    private fun sameNumber(a: String, b: String): Boolean =
        CallLogMatch.sameNumber(context, a, b)

    companion object {
        const val TAG = "GuardCallLog"
    }
}

/** 号码比对与结果挑选：纯 Kotlin 部分可 JVM 单测。 */
object CallLogMatch {

    /** 一行拨出记录（只取判定需要的三个字段）。 */
    data class Row(val number: String, val durationSec: Long, val date: Long)

    /**
     * rows 已按时间倒序：取第一条号码匹配的——
     * DURATION>0 → ANSWERED，否则 NOT_ANSWERED；无匹配 → NOT_FOUND。
     */
    fun pick(
        rows: List<Row>,
        target: String,
        same: (String, String) -> Boolean,
    ): CallLogResult {
        val hit = rows.firstOrNull { same(it.number, target) }
            ?: return CallLogResult.NOT_FOUND
        return if (hit.durationSec > 0) CallLogResult.ANSWERED else CallLogResult.NOT_ANSWERED
    }

    /** 规范化：只留数字，再取尾部 [len] 位（不足取全部）——应对 +86/空格/横杠差异。 */
    fun digitsTail(n: String, len: Int = 11): String {
        val d = n.filter(Char::isDigit)
        return if (d.length <= len) d else d.takeLast(len)
    }

    /** 平台号码比较：API31+ 带国家码的 areSamePhoneNumber，低版本 compare；再兜底数字尾部相等。 */
    fun sameNumber(context: Context, a: String, b: String): Boolean {
        val sysMatch = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PhoneNumberUtils.areSamePhoneNumber(a, b, countryIso(context))
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(context, a, b)
            }
        }.getOrDefault(false)
        if (sysMatch) return true
        val ta = digitsTail(a)
        val tb = digitsTail(b)
        return ta.isNotEmpty() && ta == tb
    }

    /** 比较用国家码：网络国家 → SIM 国家 → 默认 cn（小写）。 */
    private fun countryIso(context: Context): String {
        val tm = runCatching {
            context.getSystemService(TelephonyManager::class.java)
        }.getOrNull() ?: return "cn"
        val iso = runCatching { tm.networkCountryIso }.getOrNull()
            ?: runCatching { tm.simCountryIso }.getOrNull()
        return if (iso.isNullOrBlank()) "cn" else iso.lowercase()
    }
}
