package io.github.xiaohaoz1.jiarenxia.guardian.core

/** 活动类型，label 用于日志与界面中文显示。 */
enum class ActivityKind(val label: String) {
    UNLOCK("解锁手机"),
    LOCK_AFTER_USE("使用后熄屏"),
    CALL("通话"),
    MANUAL_IM_OK("手动确认平安"),
    USAGE_STATS("系统记录找回"),
    ENABLE("开启守护"),
}
