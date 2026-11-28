package io.github.krax1337.gsync

import io.github.krax1337.gsync.protocol.ClockFace
import io.github.krax1337.gsync.protocol.Setting
import io.github.krax1337.gsync.protocol.Support
import io.github.krax1337.gsync.protocol.TimeProtocol
import io.github.krax1337.gsync.protocol.WatchProfile
import io.github.krax1337.gsync.protocol.WatchProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    private fun of(name: String) = WatchProfiles.of(name)

    @Test
    fun `the GW-BX5600 is the confirmed model and keeps everything it had`() {
        val expected = WatchProfile(
            model = "GW-BX5600",
            support = Support.VERIFIED,
            timeProtocol = TimeProtocol.CLOCK_BLOCKS,
            alarmCount = 5,
            lastAlarmIsSnooze = true,
            hasHourlySignal = true,
            hasTimer = true,
            hasAutoSync = true,
            settings = Setting.entries.toSet(),
            shortLight = "1.5 s",
            longLight = "3 s",
            hasBattery = true,
            hasTemperature = true,
            hasPhoneFinder = true,
            batteryScale = 14..24,
            face = ClockFace.SQUARE,
        )
        assertEquals(expected, of("CASIO GW-BX5600"))
        assertEquals(expected, of("GW-BX5600"))
    }

    @Test
    fun `the GMW-BZ5000 is tried with the clock blocks of the GW-BX5600`() {
        val profile = of("CASIO GMW-BZ5000")
        assertEquals("GMW-BZ5000", profile.model)
        assertEquals(Support.EXPERIMENTAL, profile.support)
        assertEquals(TimeProtocol.CLOCK_BLOCKS, profile.timeProtocol)
    }

    @Test
    fun `the register watches are served as experimental under their own name`() {
        val names = listOf(
            "GW-B5600", "GMW-B5000", "DW-B5600", "GA-B2100", "GST-B500", "G-B001", "GPR-B1000",
            "GBA-900", "GBD-200", "GBD-800", "ABL-100WE",
        )
        for (name in names) {
            val profile = of("CASIO $name")
            assertEquals(name, profile.model)
            assertEquals(name, Support.EXPERIMENTAL, profile.support)
            assertEquals(name, TimeProtocol.REGISTERS, profile.timeProtocol)
            assertEquals(name, 5, profile.alarmCount)
        }
    }

    @Test
    fun `a model only offers what it has`() {
        val square = of("CASIO GW-B5600")
        assertEquals(Setting.entries.toSet() - Setting.DIGITS, square.settings)
        assertEquals("2 s" to "4 s", square.shortLight to square.longLight)
        assertTrue(square.lastAlarmIsSnooze)
        assertFalse(Setting.AUTO_LIGHT in of("CASIO DW-B5600").settings)

        val octagon = of("CASIO GA-B2100")
        assertEquals(Setting.entries.toSet() - Setting.DIGITS - Setting.AUTO_LIGHT, octagon.settings)
        assertEquals("1.5 s" to "3 s", octagon.shortLight to octagon.longLight)
        assertTrue(octagon.hasTimer && octagon.hasBattery && octagon.hasTemperature)

        val tracker = of("CASIO GBD-200")
        assertTrue(tracker.hasBattery)
        assertFalse(tracker.hasTemperature)
        assertFalse(tracker.hasTimer)

        val retro = of("CASIO ABL-100WE")
        assertFalse(retro.hasBattery || retro.hasTemperature || retro.hasTimer)
        assertFalse(Setting.LANGUAGE in retro.settings || Setting.DATE_FORMAT in retro.settings)
    }

    @Test
    fun `steps are only offered where GShockAPI reads them`() {
        for (name in listOf("ABL-100WE", "F-B100W", "BSA-B100")) assertTrue(name, of("CASIO $name").hasSteps)
        val without = listOf("GW-BX5600", "GMW-BZ5000", "GW-B5600", "GBD-100", "GBD-200", "GBX-100", "GBD-800", "GBA-900", "DW-H5600")
        for (name in without) assertFalse(name, of("CASIO $name").hasSteps)
    }

    @Test
    fun `watches the app cannot serve are unsupported and are offered nothing`() {
        val names = listOf(
            "CASIO DW-H5600", // stays connected all the time
            "CASIO ECB-30",
            "CASIO MTG-B1000", // hands that need their own procedure
            "CASIO EQB-1000",
            "CASIO GST-B100",
            "CASIO GWR-B1000",
            "CASIO GB-6900B", // first Bluetooth generation
            "CASIO OCW-T200", // only guessed at
            "CASIO XYZ-1000", // not a model anybody listed
            "CASIO",
            "",
        )
        for (name in names) {
            val profile = of(name)
            assertEquals(name, Support.UNSUPPORTED, profile.support)
            assertEquals(name, 0, profile.alarmCount)
            assertEquals(name, emptySet<Setting>(), profile.settings)
            assertFalse(name, profile.hasTimer || profile.hasAutoSync || profile.hasBattery || profile.hasTemperature || profile.hasPhoneFinder)
        }
        assertEquals("XYZ-1000", of("CASIO XYZ-1000").model)
    }

    @Test
    fun `a name that only starts like a known model is not that model`() {
        assertEquals(Support.UNSUPPORTED, of("CASIO GBD-2000").support)
        assertEquals(Support.UNSUPPORTED, of("CASIO GW-B5600X").support)
        assertEquals(Support.UNSUPPORTED, of("CASIO GW-BX56").support)
    }

    @Test
    fun `a name is read as the watch sends it, padded or marked`() {
        // Zero bytes after the name are padding.
        assertEquals(Support.EXPERIMENTAL, of("CASIO GA-B2100\u0000\u0000").support)
        assertEquals("GBX-100", of("CASIO GBX-100\u0000").model)
        assertEquals(Support.VERIFIED, of("CASIO GW-BX5600\u0000").support)
        // Later runs of the squares add a # to theirs.
        assertEquals(Support.EXPERIMENTAL, of("CASIO GMW-B5000#").support)
        assertEquals(of("CASIO GMW-B5000").settings, of("CASIO GMW-B5000#").settings)
    }
}
