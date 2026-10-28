package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/** 拨打紧急电话：TelecomManager.placeCall + 免提 extra（无需接管通话）。 */
class TelecomCaller(private val context: Context) {

    fun placeCall(number: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CALL_PHONE 未授权，无法拨号")
            return false
        }
        val tm = context.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            tm.placeCall(Uri.fromParts("tel", number, null), extras)
            Log.d(TAG, "placeCall " + io.github.xiaohaoz1.jiarenxia.guardian.core.maskNumber(number))
            true
        } catch (se: SecurityException) {
            Log.e(TAG, "placeCall SecurityException", se)
            false
        } catch (t: Throwable) {
            Log.e(TAG, "placeCall failed", t)
            false
        }
    }

    companion object {
        const val TAG = "GuardCall"
    }
}
