package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.xiaohaoz1.jiarenxia.guardian.core.BatteryStatus
import io.github.xiaohaoz1.jiarenxia.guardian.core.SmsKind

/** 短信发送与电量读取：文案在 core/SmsText，本层负责提交与 sentIntent 回传。 */
class SmsReporter(private val context: Context) {

    /**
     * 提交发送一条短信给第 index 位联系人（多段发送，每段各挂 sentIntent），
     * 返回是否成功提交。发出结果由 SmsSentReceiver 回传 Event.SmsSent。
     */
    fun send(number: String, body: String, kind: SmsKind, index: Int): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "SEND_SMS 未授权，无法发送短信 kind=$kind index=$index")
            return false
        }
        val sm: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (sm == null) {
            Log.e(TAG, "SmsManager 不可用")
            return false
        }
        return try {
            val parts = sm.divideMessage(body)
            // 每段一个 sentIntent：requestCode = kind*100 + index*10 + part（part 封顶 9）
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.forEachIndexed { part, _ ->
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    kind.ordinal * 100 + index * 10 + minOf(part, 9),
                    Intent(context, SmsSentReceiver::class.java)
                        .setAction(SmsSentReceiver.ACTION_SMS_SENT)
                        .putExtra(SmsSentReceiver.EXTRA_KIND, kind.name)
                        .putExtra(SmsSentReceiver.EXTRA_INDEX, index),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            sm.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            Log.d(TAG, "短信已提交 kind=$kind index=$index 至 " +
                io.github.xiaohaoz1.jiarenxia.guardian.core.maskNumber(number))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "短信发送失败 kind=$kind index=$index", t)
            false
        }
    }

    /** 电量快照：取不到时 pct=-1（短信文案写"电量未知"）。 */
    fun batteryInfo(): BatteryStatus {
        return try {
            val bat: Intent? = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatteryStatus(pct, charging)
        } catch (t: Throwable) {
            BatteryStatus(-1, false)
        }
    }

    companion object {
        const val TAG = "GuardSms"
    }
}
