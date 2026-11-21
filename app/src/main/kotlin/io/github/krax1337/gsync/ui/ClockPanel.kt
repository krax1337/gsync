package io.github.krax1337.gsync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.watch.ClockSource
import io.github.krax1337.gsync.watch.ReferenceClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Proportions of the seven-segment font, in em. Digits are exactly one em tall and stand on the
 * baseline, so the font size is the digit height.
 */
private const val DIGIT_EM = 0.816f
private const val COLON_EM = 0.2f

/** Empty space on each side of a digit, inside its advance width. */
private const val BEARING_EM = 0.099f

/** Seconds and date digits, relative to the hours and minutes. */
private const val SECONDS_SCALE = 0.5f
private const val DATE_SCALE = 0.3f
private const val SECONDS_GAP_EM = 0.08f

/** Width of `88:88 88` in em of the large digits, with a little room for pixel rounding. */
private const val ROW_EM = 4 * DIGIT_EM + COLON_EM + SECONDS_GAP_EM + 2 * DIGIT_EM * SECONDS_SCALE + 0.04f

/** The digits fill the panel on a phone; on a wider screen they stop growing here. */
private val MAX_DIGIT_SIZE = 96.dp

private val SPOKEN_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/** Weekday abbreviations as the watch writes them in each language, Monday first. */
private val WEEKDAYS: Map<Language, List<String>> = mapOf(
    Language.ENGLISH to listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"),
    Language.SPANISH to listOf("LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB", "DOM"),
    Language.FRENCH to listOf("LUN", "MAR", "MER", "JEU", "VEN", "SAM", "DIM"),
    Language.GERMAN to listOf("MON", "DIE", "MIT", "DON", "FRE", "SAM", "SON"),
    Language.ITALIAN to listOf("LUN", "MAR", "MER", "GIO", "VEN", "SAB", "DOM"),
    Language.RUSSIAN to listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"),
)

/**
 * What the watch should be showing right now, drawn like its display so the two can be held
 * side by side: the time from [clock], laid out the way the watch is set up.
 */
@Composable
internal fun ClockPanel(
    clock: ReferenceClock,
    use24Hour: Boolean,
    dayFirst: Boolean,
    language: Language,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWatchColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.lcdPanel,
        contentColor = colors.lcdSegment,
        border = BorderStroke(1.dp, colors.lcdEdge),
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Sized from the width, not from the font scale: the digits always fit and never clip.
            LcdFace(
                offsetMs = clock.offsetMs,
                source = clock.source,
                use24Hour = use24Hour,
                dayFirst = dayFirst,
                language = language,
                digitSize = min(maxWidth / ROW_EM, MAX_DIGIT_SIZE),
            )
        }
    }
}

/**
 * Owns the tick. This recomposes once a minute and [LcdSeconds] once a second; nothing outside
 * the panel sees the clock run.
 */
@Composable
private fun LcdFace(
    offsetMs: Long,
    source: ClockSource,
    use24Hour: Boolean,
    dayFirst: Boolean,
    language: Language,
    digitSize: Dp,
) {
    val seconds = rememberTicks(offsetMs = offsetMs, unitMs = SECOND_MS)
    val minute by remember(seconds) { derivedStateOf { seconds.longValue / 60 } }
    val reading = remember(minute, use24Hour, dayFirst, language) {
        LcdReading.at(epochMinute = minute, use24Hour = use24Hour, dayFirst = dayFirst, language = language)
    }
    val caption = when (source) {
        ClockSource.NTP -> "Network time"
        ClockSource.PHONE -> "Phone clock"
    }
    val colors = LocalWatchColors.current
    val lettering = MaterialTheme.typography.labelLarge.let {
        remember(it) { it.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
    }
    // Lines the lettering up with the ink of the outer digits rather than with their boxes.
    val startInset = digitSize * BEARING_EM
    val endInset = startInset * SECONDS_SCALE

    Column(
        modifier = Modifier
            .width(digitSize * ROW_EM)
            .clearAndSetSemantics { contentDescription = "Reference time ${reading.spoken}. $caption." },
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = startInset, end = endInset)) {
            Text(text = reading.weekday, style = lettering, maxLines = 1, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(8.dp))
            LcdDigits(
                text = reading.date,
                ghost = "88.88",
                size = digitSize * DATE_SCALE,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.weight(1f))
            if (reading.marker != null) {
                Text(text = reading.marker, style = lettering, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            LcdDigits(text = reading.time, ghost = "88:88", size = digitSize, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(digitSize * SECONDS_GAP_EM))
            LcdSeconds(seconds = seconds, size = digitSize * SECONDS_SCALE, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = colors.lcdLabel,
            modifier = Modifier.padding(start = startInset),
        )
    }
}

@Composable
private fun LcdSeconds(seconds: LongState, size: Dp, modifier: Modifier = Modifier) {
    LcdDigits(text = TWO_DIGITS[(seconds.longValue % 60).toInt()], ghost = "88", size = size, modifier = modifier)
}

/** Seven-segment [text] of the given digit height, over the faint unlit segments of [ghost]. */
@Composable
private fun LcdDigits(text: String, ghost: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalWatchColors.current
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val style = remember(fontSize) { TextStyle(fontFamily = LcdFont, fontSize = fontSize) }
    Box(modifier = modifier) {
        Text(
            text = ghost,
            color = colors.lcdGhost,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = text,
            color = colors.lcdSegment,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/** The parts of the display that change once a minute. */
private class LcdReading(
    /** Hours and minutes; a `!` stands for a blank leading digit. */
    val time: String,
    val date: String,
    val weekday: String,
    /** "AM" or "PM" in 12-hour mode. */
    val marker: String?,
    /** The same in plain text, for the screen reader. */
    val spoken: String,
) {
    companion object {
        fun at(epochMinute: Long, use24Hour: Boolean, dayFirst: Boolean, language: Language): LcdReading {
            val now = Instant.ofEpochSecond(epochMinute * 60).atZone(ZoneId.systemDefault())
            val hour = if (use24Hour) now.hour else (now.hour + 11) % 12 + 1
            val minute = TWO_DIGITS[now.minute]
            val marker = if (use24Hour) null else if (now.hour < 12) "AM" else "PM"
            val day = TWO_DIGITS[now.dayOfMonth]
            val month = TWO_DIGITS[now.monthValue]
            return LcdReading(
                // The watch leaves the tens of the hour dark instead of showing a zero.
                time = (if (hour < 10) "!" else "") + hour + ":" + minute,
                date = if (dayFirst) "$day.$month" else "$month.$day",
                weekday = WEEKDAYS.getValue(language)[now.dayOfWeek.value - 1],
                marker = marker,
                spoken = "$hour:$minute" + (if (marker != null) " $marker" else "") + ", " + SPOKEN_DATE.format(now),
            )
        }
    }
}
