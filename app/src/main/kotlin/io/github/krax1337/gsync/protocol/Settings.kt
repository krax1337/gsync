package io.github.krax1337.gsync.protocol

/** Language of the weekday shown on the watch face, in wire order. */
enum class Language { ENGLISH, SPANISH, FRENCH, GERMAN, ITALIAN, RUSSIAN }

/**
 * Basic watch settings from [Feature.SETTINGS].
 *
 * Writing goes through [applyTo]: only the bits modelled here change, everything else in the frame
 * goes back the way the watch sent it.
 */
data class WatchSettings(
    val use24Hour: Boolean,
    val buttonTone: Boolean,
    val autoLight: Boolean,
    val powerSaving: Boolean,
    /** Backlight stays on for 3 s instead of 1.5 s. */
    val longLight: Boolean,
    /** Date reads day.month instead of month.day. */
    val dayFirst: Boolean,
    val language: Language,
    /** Seven-segment style digits instead of the standard typeface. */
    val classicFont: Boolean,
) {
    fun applyTo(frame: ByteArray): ByteArray {
        val out = frame.requireFrame(Feature.SETTINGS, MIN_SIZE).copyOf()
        out[1] = out.u(1)
            .withBit(HOUR_24, use24Hour)
            .withBit(TONE_OFF, !buttonTone)
            .withBit(AUTO_LIGHT_OFF, !autoLight)
            .withBit(POWER_SAVING_OFF, !powerSaving)
            .toByte()
        out[2] = out.u(2).withBit(LIGHT_LONG, longLight).toByte()
        out[4] = if (dayFirst) 1 else 0
        out[5] = language.ordinal.toByte()
        if (out.size > FONT_INDEX) out[FONT_INDEX] = out.u(FONT_INDEX).withBit(FONT_CLASSIC, classicFont).toByte()
        return out
    }

    companion object {
        private const val MIN_SIZE = 6
        private const val HOUR_24 = 0x01
        private const val TONE_OFF = 0x02
        private const val AUTO_LIGHT_OFF = 0x04
        private const val POWER_SAVING_OFF = 0x10
        private const val LIGHT_LONG = 0x01
        private const val FONT_INDEX = 8
        private const val FONT_CLASSIC = 0x20

        fun decode(frame: ByteArray): WatchSettings {
            frame.requireFrame(Feature.SETTINGS, MIN_SIZE)
            val flags = frame.u(1)
            return WatchSettings(
                use24Hour = flags and HOUR_24 != 0,
                buttonTone = flags and TONE_OFF == 0,
                autoLight = flags and AUTO_LIGHT_OFF == 0,
                powerSaving = flags and POWER_SAVING_OFF == 0,
                longLight = frame.u(2) and LIGHT_LONG != 0,
                dayFirst = frame.u(4) == 1,
                language = Language.entries.getOrElse(frame.u(5)) { Language.ENGLISH },
                classicFont = frame.size > FONT_INDEX && frame.u(FONT_INDEX) and FONT_CLASSIC != 0,
            )
        }
    }
}

/**
 * Automatic time adjustment from [Feature.AUTO_SYNC]: when [enabled], the watch connects four
 * times a day, [minute] minutes past the hour.
 */
data class AutoSync(val enabled: Boolean, val minute: Int) {
    fun applyTo(frame: ByteArray): ByteArray =
        frame.requireFrame(Feature.AUTO_SYNC, MIN_SIZE).copyOf().also {
            it[12] = (if (enabled) 0x00 else 0x80).toByte()
            it[13] = minute.toByte()
        }

    companion object {
        private const val MIN_SIZE = 14

        fun decode(frame: ByteArray): AutoSync {
            frame.requireFrame(Feature.AUTO_SYNC, MIN_SIZE)
            return AutoSync(enabled = frame.u(12) == 0, minute = frame.u(13).takeIf { it in 0..59 } ?: 30)
        }
    }
}

private fun Int.withBit(mask: Int, on: Boolean): Int = if (on) this or mask else this and mask.inv()
