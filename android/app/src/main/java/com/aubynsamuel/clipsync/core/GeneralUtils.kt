package com.aubynsamuel.clipsync.core

import android.content.Context
import android.widget.Toast
import com.aubynsamuel.clipsync.bluetooth.SharingResult

internal const val tag = "BluetoothService"

fun showToast(msg: String, context: Context) {
    runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}

fun getSharingResultMessage(sendingResult: SharingResult?): String = when (sendingResult) {
    SharingResult.SUCCESS -> I18n.t("sent_ok")
    SharingResult.SENDING_ERROR -> I18n.t("send_failed")
    SharingResult.PERMISSION_NOT_GRANTED -> I18n.t("permissions_denied")
    SharingResult.NO_SELECTED_DEVICES -> I18n.t("no_devices")
    SharingResult.FILE_TOO_LARGE -> I18n.t("file_too_large", "")
    SharingResult.CANCELLED -> I18n.t("transfer_cancelled")
    null -> I18n.t("send_failed")
}
