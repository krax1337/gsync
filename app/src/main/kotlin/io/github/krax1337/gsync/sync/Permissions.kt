package io.github.krax1337.gsync.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

object Permissions {
    private val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    /** Everything to ask the user for. Notifications are optional: they only show the sync status. */
    val REQUIRED: Array<String> = BLUETOOTH + Manifest.permission.POST_NOTIFICATIONS

    /** Whether the watch can be reached at all. */
    fun granted(context: Context): Boolean =
        BLUETOOTH.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
