package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import io.github.xiaohaoz1.jiarenxia.guardian.R
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardianFormat
import io.github.xiaohaoz1.jiarenxia.guardian.core.TimeRules
import io.github.xiaohaoz1.jiarenxia.guardian.ui.PreAlarmActivity

/** 通知渠道与通知构建集中管理。 */
object Notifications {

    /** 常驻状态通知走最低重要性渠道：无状态栏图标、不打扰。 */
    const val CHANNEL_STATUS = "guardian_status_quiet"
    const val CHANNEL_CONFIRM = "guardian_confirm"
    const val CHANNEL_RESULT = "guardian_result"

    /**
     * 所有守护通知统一用此 tag，与家人侠生日通知的 id 永不冲突。
     * 唯一例外：前台服务的状态通知由 startForeground 持有，无 tag（id 相同）。
     */
    const val TAG_NOTIFY = "guardian"

    /** V0.2 及之前的状态渠道（IMPORTANCE_LOW），升级后删除重建为 MIN。 */
    private const val CHANNEL_STATUS_LEGACY = "guardian_status"

    const val ID_STATUS = 1
    const val ID_CONFIRM = 2
    const val ID_ALERTED = 3
    /** 演练确认通知：报警类通知，走 CHANNEL_CONFIRM + TAG_NOTIFY，演练结束必须清除。 */
    const val ID_DRILL_CONFIRM = 4

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // 旧渠道 guardian_status 仍挂在在跑的 FGS 上时 delete 会抛异常，吞掉即可
        runCatching { nm.deleteNotificationChannel(CHANNEL_STATUS_LEGACY) }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_MIN,
            )
        )

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val confirm = NotificationChannel(
            CHANNEL_CONFIRM,
            context.getString(R.string.channel_confirm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setBypassDnd(true)
            enableVibration(true)
            if (alarmSound != null) {
                setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build(),
                )
            }
        }
        nm.createNotificationChannel(confirm)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun mainIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            20,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 前台服务常驻通知：文案走 core 纯函数。
     * 演练进行中显示"正在测试"；守护已开启但检测到异常时改报"守护可能失效"。
     * 仍然只有这一条状态通知（无 tag、渠道 guardian_status_quiet）。
     */
    fun statusNotification(context: Context): Notification {
        ensureChannels(context)
        val state = ServiceLocator.store.stateFlow.value
        val text = if (
            !state.drill.active &&
            ServiceLocator.store.configFlow.value.enabled &&
            runCatching { Permissions.anomalyReasons(context) }
                .getOrDefault(emptyList()).isNotEmpty()
        ) {
            "守护可能失效，请打开查看"
        } else {
            GuardianFormat.statusText(
                state, ServiceLocator.store.configFlow.value.validContacts)
        }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(mainIntent(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    /** 刷新 FGS 那条状态通知：必须无 tag、同 id，否则会变成第二条通知。 */
    fun refreshStatus(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(ID_STATUS, statusNotification(context))
    }

    /** 清掉旧版本用 tag 发的那条重复状态通知。 */
    fun cancelLegacyStatus(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(TAG_NOTIFY, ID_STATUS)
    }

    /** 到期确认：全屏 intent 拉起 PreAlarmActivity + 两个大按钮。 */
    fun showConfirm(context: Context, confirmEndAt: Long) {
        ensureChannels(context)
        val fullScreen = PendingIntent.getActivity(
            context,
            21,
            Intent(context, PreAlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val imOk = PendingIntent.getBroadcast(
            context,
            22,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_IM_OK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alertNow = PendingIntent.getBroadcast(
            context,
            23,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_ALERT_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(context.getString(R.string.confirm_notif_title))
            .setContentText(context.getString(R.string.confirm_notif_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, context.getString(R.string.btn_im_ok), imOk)
            .addAction(0, context.getString(R.string.btn_alert_now), alertNow)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(TAG_NOTIFY, ID_CONFIRM, n)
    }

    fun cancelConfirm(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(TAG_NOTIFY, ID_CONFIRM)
    }

    /**
     * 演练确认通知：全屏 intent 拉起 PreAlarmActivity（演练样式），
     * 只提供一个次要的"停止测试"按钮——演练页面不允许"我没事"。
     */
    fun showDrillConfirm(context: Context, confirmEndAt: Long) {
        ensureChannels(context)
        val fullScreen = PendingIntent.getActivity(
            context,
            24,
            Intent(context, PreAlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            25,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_DRILL_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(context.getString(R.string.drill_notif_title))
            .setContentText(context.getString(R.string.drill_notif_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, context.getString(R.string.drill_btn_stop), stop)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(TAG_NOTIFY, ID_DRILL_CONFIRM, n)
    }

    fun cancelDrill(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(TAG_NOTIFY, ID_DRILL_CONFIRM)
    }

    /** "已于 HH:mm 完成报警：<结果>"——结果按 alertSeq 掩码拼（谁接了/无人接）。 */
    fun showAlerted(context: Context, alertedAt: Long) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                GuardianFormat.alertResultText(
                    ServiceLocator.store.stateFlow.value,
                    ServiceLocator.store.configFlow.value.validContacts,
                )
            )
            .setContentIntent(mainIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(TAG_NOTIFY, ID_ALERTED, n)
    }
}
