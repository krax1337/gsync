package io.github.krax1337.gsync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.DateFormat
import io.github.krax1337.gsync.GsyncApp
import io.github.krax1337.gsync.R
import io.github.krax1337.gsync.ui.MainActivity
import io.github.krax1337.gsync.watch.LinkState
import io.github.krax1337.gsync.watch.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Keeps the process alive and listening for the watch. Runs whenever a watch is paired: without
 * it Android freezes the app within minutes and the watch's sync attempts go unanswered.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val manager = (application as GsyncApp).manager
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification(manager.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        scope.launch {
            manager.run()
            stopSelf()
        }
        scope.launch {
            manager.state
                .map { it.copy(batteryPercent = null, temperatureC = null, alarms = null, settings = null, autoSync = null) }
                .distinctUntilChanged()
                .collect { notifications.notify(NOTIFICATION_ID, notification(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FINDER) (application as GsyncApp).manager.stopFinder()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: WatchState): Notification {
        val status = when (state.link) {
            LinkState.BLUETOOTH_OFF -> getString(R.string.status_bluetooth_off)
            LinkState.CONNECTING, LinkState.SYNCING -> getString(R.string.status_syncing)
            LinkState.CONNECTED -> getString(R.string.status_connected)
            LinkState.WAITING -> state.lastSync
                ?.let { getString(R.string.status_last_sync, DateFormat.getTimeFormat(this).format(Date(it.atMs))) }
                ?: getString(R.string.status_waiting)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(state.watch?.name?.removePrefix("CASIO ") ?: getString(R.string.app_name))
            .setContentText(status)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1

        /** Sent by the "Stop" button of the phone finder notification. */
        const val ACTION_STOP_FINDER = "io.github.krax1337.gsync.STOP_FINDER"

        /** Starts listening for the watch. Does nothing until a watch is paired and Bluetooth access is granted. */
        fun start(context: Context) {
            val app = context.applicationContext as GsyncApp
            if (app.manager.state.value.watch == null || !Permissions.granted(context)) return
            context.startForegroundService(Intent(context, SyncService::class.java))
        }
    }
}
