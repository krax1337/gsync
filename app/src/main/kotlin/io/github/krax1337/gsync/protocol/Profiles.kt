package io.github.krax1337.gsync.protocol

/** How far this app can be trusted with a watch model. */
enum class Support {
    /** Confirmed on a real watch. */
    VERIFIED,

    /** Written from what is known about the protocol; nobody has confirmed it on this model yet. */
    EXPERIMENTAL,

    /** A Casio watch this app cannot serve. */
    UNSUPPORTED,
}

/** How a watch takes a new time. */
enum class TimeProtocol {
    /** Three clock blocks on a second pair of characteristics, then the time (see [ClockBlocks]). */
    CLOCK_BLOCKS,

    /** Summer time, zone and city registers read and written back one by one, then the time (see [ClockRegisters]). */
    REGISTERS,
}

/** Where a [TimeProtocol.REGISTERS] watch keeps the city of each of its clocks. */
enum class CityRegister {
    /** Nowhere the time procedure touches. */
    NONE,

    /** [Feature.CITY_NAME]: the name as text, which this app can show. */
    NAMES,

    /** [Feature.HOME_TIME]: a record whose layout is not known; it is only written back. */
    HOME_TIME,
}

/** A field of [WatchSettings] that a model may or may not have. */
enum class Setting { TIME_FORMAT, DATE_FORMAT, LANGUAGE, DIGITS, AUTO_LIGHT, LIGHT_DURATION, BUTTON_TONE, POWER_SAVING }

/** What differs between watch models, as far as this app is concerned. */
data class WatchProfile(
    /** Model name without the "CASIO " the watch advertises in front of it. */
    val model: String,
    val support: Support,
    val timeProtocol: TimeProtocol,
    val alarmCount: Int,
    /** The last alarm is the snooze alarm. */
    val lastAlarmIsSnooze: Boolean,
    val hasHourlySignal: Boolean,
    /** A countdown timer of up to 24 hours. Models with interval timers do not count. */
    val hasTimer: Boolean,
    /** The watch connects by itself four times a day, and that can be switched and timed. */
    val hasAutoSync: Boolean,
    val settings: Set<Setting>,
    /** The two backlight durations the watch offers, as shown to the user. */
    val shortLight: String,
    val longLight: String,
    val hasBattery: Boolean,
    val hasTemperature: Boolean,
    val hasPhoneFinder: Boolean,
    /** The raw battery reading when empty and when full; it differs between modules. */
    val batteryScale: IntRange = 9..19,
    /**
     * Clocks a [TimeProtocol.REGISTERS] watch keeps, home time included: 2 or 6. Each has a zone
     * register and, if [cityRegister] says so, a city; every two share a summer time register.
     */
    val clocks: Int = 0,
    val cityRegister: CityRegister = CityRegister.NONE,
)

/**
 * The model table.
 *
 * Everything but the GW-BX5600 is taken from the table of izivkov/GShockAPI (`WatchInfo.kt`) and
 * has not been tried on a watch. As there, a model is found by its exact name. Left out on purpose,
 * although GShockAPI lists them: watches that stay connected all the time (DW-H5600, ECB-30),
 * watches with hands only (MTG, EQB, GST-B100), the first Bluetooth generation (GB-6900B), and
 * everything it files under "generic".
 */
object WatchProfiles {
    private val GW_BX5600 = WatchProfile(
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
    )

    /** What GShockAPI assumes of a register watch unless its table says otherwise. */
    private val STANDARD = WatchProfile(
        model = "",
        support = Support.EXPERIMENTAL,
        timeProtocol = TimeProtocol.REGISTERS,
        alarmCount = 5,
        // GShockAPI does not know snooze alarms.
        lastAlarmIsSnooze = false,
        hasHourlySignal = true,
        hasTimer = true,
        hasAutoSync = true,
        settings = Setting.entries.toSet() - Setting.DIGITS - Setting.AUTO_LIGHT,
        shortLight = "1.5 s",
        longLight = "3 s",
        hasBattery = true,
        hasTemperature = true,
        hasPhoneFinder = true,
        batteryScale = 9..19,
        clocks = 2,
        cityRegister = CityRegister.NAMES,
    )

    /** A watch this app leaves alone: it offers nothing, and no session can be opened on it. */
    private val UNSUPPORTED = WatchProfile(
        model = "",
        support = Support.UNSUPPORTED,
        timeProtocol = TimeProtocol.REGISTERS,
        alarmCount = 0,
        lastAlarmIsSnooze = false,
        hasHourlySignal = false,
        hasTimer = false,
        hasAutoSync = false,
        settings = emptySet(),
        shortLight = "",
        longLight = "",
        hasBattery = false,
        hasTemperature = false,
        hasPhoneFinder = false,
    )

    private val MODELS: Map<String, WatchProfile> = buildMap {
        fun family(profile: WatchProfile, vararg models: String) = models.forEach { put(it, profile.copy(model = it)) }
        val autoLight = STANDARD.copy(settings = STANDARD.settings + Setting.AUTO_LIGHT)

        family(GW_BX5600, "GW-BX5600")
        // The full-metal sibling of the GW-BX5600. GShockAPI describes the clock blocks for its
        // module but lists it with the register watches, so this entry is the least certain.
        family(GW_BX5600.copy(support = Support.EXPERIMENTAL, batteryScale = 9..19), "GMW-BZ5000")

        // Digital squares: six clocks. Their fifth alarm is the snooze alarm (Casio's guides, not GShockAPI).
        val square = autoLight.copy(clocks = 6, lastAlarmIsSnooze = true, shortLight = "2 s", longLight = "4 s")
        family(square, "GW-B5600", "GW-B5000", "GMW-B5000", "MRG-B5000", "GCW-B5000", "TRN-50")
        family(square.copy(settings = STANDARD.settings), "DW-B5600")

        // Hands and a display, two clocks: the GA-B2100 line, G-STEEL and the two Edifice models
        // on a G-STEEL module.
        family(STANDARD, "GA-B2100", "GM-B2100", "GBM-2100", "GA-B001", "GA-B010")
        family(STANDARD, "GST-B200", "GST-B300", "GST-B400", "GST-B500", "GST-B600", "ECB-900", "ECB-950")
        // Digital, two clocks.
        family(autoLight, "G-B001")
        family(autoLight.copy(settings = autoLight.settings - Setting.LANGUAGE), "GPR-B1000")

        // Step trackers. Casio describes their timers as interval timers, and whether the timer
        // frame of the watches above fits those is not known, so the timer is left alone.
        family(STANDARD.copy(hasTimer = false), "GBA-800", "GBA-900", "GBA-950")
        val tracker = autoLight.copy(hasTimer = false, hasTemperature = false)
        family(tracker.copy(cityRegister = CityRegister.HOME_TIME), "GBD-100", "GBD-200", "GBD-300", "GBD-H1000", "GBX-100")
        val bare = tracker.copy(hasBattery = false, cityRegister = CityRegister.NONE)
        family(bare, "GBD-800", "GMD-B800")
        family(bare.copy(settings = STANDARD.settings - Setting.DATE_FORMAT - Setting.LANGUAGE), "ABL-100WE", "F-B100W", "BSA-B100")
    }

    /** The profile of a watch, from the name it advertises (for example "CASIO GW-BX5600"). */
    fun of(advertisedName: String): WatchProfile {
        val model = advertisedName.removePrefix("CASIO").trim()
        val name = model.substringBefore(' ')
        return MODELS[name]
            // A watch of the confirmed model that was paired before this table existed must keep
            // working, whatever a variant adds to the name.
            ?: GW_BX5600.takeIf { name.startsWith(it.model) }
            ?: UNSUPPORTED.copy(model = model)
    }
}
