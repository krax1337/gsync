package io.github.krax1337.gsync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.krax1337.gsync.protocol.ClockFace
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.watch.ClockSource
import io.github.krax1337.gsync.watch.FaceChoice
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
    face: ClockFace = ClockFace.SEGMENTS,
    /** Which of its two digit styles a watch with the [ClockFace.SQUARE] display is set to. */
    classicDigits: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWatchColors.current
    // Only a segment display has a ground of its own colour; the others are ink on plain ground.
    val mip = face != ClockFace.SEGMENTS
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (mip) colors.mipPanel else colors.lcdPanel,
        contentColor = if (mip) colors.mipInk else colors.lcdSegment,
        border = BorderStroke(1.dp, if (mip) colors.mipEdge else colors.lcdEdge),
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Sized from the width, not from the font scale: the digits always fit and never clip.
            when (face) {
                ClockFace.SEGMENTS -> LcdFace(
                    offsetMs = clock.offsetMs,
                    source = clock.source,
                    use24Hour = use24Hour,
                    dayFirst = dayFirst,
                    language = language,
                    digitSize = min(maxWidth / ROW_EM, MAX_DIGIT_SIZE),
                )
                ClockFace.SQUARE -> SquareFace(
                    offsetMs = clock.offsetMs,
                    source = clock.source,
                    use24Hour = use24Hour,
                    dayFirst = dayFirst,
                    language = language,
                    classic = classicDigits,
                    digitSize = min(maxWidth / (squareFaceEm(classicDigits) + 0.04f), MAX_DIGIT_SIZE),
                )
                ClockFace.SPORTS -> MipFace(
                    offsetMs = clock.offsetMs,
                    source = clock.source,
                    use24Hour = use24Hour,
                    dayFirst = dayFirst,
                    language = language,
                    digitSize = min(maxWidth / MIP_ROW_EM, MIP_MAX_DIGIT_SIZE),
                )
            }
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
    val caption = caption(source)
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

/**
 * Seven-segment [text] of the given digit height, over the faint unlit segments of [ghost] where
 * the display has such.
 */
@Composable
private fun LcdDigits(
    text: String,
    ghost: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = LocalWatchColors.current.lcdSegment,
) {
    val colors = LocalWatchColors.current
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val style = remember(fontSize) { TextStyle(fontFamily = LcdFont, fontSize = fontSize) }
    Box(modifier = modifier) {
        if (ghost != null) {
            Text(
                text = ghost,
                color = colors.lcdGhost,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
        Text(
            text = text,
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * The face the clock of a watch is drawn with, and whether its time is in the classic digits:
 * what the user chose, or with [FaceChoice.AUTOMATIC] the face of the model in the digits the
 * watch is set to.
 */
internal fun faceFor(choice: FaceChoice, modelFace: ClockFace, classicOnWatch: Boolean): Pair<ClockFace, Boolean> =
    when (choice) {
        FaceChoice.AUTOMATIC -> modelFace to classicOnWatch
        FaceChoice.SQUARE_CLASSIC -> ClockFace.SQUARE to true
        FaceChoice.SQUARE_STANDARD -> ClockFace.SQUARE to false
        FaceChoice.SPORTS -> ClockFace.SPORTS to classicOnWatch
        FaceChoice.SEGMENTS -> ClockFace.SEGMENTS to classicOnWatch
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

private fun caption(source: ClockSource): String = when (source) {
    ClockSource.NTP -> "Network time"
    ClockSource.PHONE -> "Phone clock"
}

/*
 * Proportions of the memory-in-pixel face, in heights of the large digits, measured on Casio's
 * picture of the GBD-200's timekeeping screen. The digits are drawn, not set in a font: one even
 * stroke with rounded corners.
 */
private const val MIP_DIGIT_EM = 0.68f

/** A one is narrower than the other digits. */
private const val MIP_ONE_EM = 0.39f
private const val MIP_STROKE_EM = 0.165f
private const val MIP_GAP_EM = 0.09f
private const val MIP_SLASH_EM = 0.42f

/** Width of `88:88`: the time is set against its right edge, whatever digits it has. */
private const val MIP_TIME_EM = 4 * MIP_DIGIT_EM + MIP_STROKE_EM + 4 * MIP_GAP_EM

/** The A or P of 12-hour time stands in front of the time, and its place is kept in 24-hour time. */
private const val MIP_MARKER_EM = 0.3f
private const val MIP_MARKER_SCALE = 0.34f

/** The face is as wide as the marker and the time, with a little room for pixel rounding. */
private const val MIP_FACE_EM = MIP_MARKER_EM + MIP_TIME_EM
private const val MIP_ROW_EM = MIP_FACE_EM + 0.04f

/** The date, the weekday lettering and the seconds, relative to the hours and minutes. */
private const val MIP_DATE_SCALE = 0.31f
private const val MIP_WEEKDAY_SCALE = 0.46f
private const val MIP_SECONDS_SCALE = 0.5f

/** How far the date and the seconds stand in from the right edge, and the weekday from the left. */
private const val MIP_DATE_INSET_EM = 0.39f
private const val MIP_SECONDS_INSET_EM = 0.18f
private const val MIP_WEEKDAY_INSET_EM = 0.38f

/** The lower rule ends before the seconds. */
private const val MIP_SHORT_RULE = 0.61f

/** Space between the lines of the face. */
private const val MIP_LINE_GAP_EM = 0.15f

/** This face is taller than the seven-segment one for the same digits, so they stop growing sooner. */
private val MIP_MAX_DIGIT_SIZE = 84.dp

/**
 * The strokes of each digit as points on a grid one cell wide and two tall: x from 0 to 1, y from
 * 0 at the top to 2 at the bottom. A stroke that ends where it began is closed.
 */
private val MIP_STROKES: Map<Char, List<FloatArray>> = mapOf(
    '0' to listOf(floatArrayOf(0f, 0f, 1f, 0f, 1f, 2f, 0f, 2f, 0f, 0f)),
    '1' to listOf(floatArrayOf(0.45f, 0f, 1f, 0f, 1f, 2f)),
    '2' to listOf(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, 2f, 1f, 2f)),
    '3' to listOf(floatArrayOf(0f, 0f, 1f, 0f, 1f, 2f, 0f, 2f), floatArrayOf(0.3f, 1f, 1f, 1f)),
    '4' to listOf(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), floatArrayOf(1f, 0f, 1f, 2f)),
    '5' to listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 2f, 0f, 2f)),
    '6' to listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 2f, 1f, 2f, 1f, 1f, 0f, 1f)),
    '7' to listOf(floatArrayOf(0f, 0f, 1f, 0f, 1f, 2f)),
    '8' to listOf(floatArrayOf(0f, 0f, 1f, 0f, 1f, 2f, 0f, 2f, 0f, 0f), floatArrayOf(0f, 1f, 1f, 1f)),
    '9' to listOf(floatArrayOf(1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 2f, 0f, 2f)),
)

/**
 * The face of the sports models, laid out like their timekeeping screen: the date above a rule,
 * the time across the width, and under a shorter rule the weekday on the left and the seconds on
 * the right. The icons the watch shows around them are left out.
 */
@Composable
private fun MipFace(
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
    val caption = caption(source)
    // Sized with the digits, like everything on this face, not with the font scale.
    val density = LocalDensity.current
    val weekdayStyle = remember(digitSize, density) { mipLettering(with(density) { (digitSize * MIP_WEEKDAY_SCALE).toSp() }) }
    val markerStyle = remember(digitSize, density) { mipLettering(with(density) { (digitSize * MIP_MARKER_SCALE).toSp() }) }
    val lineGap = digitSize * MIP_LINE_GAP_EM

    Column(
        modifier = Modifier
            .width(digitSize * MIP_FACE_EM)
            .clearAndSetSemantics { contentDescription = "Reference time ${reading.spoken}. $caption." },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            // 6/30, not 06.30.
            MipDigits(
                text = reading.date.split('.').joinToString("/") { it.toInt().toString() },
                height = digitSize * MIP_DATE_SCALE,
            )
            Spacer(Modifier.width(digitSize * MIP_DATE_INSET_EM))
        }
        Spacer(Modifier.height(lineGap))
        MipRule(Modifier.fillMaxWidth())
        Spacer(Modifier.height(lineGap))
        Row {
            // The watch writes A or P, not AM or PM, halfway up the digits.
            Box(
                modifier = Modifier.width(digitSize * MIP_MARKER_EM).height(digitSize),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (reading.marker != null) Text(text = reading.marker.take(1), style = markerStyle, maxLines = 1)
            }
            // No dark segment stands in for a missing tens digit here: the place stays empty.
            Box(modifier = Modifier.width(digitSize * MIP_TIME_EM), contentAlignment = Alignment.BottomEnd) {
                MipDigits(text = reading.time.removePrefix("!"), height = digitSize)
            }
        }
        Spacer(Modifier.height(lineGap))
        Box(modifier = Modifier.fillMaxWidth()) {
            Column {
                MipRule(Modifier.fillMaxWidth(MIP_SHORT_RULE))
                Text(
                    text = reading.weekday,
                    style = weekdayStyle,
                    maxLines = 1,
                    modifier = Modifier.padding(start = digitSize * MIP_WEEKDAY_INSET_EM),
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = digitSize * MIP_SECONDS_INSET_EM)) {
                MipSeconds(seconds = seconds, height = digitSize * MIP_SECONDS_SCALE)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = LocalWatchColors.current.mipInk.copy(alpha = 0.72f),
        )
    }
}

private fun mipLettering(size: TextUnit) = TextStyle(fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 1.sp)

/** One of the thin lines the display draws between its fields. */
@Composable
private fun MipRule(modifier: Modifier) {
    Box(modifier.height(1.5.dp).background(LocalWatchColors.current.mipInk))
}

@Composable
private fun MipSeconds(seconds: LongState, height: Dp, style: DigitStyle = SPORTS_DIGITS) {
    MipDigits(text = TWO_DIGITS[(seconds.longValue % 60).toInt()], height = height, style = style)
}

/** The proportions of a set of drawn digits, in digit heights. */
private class DigitStyle(
    val digitEm: Float,
    /** A one is narrower than the other digits. */
    val oneEm: Float,
    val strokeEm: Float,
    val gapEm: Float,
) {
    /** Width of `88:88`. */
    val timeEm: Float get() = 4 * digitEm + strokeEm + 4 * gapEm

    fun advanceEm(char: Char): Float = when (char) {
        ':' -> strokeEm
        '/' -> MIP_SLASH_EM
        '-' -> digitEm * 0.6f
        '1' -> oneEm
        else -> digitEm
    }

    fun widthEm(text: String): Float =
        text.fold(0f) { width, char -> width + advanceEm(char) } + (text.length - 1).coerceAtLeast(0) * gapEm
}

/** The digits of the sports models. */
private val SPORTS_DIGITS = DigitStyle(digitEm = MIP_DIGIT_EM, oneEm = MIP_ONE_EM, strokeEm = MIP_STROKE_EM, gapEm = MIP_GAP_EM)

/** The standard digits of the GW-BX5600, measured on photographs of the watch. */
private val STANDARD_DIGITS = DigitStyle(digitEm = 0.52f, oneEm = 0.3f, strokeEm = 0.14f, gapEm = 0.12f)

/** [text] in drawn digits of the given [height]. It knows digits, `:`, `/` and `-`. */
@Composable
private fun MipDigits(text: String, height: Dp, style: DigitStyle = SPORTS_DIGITS) {
    val ink = LocalWatchColors.current.mipInk
    Canvas(modifier = Modifier.size(width = height * style.widthEm(text), height = height)) {
        var left = 0f
        for (char in text) {
            drawMipGlyph(char, left, ink, style)
            left += size.height * (style.advanceEm(char) + style.gapEm)
        }
    }
}

/** Draws [char] in one stroke, as tall as the canvas, its left edge at [left]. */
private fun DrawScope.drawMipGlyph(char: Char, left: Float, ink: Color, style: DigitStyle) {
    val height = size.height
    val stroke = height * style.strokeEm
    val dot = Size(stroke, stroke)
    when (char) {
        ':' -> {
            drawRect(ink, Offset(left, height * 0.3f - stroke / 2), dot)
            drawRect(ink, Offset(left, height * 0.7f - stroke / 2), dot)
        }
        '/' -> drawLine(
            color = ink,
            start = Offset(left + stroke * 0.35f, height - stroke * 0.2f),
            end = Offset(left + height * MIP_SLASH_EM - stroke * 0.35f, stroke * 0.2f),
            strokeWidth = stroke * 0.8f,
        )
        '-' -> drawRect(ink, Offset(left, (height - stroke) / 2), Size(height * style.advanceEm(char), stroke))
        else -> {
            val strokes = MIP_STROKES[char] ?: return
            // The points are the middle of the stroke: half its width stays clear on every side.
            val cell = height * style.advanceEm(char) - stroke
            val rise = (height - stroke) / 2
            val path = Path()
            for (points in strokes) {
                for (index in points.indices step 2) {
                    val x = left + stroke / 2 + points[index] * cell
                    val y = stroke / 2 + points[index + 1] * rise
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (points[0] == points[points.size - 2] && points[1] == points[points.size - 1]) path.close()
            }
            drawPath(path, ink, style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Round))
        }
    }
}

/*
 * The face of the GW-BX5600, measured on photographs of the watch: weekday and boxed date over
 * the time, the seconds after it. In heights of the large digits.
 */
private const val SQUARE_SECONDS_SCALE = 0.6f
private const val SQUARE_SECONDS_GAP_EM = 0.1f
private const val SQUARE_DATE_SCALE = 0.43f
private const val SQUARE_WEEKDAY_SCALE = 0.62f
private const val SQUARE_LINE_GAP_EM = 0.18f

/** The A or P of 12-hour time stands in front of the time, and its place is kept in 24-hour time. */
private const val SQUARE_MARKER_EM = 0.35f
private const val SQUARE_MARKER_SCALE = 0.3f

/** Width of the face: the marker, `88:88` and the seconds, in the digits that are set. */
private fun squareFaceEm(classic: Boolean): Float {
    val time = if (classic) 4 * DIGIT_EM + COLON_EM else STANDARD_DIGITS.timeEm
    val seconds = if (classic) 2 * DIGIT_EM else STANDARD_DIGITS.widthEm("88")
    return SQUARE_MARKER_EM + time + SQUARE_SECONDS_GAP_EM + seconds * SQUARE_SECONDS_SCALE
}

/**
 * The face of the GW-BX5600. Its display is memory-in-pixel, so nothing shows where a segment is
 * dark. The watch has two styles for the time: [classic] seven-segment digits or the standard
 * ones. The date is in the standard digits either way. The small LT and SIG marks beside the
 * seconds are left out.
 */
@Composable
private fun SquareFace(
    offsetMs: Long,
    source: ClockSource,
    use24Hour: Boolean,
    dayFirst: Boolean,
    language: Language,
    classic: Boolean,
    digitSize: Dp,
) {
    val seconds = rememberTicks(offsetMs = offsetMs, unitMs = SECOND_MS)
    val minute by remember(seconds) { derivedStateOf { seconds.longValue / 60 } }
    val reading = remember(minute, use24Hour, dayFirst, language) {
        LcdReading.at(epochMinute = minute, use24Hour = use24Hour, dayFirst = dayFirst, language = language)
    }
    val caption = caption(source)
    val secondsSize = digitSize * SQUARE_SECONDS_SCALE
    val ink = LocalWatchColors.current.mipInk
    // Sized with the digits, like everything on this face, not with the font scale.
    val density = LocalDensity.current
    val weekdayStyle = remember(digitSize, density) { mipLettering(with(density) { (digitSize * SQUARE_WEEKDAY_SCALE).toSp() }) }
    val markerStyle = remember(digitSize, density) { mipLettering(with(density) { (digitSize * SQUARE_MARKER_SCALE).toSp() }) }
    val markerWidth = digitSize * SQUARE_MARKER_EM

    Column(
        modifier = Modifier
            .width(digitSize * squareFaceEm(classic))
            .clearAndSetSemantics { contentDescription = "Reference time ${reading.spoken}. $caption." },
    ) {
        // The weekday starts where the time does, the box ends where the seconds do.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = markerWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = reading.weekday, style = weekdayStyle, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .border(1.5.dp, ink, RoundedCornerShape(3.dp))
                    .padding(horizontal = digitSize * 0.14f, vertical = digitSize * 0.11f),
            ) {
                // 28-11, not 28.11.
                MipDigits(text = reading.date.replace('.', '-'), height = digitSize * SQUARE_DATE_SCALE, style = STANDARD_DIGITS)
            }
        }
        Spacer(Modifier.height(digitSize * SQUARE_LINE_GAP_EM))
        Row {
            // The watch writes A or P, not AM or PM, at the top of the line.
            Box(modifier = Modifier.width(markerWidth)) {
                if (reading.marker != null) Text(text = reading.marker.take(1), style = markerStyle, maxLines = 1)
            }
            if (classic) {
                LcdDigits(text = reading.time, ghost = null, size = digitSize, modifier = Modifier.alignByBaseline(), color = ink)
                Spacer(Modifier.width(digitSize * SQUARE_SECONDS_GAP_EM))
                SquareClassicSeconds(seconds = seconds, size = secondsSize, modifier = Modifier.alignByBaseline())
            } else {
                // The drawn digits stand on their lower edge. Nothing stands in for a missing tens digit.
                Box(
                    modifier = Modifier.width(digitSize * STANDARD_DIGITS.timeEm).alignBy { it.measuredHeight },
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    MipDigits(text = reading.time.removePrefix("!"), height = digitSize, style = STANDARD_DIGITS)
                }
                Spacer(Modifier.width(digitSize * SQUARE_SECONDS_GAP_EM))
                Box(modifier = Modifier.alignBy { it.measuredHeight }) {
                    MipSeconds(seconds = seconds, height = secondsSize, style = STANDARD_DIGITS)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = ink.copy(alpha = 0.72f),
            modifier = Modifier.padding(start = markerWidth),
        )
    }
}

@Composable
private fun SquareClassicSeconds(seconds: LongState, size: Dp, modifier: Modifier) {
    LcdDigits(
        text = TWO_DIGITS[(seconds.longValue % 60).toInt()],
        ghost = null,
        size = size,
        modifier = modifier,
        color = LocalWatchColors.current.mipInk,
    )
}
