package io.github.xiaohaoz1.jiarenxia.guardian.core

/** 时钟抽象：引擎与纯函数只依赖它，单测用 FakeClock 控制时间。 */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
