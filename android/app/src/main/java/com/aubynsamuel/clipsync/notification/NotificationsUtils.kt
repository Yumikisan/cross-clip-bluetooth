package com.aubynsamuel.clipsync.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aubynsamuel.clipsync.R
import com.aubynsamuel.clipsync.activities.MainActivity
import com.aubynsamuel.clipsync.activities.OpenReceivedFileActivity
import com.aubynsamuel.clipsync.activities.ShareClipboardActivity
import com.aubynsamuel.clipsync.bluetooth.BluetoothService
import com.aubynsamuel.clipsync.core.I18n

const val channelId = "ClipSyncServiceChannel"

/** Separate, high importance channel so the confirmation code actually pops up. */
const val authChannelId = "ClipSyncAuthChannel"

private const val AUTH_NOTIFICATION_ID = 2001

fun createAuthChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            authChannelId,
            I18n.t("auth_channel"),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = I18n.t("auth_channel_desc")
            enableVibration(true)
            setShowBadge(true)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}

/**
 * Shows the six digit code with Confirm / Reject actions. The same request also reaches the
 * in-app dialog through AuthGate, so whichever surface the user touches first wins.
 */
fun showAuthNotification(deviceName: String, code: String, sid: String, context: Context) {
    val pretty = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

    val acceptIntent = Intent(context, NotificationReceiver::class.java).apply {
        action = "ACTION_AUTH_OK"
        putExtra("AUTH_SID", sid)
        putExtra("AUTH_NAME", deviceName)
    }
    val denyIntent = Intent(context, NotificationReceiver::class.java).apply {
        action = "ACTION_AUTH_DENY"
        putExtra("AUTH_SID", sid)
        putExtra("AUTH_NAME", deviceName)
    }

    val acceptPending = PendingIntent.getBroadcast(
        context, sid.hashCode(), acceptIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val denyPending = PendingIntent.getBroadcast(
        context, sid.hashCode() + 1, denyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Tapping a notification opens the history page; the settings page decides which
        // slice of it (all / text only / files only).
        putExtra(MainActivity.EXTRA_OPEN_HISTORY, true)
    }
    val contentPending = PendingIntent.getActivity(
        context, 0, contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val body = I18n.t("auth_text", deviceName, pretty)

    val notification = NotificationCompat.Builder(context, authChannelId)
        .setContentTitle(I18n.t("auth_title"))
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setSmallIcon(R.mipmap.ic_launcher)
        .addAction(0, I18n.t("auth_accept"), acceptPending)
        .addAction(0, I18n.t("auth_deny"), denyPending)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setAutoCancel(true)
        .setContentIntent(contentPending)
        .build()

    notifySafely(context, AUTH_NOTIFICATION_ID, notification)
}

fun cancelAuthNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(AUTH_NOTIFICATION_ID)
}

fun createServiceNotification(context: Context, useShizuku: Boolean = false): Notification {
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Tapping a notification opens the history page; the settings page decides which
        // slice of it (all / text only / files only).
        putExtra(MainActivity.EXTRA_OPEN_HISTORY, true)
    }
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        0,
        contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // With Shizuku the service itself can read the clipboard, so no window is needed at all.
    val sharePendingIntent = if (useShizuku) {
        val shareIntent = Intent(context, BluetoothService::class.java).apply {
            action = BluetoothService.ACTION_SHARE
        }
        PendingIntent.getService(
            context, 1, shareIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } else {
        val shareIntent = Intent(context, ShareClipboardActivity::class.java).apply {
            action = "ACTION_SHARE"
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        PendingIntent.getActivity(
            context, 1, shareIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    val dismissIntent = Intent(context, NotificationReceiver::class.java).apply {
        action = "ACTION_DISMISS"
    }
    val dismissPendingIntent = PendingIntent.getBroadcast(
        context,
        2,
        dismissIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(context, channelId)
        .setContentTitle(I18n.t("notif_title"))
        .setContentText(I18n.t("notif_text"))
        .setSmallIcon(R.mipmap.ic_launcher)
        .addAction(0, I18n.t("action_share"), sharePendingIntent)
        .addAction(0, I18n.t("action_stop"), dismissPendingIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .setContentIntent(contentPendingIntent)
        .build()
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = I18n.t("notify_channel")
        val descriptionText = I18n.t("notify_channel_desc")
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}

fun showReceivedNotification(text: String, context: Context) {
    val notificationId = 4000 + (Math.random() * 1000).toInt()
    val copyIntent = Intent(context, NotificationReceiver::class.java).apply {
        action = "ACTION_COPY"
        putExtra("CLIP_TEXT", text)
        putExtra("NOTIFICATION_ID", notificationId)
    }

    val copyPendingIntent = PendingIntent.getBroadcast(
        context, notificationId, copyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle(I18n.t("received_clip"))
        .setContentText(text.take(120).replace('\n', ' '))
        .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
        .setSmallIcon(R.mipmap.ic_launcher)
        .addAction(0, I18n.t("copy"), copyPendingIntent)
        .setAutoCancel(true)
        .build()

    notifySafely(context, notificationId, notification)
}

/**
 * "File received" notice. Tapping it opens the file with the same helper the history list uses,
 * so the behaviour is identical from both places (default viewer, then chooser, then share).
 * The request code is derived from the uri so two files never share a PendingIntent.
 */
fun showFileReceivedNotification(name: String, uri: String, context: Context) {
    val builder = NotificationCompat.Builder(context, channelId)
        .setContentTitle(I18n.t("file_received", name))
        .setContentText(name)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setAutoCancel(true)

    if (uri.isNotBlank()) {
        val open = Intent(context, OpenReceivedFileActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(OpenReceivedFileActivity.EXTRA_URI, uri)
            putExtra(OpenReceivedFileActivity.EXTRA_NAME, name)
        }
        builder.setContentIntent(
            PendingIntent.getActivity(
                context,
                uri.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    notifySafely(context, 3999, builder.build())
}

/**
 * One summary for a whole folder transfer. Per-file notices are suppressed while a folder is
 * open, otherwise a 40-file folder would bury the shade in forty identical lines.
 */
fun showFolderReceivedNotification(name: String, files: Int, folderUri: String, context: Context) {
    val builder = NotificationCompat.Builder(context, channelId)
        .setContentTitle(I18n.t("folder_received", name))
        .setContentText(I18n.t("folder_received_files", files))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setAutoCancel(true)

    // Tapping a folder lands on the system "open with" list, exactly like tapping a received
    // file does -- a notice you cannot act on is just noise. The uri is optional: when the
    // lookup failed the opener finds the folder again from its name, so the tap always works.
    val open = Intent(context, OpenReceivedFileActivity::class.java).apply {
        putExtra(OpenReceivedFileActivity.EXTRA_URI, folderUri)
        putExtra(OpenReceivedFileActivity.EXTRA_NAME, name)
        putExtra(OpenReceivedFileActivity.EXTRA_IS_FOLDER, true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    builder.setContentIntent(
        PendingIntent.getActivity(
            context,
            3998,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )

    notifySafely(context, 3998, builder.build())
}

fun sharingResultNotification(title: String, text: String, context: Context) {
    val notificationId = 1000
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Tapping a notification opens the history page; the settings page decides which
        // slice of it (all / text only / files only).
        putExtra(MainActivity.EXTRA_OPEN_HISTORY, true)
    }
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        0,
        contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle(title)
        .setContentText(text.take(120))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setAutoCancel(true)
        .setContentIntent(contentPendingIntent)
        .build()

    notifySafely(context, notificationId, notification)
}

private fun notifySafely(context: Context, id: Int, notification: Notification) {
    NotificationManagerCompat.from(context).apply {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notify(id, notification) }
        }
    }
}
