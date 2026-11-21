package io.github.krax1337.gsync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.github.krax1337.gsync.R
import io.github.krax1337.gsync.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rings the phone for the watch's phone finder: the default alarm sound at full alarm volume, so
 * it is heard in silent and vibrate mode. Stops on [stop] or by itself after [RING_LIMIT_MS].
 */
class PhoneFinder(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val timeout = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var volumeBefore = 0

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing

    /** Starts the tone. Throws if no alarm sound can be played. */
    @Synchronized
    fun start() {
        if (player != null) return
        val sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        volumeBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val ringer = MediaPlayer()
        try {
            ringer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            ringer.setDataSource(context, sound)
            ringer.isLooping = true
            // Keeps the CPU awake while the tone plays with the screen off.
            ringer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            ringer.prepare()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            ringer.start()
        } catch (e: Exception) {
            ringer.release()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, volumeBefore, 0)
            throw e
        }
        player = ringer
        _ringing.value = true
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_finder), NotificationManager.IMPORTANCE_HIGH),
        )
        notifications.notify(NOTIFICATION_ID, notification())
        timeout.postDelayed(::stop, RING_LIMIT_MS)
    }

    @Synchronized
    fun stop() {
        val ringer = player ?: return
        player = null
        timeout.removeCallbacksAndMessages(null)
        runCatching { ringer.stop() }
        ringer.release()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, volumeBefore, 0)
        notifications.cancel(NOTIFICATION_ID)
        _ringing.value = false
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            context,
            0,
            Intent(context, SyncService::class.java).setAction(SyncService.ACTION_STOP_FINDER),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(context.getString(R.string.finder_title))
            .setContentText(context.getString(R.string.finder_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.finder_stop), stop).build())
            .build()
    }

    private companion object {
        const val CHANNEL = "finder"
        const val NOTIFICATION_ID = 2
        const val RING_LIMIT_MS = 60_000L
    }
}
