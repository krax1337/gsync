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
import io.github.krax1337.gsync.protocol.hexToFrame
import io.github.krax1337.gsync.protocol.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What is kept for one paired watch: its last known state and the edits not yet written to it. */
data class SavedWatch(
    val watch: Watch,
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
    /** Home city and world time cities stored on the watch, as read at the last sync. */
    val homeCity: String? = null,
    val worldCities: List<String> = emptyList(),
    /** What the watch sent at the last sync, one labelled hex line each, for bug reports. */
    val syncData: List<String> = emptyList(),
)

/** What survives a process restart. */
data class Saved(
    val watches: List<SavedWatch> = emptyList(),
    /** Address of the watch on screen. */
    val selected: String? = null,
    val phoneFinderEnabled: Boolean = true,
) {
    /** The watch on screen: the selected one, or the first if that one is gone. */
    val current: SavedWatch? get() = watch(selected) ?: watches.firstOrNull()

    fun watch(address: String?): SavedWatch? = watches.firstOrNull { it.watch.address == address }

    /** Changes the watch with [address]; nothing happens if it is no longer paired. */
    fun with(address: String?, transform: (SavedWatch) -> SavedWatch): Saved =
        copy(watches = watches.map { if (it.watch.address == address) transform(it) else it })
}

private val Context.watchPreferences by preferencesDataStore("watch")

class WatchStore(private val context: Context) {
    val saved: Flow<Saved> = context.watchPreferences.data.map(::decode)

    suspend fun update(transform: (Saved) -> Saved) {
        context.watchPreferences.edit { encode(transform(decode(it)), it) }
    }
}

private val WATCHES = stringPreferencesKey("watches")
private val SELECTED = stringPreferencesKey("selected")
private val PHONE_FINDER = booleanPreferencesKey("phone_finder")

/** Up to 0.1.1 there was one watch, stored under the field names alone. */
private val LEGACY_ADDRESS = stringPreferencesKey("address")

// Alarms, settings and auto sync are stored as the protocol frames that carry them.
private val BLANK_SETTINGS = ByteArray(17).also { it[0] = Feature.SETTINGS.toByte() }
private val BLANK_AUTO_SYNC = ByteArray(14).also { it[0] = Feature.AUTO_SYNC.toByte() }

internal fun decode(prefs: Preferences): Saved {
    val legacy = prefs[LEGACY_ADDRESS]
    val watches = prefs[WATCHES]?.split(',')?.filter { it.isNotEmpty() }?.map { decodeWatch(prefs, it, "$it/") }
        ?: listOfNotNull(legacy?.let { decodeWatch(prefs, it, "") })
    return Saved(
        watches = watches,
        selected = prefs[SELECTED] ?: legacy,
        phoneFinderEnabled = prefs[PHONE_FINDER] ?: true,
    )
}

internal fun encode(saved: Saved, prefs: MutablePreferences) {
    prefs.clear()
    prefs[WATCHES] = saved.watches.joinToString(",") { it.watch.address }
    saved.current?.let { prefs[SELECTED] = it.watch.address }
    prefs[PHONE_FINDER] = saved.phoneFinderEnabled
    saved.watches.forEach { encodeWatch(it, prefs, "${it.watch.address}/") }
}

/** The fields of one watch are stored under [prefix] followed by the field name. */
private fun decodeWatch(prefs: Preferences, address: String, prefix: String): SavedWatch {
    fun string(field: String) = prefs[stringPreferencesKey(prefix + field)]
    fun int(field: String) = prefs[intPreferencesKey(prefix + field)]
    fun long(field: String) = prefs[longPreferencesKey(prefix + field)]
    fun flag(field: String) = prefs[booleanPreferencesKey(prefix + field)] ?: false

    val alarmFirst = string("alarm_first")
    val alarmRest = string("alarm_rest")
    return SavedWatch(
        watch = Watch(address, string("name").orEmpty()),
        batteryPercent = int("battery"),
        temperatureC = int("temperature"),
        lastSync = long("sync_at")?.let {
            SyncRecord(
                atMs = it,
                trigger = Trigger.valueOf(string("sync_trigger") ?: Trigger.CONNECT.name),
                source = ClockSource.valueOf(string("sync_source") ?: ClockSource.PHONE.name),
                ntpRoundTripMs = int("sync_round_trip") ?: 0,
                phoneClockErrorMs = long("sync_phone_error") ?: 0,
                zone = string("sync_zone").orEmpty(),
            )
        },
        alarms = if (alarmFirst != null && alarmRest != null) AlarmSet.decode(alarmFirst.hexToFrame(), alarmRest.hexToFrame()) else null,
        alarmsPending = flag("alarms_pending"),
        timerSeconds = int("timer"),
        timerPending = flag("timer_pending"),
        settings = string("settings")?.let { WatchSettings.decode(it.hexToFrame()) },
        settingsPending = flag("settings_pending"),
        autoSync = string("auto_sync")?.let { AutoSync.decode(it.hexToFrame()) },
        autoSyncPending = flag("auto_sync_pending"),
        fineAdjustMs = int("fine_adjust") ?: 0,
        homeCity = string("home_city"),
        worldCities = string("world_cities")?.split('|').orEmpty(),
        syncData = string("sync_data")?.split('\n').orEmpty(),
    )
}

private fun encodeWatch(saved: SavedWatch, prefs: MutablePreferences, prefix: String) {
    fun string(field: String, value: String) = prefs.set(stringPreferencesKey(prefix + field), value)
    fun int(field: String, value: Int) = prefs.set(intPreferencesKey(prefix + field), value)
    fun long(field: String, value: Long) = prefs.set(longPreferencesKey(prefix + field), value)
    fun flag(field: String, value: Boolean) = prefs.set(booleanPreferencesKey(prefix + field), value)

    string("name", saved.watch.name)
    saved.batteryPercent?.let { int("battery", it) }
    saved.temperatureC?.let { int("temperature", it) }
    saved.lastSync?.let {
        long("sync_at", it.atMs)
        string("sync_trigger", it.trigger.name)
        string("sync_source", it.source.name)
        int("sync_round_trip", it.ntpRoundTripMs)
        long("sync_phone_error", it.phoneClockErrorMs)
        string("sync_zone", it.zone)
    }
    saved.alarms?.let {
        string("alarm_first", it.firstFrame().toHex())
        string("alarm_rest", it.restFrame().toHex())
    }
    saved.timerSeconds?.let { int("timer", it) }
    saved.settings?.let { string("settings", it.applyTo(BLANK_SETTINGS).toHex()) }
    saved.autoSync?.let { string("auto_sync", it.applyTo(BLANK_AUTO_SYNC).toHex()) }
    flag("alarms_pending", saved.alarmsPending)
    flag("timer_pending", saved.timerPending)
    flag("settings_pending", saved.settingsPending)
    flag("auto_sync_pending", saved.autoSyncPending)
    int("fine_adjust", saved.fineAdjustMs)
    saved.homeCity?.let { string("home_city", it) }
    if (saved.worldCities.isNotEmpty()) string("world_cities", saved.worldCities.joinToString("|"))
    if (saved.syncData.isNotEmpty()) string("sync_data", saved.syncData.joinToString("\n"))
}
