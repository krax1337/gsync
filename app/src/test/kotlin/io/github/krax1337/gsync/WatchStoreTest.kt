package io.github.krax1337.gsync

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.krax1337.gsync.protocol.Alarm
import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.watch.ClockSource
import io.github.krax1337.gsync.watch.Saved
import io.github.krax1337.gsync.watch.SavedWatch
import io.github.krax1337.gsync.watch.SyncRecord
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.decode
import io.github.krax1337.gsync.watch.encode
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStoreTest {
    private val first = Watch("D3:60:41:AB:CD:EF", "CASIO GW-BX5600")
    private val second = Watch("E1:22:33:44:55:66", "CASIO GW-BX5600")

    @Test
    fun `a watch paired before several watches were possible is kept with everything known about it`() {
        val stored = mutablePreferencesOf(
            stringPreferencesKey("address") to first.address,
            stringPreferencesKey("name") to first.name,
            intPreferencesKey("battery") to 80,
            longPreferencesKey("sync_at") to 1_795_000_000_000,
            stringPreferencesKey("sync_trigger") to "AUTO_SYNC",
            stringPreferencesKey("sync_source") to "NTP",
            intPreferencesKey("sync_round_trip") to 18,
            longPreferencesKey("sync_phone_error") to -412,
            stringPreferencesKey("sync_zone") to "Asia/Almaty",
            intPreferencesKey("timer") to 600,
            booleanPreferencesKey("timer_pending") to true,
            intPreferencesKey("fine_adjust") to -120,
            booleanPreferencesKey("phone_finder") to false,
        )

        val expected = Saved(
            watches = listOf(
                SavedWatch(
                    watch = first,
                    batteryPercent = 80,
                    lastSync = SyncRecord(1_795_000_000_000, Trigger.AUTO_SYNC, ClockSource.NTP, 18, -412, "Asia/Almaty"),
                    timerSeconds = 600,
                    timerPending = true,
                    fineAdjustMs = -120,
                ),
            ),
            selected = first.address,
            phoneFinderEnabled = false,
        )
        assertEquals(expected, decode(stored))

        // Rewritten in the new layout, it reads back the same and the old keys are gone.
        encode(expected, stored)
        assertEquals(expected, decode(stored))
        assertEquals(null, stored[stringPreferencesKey("address")])
    }

    @Test
    fun `each watch keeps its own state and the one on screen is remembered`() {
        val saved = Saved(
            watches = listOf(
                SavedWatch(
                    watch = first,
                    temperatureC = -3,
                    alarms = AlarmSet(List(5) { Alarm(6 + it, 30, it % 2 == 0) }, hourlyChime = true),
                    alarmsPending = true,
                    settings = WatchSettings(true, false, true, true, false, true, Language.ENGLISH, true),
                    autoSync = AutoSync(true, 30),
                    homeCity = "KARACHI",
                    worldCities = listOf("NEW YORK", "TOKYO"),
                    syncData = listOf("slots 05AA", "zones 03BB", "cities 06CC"),
                ),
                SavedWatch(watch = second, timerSeconds = 90, fineAdjustMs = 40),
            ),
            selected = second.address,
        )
        val stored = mutablePreferencesOf()
        encode(saved, stored)

        val read = decode(stored)
        assertEquals(saved, read)
        assertEquals(second, read.current?.watch)
    }

    @Test
    fun `forgetting the watch on screen brings up the next one`() {
        val saved = Saved(watches = listOf(SavedWatch(first), SavedWatch(second)), selected = second.address)
        val stored = mutablePreferencesOf()
        encode(saved.copy(watches = saved.watches.filter { it.watch != second }, selected = null), stored)

        assertEquals(first, decode(stored).current?.watch)
        assertEquals(null, decode(mutablePreferencesOf()).current)
    }
}
