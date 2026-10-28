package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * OEM 白名单设置页跳转：小米/vivo/oppo/华为常见组件多候选，
 * resolveActivity 逐个尝试，全失败降级到应用详情页。
 */
object OemSettings {

    /** 自启动管理页候选。 */
    fun autostartIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        return listOf(
            // 小米 / 澎湃OS
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra("package_name", pkg)
                .putExtra("packageName", pkg),
            // vivo / iQOO
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                )
            ),
            // OPPO / 一加
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity",
                )
            ),
            // 华为
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                )
            ),
        )
    }

    /** 省电/电池优化页候选。 */
    fun batteryIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        return listOf(
            // 小米：神隐模式/应用省电配置
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
            ).putExtra("package_name", pkg).putExtra("package_label", "家人侠·守护"),
            // 通用：请求加入电池优化白名单（弹窗）
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$pkg")),
            // 通用：电池优化名单页
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }

    /** 逐个候选尝试；全部失败打开本应用详情页。返回是否打开了某个页面。 */
    fun openFirst(context: Context, candidates: List<Intent>): Boolean {
        for (base in candidates) {
            val intent = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "candidate failed: $base", t)
            }
        }
        return openAppDetails(context)
    }

    fun openAutostart(context: Context): Boolean =
        openFirst(context, autostartIntents(context))

    fun openBattery(context: Context): Boolean =
        openFirst(context, batteryIntents(context))

    fun openAppDetails(context: Context): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "openAppDetails failed", t)
            false
        }
    }

    private const val TAG = "GuardOem"
}
