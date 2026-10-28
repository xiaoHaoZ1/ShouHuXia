package io.github.xiaohaoz1.jiarenxia.guardian.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.result.contract.ActivityResultContract

/** 选中的联系人号码。 */
data class PickedContact(val name: String?, val number: String)

/**
 * 系统通讯录选号：ACTION_PICK + Phone.CONTENT_TYPE（无需 READ_CONTACTS，
 * 用户主动选择即授权读取该条）。与主应用 ContactPicker 同协议，模块内自带。
 */
class PickPhoneContact : ActivityResultContract<Unit, PickedContact?>() {

    private var resolverContext: Context? = null

    override fun createIntent(context: Context, input: Unit): Intent {
        resolverContext = context
        return Intent(Intent.ACTION_PICK)
            .setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedContact? {
        if (resultCode != android.app.Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        val resolver = resolverContext?.contentResolver ?: return null
        return runCatching {
            resolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val number = c.getString(0)?.trim()
                if (number.isNullOrBlank()) null
                else PickedContact(c.getString(1), number)
            }
        }.getOrNull()
    }
}
