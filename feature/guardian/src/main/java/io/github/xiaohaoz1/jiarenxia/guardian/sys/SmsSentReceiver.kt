package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import io.github.xiaohaoz1.jiarenxia.guardian.core.SmsKind
import kotlin.concurrent.thread

/** 短信 sentIntent 回传入口：kind+index 归因，resultCode 判定发出成败。 */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return
        // kind 解析失败直接忽略（版本间枚举不一致等）
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { SmsKind.valueOf(it) }.getOrNull() } ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        if (index < 0) return
        val ok = resultCode == Activity.RESULT_OK
        Log.d(TAG, "短信回执 kind=$kind index=$index resultCode=$resultCode")
        val pending = goAsync()
        thread(name = "GuardSmsSent") {
            try {
                ServiceLocator.init(context)
                ServiceLocator.engine.handle(Event.SmsSent(kind, index, ok))
            } catch (t: Throwable) {
                Log.e(TAG, "handle SmsSent failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "GuardSmsSent"
        const val ACTION_SMS_SENT =
            "io.github.xiaohaoz1.jiarenxia.guardian.sms.SENT"
        const val EXTRA_KIND = "kind"
        const val EXTRA_INDEX = "index"
    }
}
