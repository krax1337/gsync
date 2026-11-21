package io.github.krax1337.gsync.watch

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.Feature
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.protocol.frameOf
import io.github.krax1337.gsync.protocol.hexToFrame
import io.github.krax1337.gsync.protocol.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What survives a process restart: the pairing, the last known watch state and unsent edits. */
data class Saved(
    val watch: Watch? = null,
    val batteryPercent: Int? = null,
    val temperatureC: Int? = null,
    val lastSync: SyncRecord? = null,
    val alarms: AlarmSet? = null,
    val alarmsPending: Boolean = false,
    val timerSeconds: Int? = null,
    val timerPending: Boolean = false,
    val settings: WatchSettings? = null,
    val settingsPending: Boolean = false,
    val autoSync: AutoSync? = null,
    val autoSyncPending: Boolean = false,
    val fineAdjustMs: Int = 0,
)

private val Context.watchPreferences by preferencesDataStore("watch")

class WatchStore(private val context: Context) {
    val saved: Flow<Saved> = context.watchPreferences.data.map(::decode)

    suspend fun update(transform: (Saved) -> Saved) {
        context.watchPreferences.edit { encode(transform(decode(it)), it) }
    }
}

private val ADDRESS = stringPreferencesKey("address")
private val NAME = stringPreferencesKey("name")
private val BATTERY = intPreferencesKey("battery")
private val TEMPERATURE = intPreferencesKey("temperature")
private val SYNC_AT = longPreferencesKey("sync_at")
private val SYNC_TRIGGER = stringPreferencesKey("sync_trigger")
private val SYNC_SOURCE = stringPreferencesKey("sync_source")
private val SYNC_ROUND_TRIP = intPreferencesKey("sync_round_trip")
private val SYNC_PHONE_ERROR = longPreferencesKey("sync_phone_error")
private val SYNC_ZONE = stringPreferencesKey("sync_zone")
private val ALARM_FIRST = stringPreferencesKey("alarm_first")
private val ALARM_REST = stringPreferencesKey("alarm_rest")
private val ALARMS_PENDING = booleanPreferencesKey("alarms_pending")
private val TIMER = intPreferencesKey("timer")
private val TIMER_PENDING = booleanPreferencesKey("timer_pending")
private val SETTINGS = stringPreferencesKey("settings")
private val SETTINGS_PENDING = booleanPreferencesKey("settings_pending")
private val AUTO_SYNC = stringPreferencesKey("auto_sync")
private val AUTO_SYNC_PENDING = booleanPreferencesKey("auto_sync_pending")
private val FINE_ADJUST = intPreferencesKey("fine_adjust")

// Alarms, settings and auto sync are stored as the protocol frames that carry them.
private val BLANK_SETTINGS = ByteArray(17).also { it[0] = Feature.SETTINGS.toByte() }
private val BLANK_AUTO_SYNC = ByteArray(14).also { it[0] = Feature.AUTO_SYNC.toByte() }

private fun decode(prefs: Preferences): Saved {
    val address = prefs[ADDRESS]
    val syncAt = prefs[SYNC_AT]
    val alarmFirst = prefs[ALARM_FIRST]
    val alarmRest = prefs[ALARM_REST]
    return Saved(
        watch = address?.let { Watch(it, prefs[NAME].orEmpty()) },
        batteryPercent = prefs[BATTERY],
        temperatureC = prefs[TEMPERATURE],
        lastSync = syncAt?.let {
            SyncRecord(
                atMs = it,
                trigger = Trigger.valueOf(prefs[SYNC_TRIGGER] ?: Trigger.CONNECT.name),
                source = ClockSource.valueOf(prefs[SYNC_SOURCE] ?: ClockSource.PHONE.name),
                ntpRoundTripMs = prefs[SYNC_ROUND_TRIP] ?: 0,
                phoneClockErrorMs = prefs[SYNC_PHONE_ERROR] ?: 0,
                zone = prefs[SYNC_ZONE].orEmpty(),
            )
        },
        alarms = if (alarmFirst != null && alarmRest != null) AlarmSet.decode(alarmFirst.hexToFrame(), alarmRest.hexToFrame()) else null,
        alarmsPending = prefs[ALARMS_PENDING] ?: false,
        timerSeconds = prefs[TIMER],
        timerPending = prefs[TIMER_PENDING] ?: false,
        settings = prefs[SETTINGS]?.let { WatchSettings.decode(it.hexToFrame()) },
        settingsPending = prefs[SETTINGS_PENDING] ?: false,
        autoSync = prefs[AUTO_SYNC]?.let { AutoSync.decode(it.hexToFrame()) },
        autoSyncPending = prefs[AUTO_SYNC_PENDING] ?: false,
        fineAdjustMs = prefs[FINE_ADJUST] ?: 0,
    )
}

private fun encode(saved: Saved, prefs: MutablePreferences) {
    prefs.clear()
    saved.watch?.let {
        prefs[ADDRESS] = it.address
        prefs[NAME] = it.name
    }
    saved.batteryPercent?.let { prefs[BATTERY] = it }
    saved.temperatureC?.let { prefs[TEMPERATURE] = it }
    saved.lastSync?.let {
        prefs[SYNC_AT] = it.atMs
        prefs[SYNC_TRIGGER] = it.trigger.name
        prefs[SYNC_SOURCE] = it.source.name
        prefs[SYNC_ROUND_TRIP] = it.ntpRoundTripMs
        prefs[SYNC_PHONE_ERROR] = it.phoneClockErrorMs
        prefs[SYNC_ZONE] = it.zone
    }
    saved.alarms?.let {
        prefs[ALARM_FIRST] = it.firstFrame().toHex()
        prefs[ALARM_REST] = it.restFrame().toHex()
    }
    saved.timerSeconds?.let { prefs[TIMER] = it }
    saved.settings?.let { prefs[SETTINGS] = it.applyTo(BLANK_SETTINGS).toHex() }
    saved.autoSync?.let { prefs[AUTO_SYNC] = it.applyTo(BLANK_AUTO_SYNC).toHex() }
    prefs[ALARMS_PENDING] = saved.alarmsPending
    prefs[TIMER_PENDING] = saved.timerPending
    prefs[SETTINGS_PENDING] = saved.settingsPending
    prefs[AUTO_SYNC_PENDING] = saved.autoSyncPending
    prefs[FINE_ADJUST] = saved.fineAdjustMs
}
