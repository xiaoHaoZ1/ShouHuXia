package com.jiarenxia.guardian

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * 走真实的首次设置硬门槛、必要权限页与隐私说明返回路径。
 * 不触发响铃/拨号/短信；守护状态为全新安装的默认值（未开启）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class GuardianNavTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstRunBlocksLaterFeaturesButKeepsPrivacy() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("开始授权必要权限").assertIsDisplayed()
        check(composeRule.onAllNodesWithText("守护设置").fetchSemanticsNodes().isEmpty())
        check(composeRule.onAllNodesWithText("开始完整实机测试").fetchSemanticsNodes().isEmpty())

        // 严格向导仍保留隐私说明；返回后仍停在第 1 步。
        composeRule.onNodeWithText("隐私说明").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("守护侠不联网", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("开始授权必要权限").assertIsDisplayed()
    }

    @Test
    fun requiredPermissionEntryReturnsToFirstStep() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("开始授权必要权限").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("必要权限").assertIsDisplayed()
        composeRule.onNodeWithText("必须授权").assertIsDisplayed()
        composeRule.onNodeWithText("继续授权：拨打电话").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("开始授权必要权限").assertIsDisplayed()
    }
}
