package io.github.krax1337.gsync.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.krax1337.gsync.protocol.WatchCities

/*
 * The four buttons. The guide never names one without saying where it is: the letters are
 * Casio's and are not printed on the watch.
 */
private const val A = "A (upper left)"
private const val B = "B (upper right)"
private const val C = "C (lower left)"
private const val D = "D (lower right)"

/** The drawing of the watch is this tall and, buttons included, this wide. */
private val DIAGRAM_SIZE = 128.dp

/**
 * How the watch itself is operated, for when it has been a while: the buttons, syncing, cities
 * and what to do when travelling. The facts are those of Casio's operation guide for the watch.
 * [homeCity] is the city the watch is set to, if that is known.
 */
@Composable
internal fun GuideScreen(padding: PaddingValues, homeCity: String?, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        PageTopBar(title = "Watch guide", onBack = onBack)
        ScreenColumn(PaddingValues(0.dp)) {
            ButtonsSection()
            Gap()
            SyncingSection()
            Gap()
            HomeCitySection(homeCity)
            Gap()
            WorldTimeSection()
            Gap()
            SwappingSection()
            Gap()
            TravellingSection()
            Gap()
            CitiesSection()
        }
    }
}

@Composable
private fun ButtonsSection() {
    GuideSection("Buttons") {
        WatchDiagram()
        Column {
            ButtonRow(button = A, role = "Settings")
            ButtonRow(button = B, role = "Light")
            ButtonRow(button = C, role = "Mode")
            ButtonRow(button = D, role = "Sync")
        }
    }
}

@Composable
private fun SyncingSection() {
    GuideSection("Syncing") {
        Action(action = "Press $D once", result = "Sync now. The watch shows TIME ADJUST, then SUCCESS.")
        Action(
            action = "Hold $C for 3 seconds",
            result = "Connect and stay connected, to change settings from the app. " +
                "Press any button on the watch to disconnect.",
        )
        Action(
            action = "Hold $D for 5 seconds",
            result = "Phone finder. This phone rings, unless that is turned off in Settings.",
        )
        Action(action = "By itself", result = "Four times a day, at about 00:30, 06:30, 12:30 and 18:30.")
        HorizontalDivider()
        Note(
            "No automatic sync for 24 hours after you change the time or city by hand on the watch, " +
                "or after swapping home and world time. Pressing $D still works.",
        )
        Note("No sync while the timer is counting down.")
    }
}

@Composable
private fun HomeCitySection(homeCity: String?) {
    GuideSection("Home city") {
        Body("G-Sync sets the time. It does not change the city shown on the watch.")
        PhoneZoneCard(homeCity)
        Label("To set it")
        Steps(
            "In the timekeeping mode hold $A for 2 seconds until the city name appears.",
            "Choose the city with $B and $D.",
            "Press $A to save.",
        )
    }
}

@Composable
private fun WorldTimeSection() {
    GuideSection("World time") {
        Body("The watch keeps two world time cities.")
        Steps(
            "Press $C until World Time opens.",
            "Press $D to pick city 1 or 2.",
            "Hold $A for 2 seconds until the city name flashes.",
            "Choose the city with $B and $D.",
            "Press $A to save.",
            "Hold $C for 1 second to return to the time.",
        )
        Note("Summer time for that city: after step 4 press $C, choose AUTO, OFF or ON with $D, then press $A.")
    }
}

@Composable
private fun SwappingSection() {
    GuideSection("Swapping home and world time") {
        Body("In World Time mode press $A and $B together.")
    }
}

@Composable
private fun TravellingSection() {
    GuideSection("Travelling to another time zone") {
        Subtitle("Simple: time only")
        Steps("After landing wait until the phone shows local time.", "Press $D.")
        Note("The watch now shows local time. The city label stays as it was.")
        HorizontalDivider()
        Subtitle("Full: time and city")
        Label("Before the flight")
        Body("Set a world time city to your destination (see World time).")
        Label("After landing")
        Steps(
            "Wait until the phone shows local time.",
            "Open World Time with $C.",
            "Press $A and $B together.",
            "Hold $C for 1 second.",
            "Press $D.",
        )
        Label("Back home")
        Body("Repeat the steps after landing.")
        Note("If the watch synced by itself before you swapped, nothing is lost. Press $D as the last step.")
    }
}

@Composable
private fun CitiesSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Read while drawing, so turning the arrow recomposes nothing.
    val turn = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(MOTION_MS),
        label = "cities arrow",
    )
    // Wide enough for "UTC+12:45" at any font scale, so the names line up.
    val offsetWidth = with(LocalDensity.current) { 84.sp.toDp() }

    Section("Cities built into the watch") {
        SettingRow(
            title = "All ${WatchCities.ALL.size} cities",
            modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            onClick = { expanded = !expanded },
            trailing = {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = turn.value },
                )
            },
        )
        Reveal(visible = expanded, gap = 0.dp) {
            Column {
                RowDivider()
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CITY_LINES.forEach { line ->
                        Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            Text(
                                text = line.offset,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(offsetWidth),
                            )
                            Text(
                                text = line.cities,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Which city to choose on the watch for the zone this phone is in, next to the city it is set to. */
@Composable
private fun PhoneZoneCard(homeCity: String?) {
    val phone = rememberPhoneZone()

    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Your phone is on ${phone.id} (${phone.offset}).", style = MaterialTheme.typography.bodyLarge)
            if (phone.cities.isEmpty()) {
                Text(
                    text = "The watch has no city on this offset. The time is still set correctly.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(
                    text = buildAnnotatedString {
                        append("On the watch choose: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(phone.cities) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (homeCity != null) {
                Text(
                    text = buildAnnotatedString {
                        append("The watch is set to: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(titleCase(homeCity)) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** The watch from the front: the case, its display and the four buttons, each with its letter. */
@Composable
private fun WatchDiagram() {
    val scheme = MaterialTheme.colorScheme
    val lcd = LocalWatchColors.current
    Row(
        // The list under the drawing says the same in words.
        modifier = Modifier.fillMaxWidth().height(DIAGRAM_SIZE).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ButtonLabels(upper = "A", upperRole = "Settings", lower = "C", lowerRole = "Mode", alignment = Alignment.End)
        Canvas(modifier = Modifier.width(DIAGRAM_SIZE).fillMaxHeight()) {
            val button = Size(6.dp.toPx(), 22.dp.toPx())
            val buttonCorner = CornerRadius(2.dp.toPx())
            // A quarter of the way in from the top and from the bottom, level with the labels.
            val upper = size.height / 4 - button.height / 2
            val lower = size.height * 3 / 4 - button.height / 2
            val right = size.width - button.width
            drawRoundRect(scheme.outline, Offset(0f, upper), button, buttonCorner)
            drawRoundRect(scheme.outline, Offset(right, upper), button, buttonCorner)
            drawRoundRect(scheme.outline, Offset(0f, lower), button, buttonCorner)
            drawRoundRect(scheme.outline, Offset(right, lower), button, buttonCorner)

            edgedRoundRect(
                fill = scheme.surfaceContainerHighest,
                edge = scheme.outline,
                topLeft = Offset(button.width, 0f),
                size = Size(size.width - 2 * button.width, size.height),
                corner = 28.dp.toPx(),
                edgeWidth = 1.5.dp.toPx(),
            )
            val displayInset = Offset(button.width + 20.dp.toPx(), 30.dp.toPx())
            edgedRoundRect(
                fill = lcd.lcdPanel,
                edge = lcd.lcdEdge,
                topLeft = displayInset,
                size = Size(size.width - 2 * displayInset.x, size.height - 2 * displayInset.y),
                corner = 6.dp.toPx(),
                edgeWidth = 1.dp.toPx(),
            )
        }
        ButtonLabels(upper = "B", upperRole = "Light", lower = "D", lowerRole = "Sync", alignment = Alignment.Start)
    }
}

/** A filled rounded rectangle with an [edgeWidth] wide line of [edge] just inside its bounds. */
private fun DrawScope.edgedRoundRect(
    fill: Color,
    edge: Color,
    topLeft: Offset,
    size: Size,
    corner: Float,
    edgeWidth: Float,
) {
    drawRoundRect(color = edge, topLeft = topLeft, size = size, cornerRadius = CornerRadius(corner))
    drawRoundRect(
        color = fill,
        topLeft = Offset(topLeft.x + edgeWidth, topLeft.y + edgeWidth),
        size = Size(size.width - 2 * edgeWidth, size.height - 2 * edgeWidth),
        cornerRadius = CornerRadius(corner - edgeWidth),
    )
}

/** The letters of the two buttons on one side of the drawing, each centred on its button. */
@Composable
private fun RowScope.ButtonLabels(
    upper: String,
    upperRole: String,
    lower: String,
    lowerRole: String,
    alignment: Alignment.Horizontal,
) {
    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        ButtonLabel(letter = upper, role = upperRole, alignment = alignment)
        ButtonLabel(letter = lower, role = lowerRole, alignment = alignment)
    }
}

@Composable
private fun ColumnScope.ButtonLabel(letter: String, role: String, alignment: Alignment.Horizontal) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = alignment,
    ) {
        Text(text = letter, style = MaterialTheme.typography.titleMedium)
        Text(
            text = role,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ButtonRow(button: String, role: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
        Text(text = button, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = role,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A [Section] of text rather than of rows: padded, with 12dp between its blocks. */
@Composable
private fun GuideSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Section(title) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Something done on the watch, and what comes of it. */
@Composable
private fun Action(action: String, result: String) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(text = action, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = result,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [steps] in order, numbered from 1. */
@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { index, text -> Step(number = index + 1, text = text) }
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

/** A remark that matters less than the steps around it. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Names one of several procedures inside a section. */
@Composable
private fun Subtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/** Names the steps that follow it. */
@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

/** One line of the city list: every city the watch has on one offset. */
private class CityLine(val offset: String, val cities: String)

/** [WatchCities.ALL] is ordered by offset, so grouping keeps that order. */
private val CITY_LINES: List<CityLine> by lazy {
    WatchCities.ALL.groupBy { it.offsetMinutes }.map { (offsetMinutes, cities) ->
        CityLine(
            offset = utcOffsetLabel(offsetMinutes),
            cities = cities.joinToString(" · ") { it.label(titleCase(it.name)) },
        )
    }
}
