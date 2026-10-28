package com.jiarenxia.guardian

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 宿主级导航验证：直接启动正式 MainActivity（不是测试专用 Activity），
 * 走真实的页底隐私入口点击与两种返回路径（顶栏返回、系统返回键）。
 * 不触发响铃/拨号/短信；守护状态为全新安装的默认值（未开启）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class GuardianNavTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsEntryAndPrivacyNavigation() {
        // 首页（默认未开启守护）
        composeRule.onNodeWithText("守护是什么", substring = true).assertIsDisplayed()

        // 首页「设置」卡 → 独立设置页；系统返回键回首页
        composeRule.onNodeWithText("设置").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护设置").assertIsDisplayed()

        // 设置 → 权限清单（自动检测 + 需手动确认两组）→ 返回，仍回设置页
        composeRule.onNodeWithText("查看权限清单").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("权限清单").assertIsDisplayed()
        composeRule.onNodeWithText("自动检测", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("需要手动确认", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护设置").assertIsDisplayed()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护是什么", substring = true).assertIsDisplayed()

        // 再进设置页，从页底隐私入口进入隐私说明
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("隐私说明").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护侠不联网", substring = true).assertIsDisplayed()

        // 顶栏返回按钮 → 回设置页（保留原导航层级）
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护设置").assertIsDisplayed()

        // 设置页返回 → 回首页
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护是什么", substring = true).assertIsDisplayed()
    }

    @Test
    fun drillEntryNavigation() {
        // 首页向导第③步 → 实机测试页 → 返回首页
        composeRule.onNodeWithText("③", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("开始实机测试").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("做一次真机演练").assertIsDisplayed()
        composeRule.onNodeWithText("30 分钟").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护是什么", substring = true).assertIsDisplayed()
    }
}
