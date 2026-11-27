package io.github.krax1337.gsync.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.protocol.Setting
import io.github.krax1337.gsync.protocol.WatchProfile
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.update.AppUpdate
import io.github.krax1337.gsync.update.UpdateStep
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState
import kotlin.math.abs
import kotlin.math.roundToInt

private const val FINE_ADJUST_LIMIT_MS = 5000

private enum class SettingsDialog { MINUTE, FINE_ADJUST, LANGUAGE, UNPAIR, UPDATE }

/** The settings list, or the page that [helpPage] names in its place. */
@Composable
internal fun SettingsScreen(
    state: WatchState,
    watch: Watch,
    profile: WatchProfile,
    actions: WatchActions,
    padding: PaddingValues,
    helpPage: HelpPage?,
    onHelpPageChange: (HelpPage?) -> Unit,
) {
    // Held here, so the list is where it was left when the page closes.
    val listScroll = rememberScrollState()
    val close = { onHelpPageChange(null) }
    // The guide can still be open from before another watch was put on screen.
    val open = helpPage.takeUnless { it == HelpPage.GUIDE && !profile.hasGuide }
    BackHandler(enabled = open != null, onBack = close)
    AnimatedContent(
        targetState = open,
        transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_MS)) },
        label = "help page",
    ) { page ->
        when (page) {
            HelpPage.GUIDE -> GuideScreen(padding = padding, homeCity = state.homeCity, onBack = close)
            HelpPage.DIAGNOSTICS ->
                DiagnosticsScreen(padding = padding, diagnostics = state.diagnostics, onBack = close)
            HelpPage.LICENCES -> LicencesScreen(padding = padding, onBack = close)
            null -> SettingsList(state, watch, profile, actions, padding, listScroll, onOpenHelp = onHelpPageChange)
        }
    }
}

@Composable
private fun SettingsList(
    state: WatchState,
    watch: Watch,
    profile: WatchProfile,
    actions: WatchActions,
    padding: PaddingValues,
    scrollState: ScrollState,
    onOpenHelp: (HelpPage) -> Unit,
) {
    val settings = state.settings
    val autoSync = state.autoSync
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    ScreenColumn(padding, scrollState) {
        ScreenTitle("Settings")
        if (settings == null || (profile.hasAutoSync && autoSync == null)) {
            Gap()
            EmptyCard(
                title = "Connect the watch once to load its settings",
                text = "Hold the lower-left button on the watch for about 3 seconds, until CONNECT appears.",
            )
        }

        Gap()
        Section("Help") {
            if (profile.hasGuide) {
                PageRow(
                    title = "Watch guide",
                    supporting = "Buttons, cities, travelling",
                    onClick = { onOpenHelp(HelpPage.GUIDE) },
                )
                RowDivider()
            }
            PageRow(
                title = "Diagnostics",
                supporting = "Raw data from the last sync, for bug reports",
                onClick = { onOpenHelp(HelpPage.DIAGNOSTICS) },
            )
            RowDivider()
            PageRow(
                title = "Licences",
                supporting = "G-Sync and the open-source work inside it",
                onClick = { onOpenHelp(HelpPage.LICENCES) },
            )
        }

        Gap()
        Section("Time sync") {
            if (profile.hasAutoSync && autoSync != null) {
                SwitchRow(
                    title = "Automatic adjustment",
                    supporting = "The watch connects four times a day",
                    checked = autoSync.enabled,
                    onCheckedChange = { actions.setAutoSync(autoSync.copy(enabled = it)) },
                )
                RowDivider()
                Reveal(visible = autoSync.enabled, gap = 0.dp) {
                    Column {
                        SettingRow(
                            title = "Minute past the hour",
                            onClick = { dialog = SettingsDialog.MINUTE },
                            trailing = { ValueText(minuteLabel(autoSync.minute)) },
                        )
                        RowDivider()
                    }
                }
            }
            SettingRow(
                title = "Fine adjustment",
                supporting = "Added to the time sent to the watch",
                onClick = { dialog = SettingsDialog.FINE_ADJUST },
                trailing = { ValueText(fineAdjustLabel(state.fineAdjustMs)) },
            )
        }

        if (settings != null) {
            WatchSettingSections(
                settings = settings,
                profile = profile,
                onChange = actions::setSettings,
                onPickLanguage = { dialog = SettingsDialog.LANGUAGE },
            )
        }

        Gap()
        Section("Phone") {
            if (profile.hasPhoneFinder) {
                SwitchRow(
                    title = "Phone finder",
                    supporting = "Ring this phone when you hold the lower-right button on the watch for 5 seconds",
                    checked = state.phoneFinderEnabled,
                    onCheckedChange = actions::setPhoneFinderEnabled,
                )
                RowDivider()
            }
            BatteryOptimisationRow()
        }

        Gap()
        Section("App") {
            UpdateRow(
                update = state.update,
                onCheck = actions::checkForUpdate,
                onShow = { dialog = SettingsDialog.UPDATE },
            )
        }

        Gap()
        Section("Watch") {
            SettingRow(title = watch.name, supporting = watch.address)
            RowDivider()
            SettingRow(
                title = "Unpair",
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { dialog = SettingsDialog.UNPAIR },
            )
        }
    }

    val dismiss = { dialog = null }
    when (dialog) {
        SettingsDialog.UPDATE -> {
            val step = state.update.step
            if (step is UpdateStep.Available) {
                UpdateDialog(
                    update = step,
                    onConfirm = {
                        dismiss()
                        actions.installUpdate()
                    },
                    onDismiss = dismiss,
                )
            }
        }
        SettingsDialog.MINUTE -> if (autoSync != null) {
            MinuteDialog(
                initial = autoSync.minute,
                onConfirm = {
                    actions.setAutoSync(autoSync.copy(minute = it))
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }
        SettingsDialog.FINE_ADJUST -> FineAdjustDialog(
            initial = state.fineAdjustMs,
            onConfirm = {
                actions.setFineAdjust(it)
                dismiss()
            },
            onDismiss = dismiss,
        )
        SettingsDialog.LANGUAGE -> if (settings != null) {
            LanguageDialog(
                selected = settings.language,
                onSelect = {
                    actions.setSettings(settings.copy(language = it))
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }
        SettingsDialog.UNPAIR -> UnpairDialog(
            // With several watches the model alone may not say which one.
            watch = if (state.watches.size > 1) "${watch.model} (${watch.addressTail})" else watch.model,
            onConfirm = {
                dismiss()
                actions.unpair()
            },
            onDismiss = dismiss,
        )
        null -> Unit
    }
}

/** The settings of the watch itself, each shown only on a model that has it. */
@Composable
private fun WatchSettingSections(
    settings: WatchSettings,
    profile: WatchProfile,
    onChange: (WatchSettings) -> Unit,
    onPickLanguage: () -> Unit,
) {
    val shown = profile.settings
    RowSection(
        title = "Display",
        rows = buildList<@Composable () -> Unit> {
            if (Setting.TIME_FORMAT in shown) {
                add {
                    SwitchRow(
                        title = "24-hour time",
                        checked = settings.use24Hour,
                        onCheckedChange = { onChange(settings.copy(use24Hour = it)) },
                    )
                }
            }
            if (Setting.DIGITS in shown) {
                add {
                    ChoiceRow(
                        title = "Digits",
                        supporting = "Classic imitates a seven-segment display",
                        options = listOf("Standard", "Classic"),
                        selected = if (settings.classicFont) 1 else 0,
                        onSelect = { onChange(settings.copy(classicFont = it == 1)) },
                    )
                }
            }
            if (Setting.DATE_FORMAT in shown) {
                add {
                    ChoiceRow(
                        title = "Date format",
                        options = listOf("Day.Month", "Month.Day"),
                        selected = if (settings.dayFirst) 0 else 1,
                        onSelect = { onChange(settings.copy(dayFirst = it == 0)) },
                    )
                }
            }
            if (Setting.LANGUAGE in shown) {
                add {
                    SettingRow(
                        title = "Weekday language",
                        onClick = onPickLanguage,
                        trailing = { ValueText(settings.language.label) },
                    )
                }
            }
        },
    )
    RowSection(
        title = "Light",
        rows = buildList<@Composable () -> Unit> {
            if (Setting.AUTO_LIGHT in shown) {
                add {
                    SwitchRow(
                        title = "Auto light",
                        supporting = "Lights up when you tilt your wrist in the dark",
                        checked = settings.autoLight,
                        onCheckedChange = { onChange(settings.copy(autoLight = it)) },
                    )
                }
            }
            if (Setting.LIGHT_DURATION in shown) {
                add {
                    ChoiceRow(
                        title = "Illumination",
                        options = listOf(profile.shortLight, profile.longLight),
                        selected = if (settings.longLight) 1 else 0,
                        onSelect = { onChange(settings.copy(longLight = it == 1)) },
                    )
                }
            }
        },
    )
    RowSection(
        title = "Sound and power",
        rows = buildList<@Composable () -> Unit> {
            if (Setting.BUTTON_TONE in shown) {
                add {
                    SwitchRow(
                        title = "Button tone",
                        checked = settings.buttonTone,
                        onCheckedChange = { onChange(settings.copy(buttonTone = it)) },
                    )
                }
            }
            if (Setting.POWER_SAVING in shown) {
                add {
                    SwitchRow(
                        title = "Power saving",
                        supporting = "The display sleeps while the watch is left in the dark",
                        checked = settings.powerSaving,
                        onCheckedChange = { onChange(settings.copy(powerSaving = it)) },
                    )
                }
            }
        },
    )
}

/** A [Section] of [rows] with dividers between them. Without rows it is not there at all, heading included. */
@Composable
private fun RowSection(title: String, rows: List<@Composable () -> Unit>) {
    if (rows.isEmpty()) return
    Gap()
    Section(title) {
        rows.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            row()
        }
    }
}

/** A row that opens a page. */
@Composable
private fun PageRow(title: String, supporting: String, onClick: () -> Unit) {
    SettingRow(
        title = title,
        supporting = supporting,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** Title with a full-width segmented control under it; side by side they do not fit 360dp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    supporting: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selected,
                    onClick = {
                        if (index != selected) {
                            haptics.tick()
                            onSelect(index)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.weight(1f),
                    label = { Text(text = label, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimisationRow() {
    val context = LocalContext.current
    // The preview renderer has no power service to ask.
    val canQuery = !LocalInspectionMode.current
    var exempt by remember { mutableStateOf(canQuery && context.ignoresBatteryOptimisation()) }
    LifecycleResumeEffect(context) {
        if (canQuery) exempt = context.ignoresBatteryOptimisation()
        onPauseOrDispose { }
    }
    SettingRow(
        title = "Battery optimisation",
        supporting = if (exempt) "Unrestricted" else "Restricted: background sync can be delayed",
        onClick = { context.startActivity(batteryOptimisationIntent(context, exempt)) },
    )
}

private fun Context.ignoresBatteryOptimisation(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/**
 * The system prompt to exempt the app. Once exempt that prompt closes itself at once, so the
 * row then opens the system list instead, where the exemption can be reviewed.
 */
@SuppressLint("BatteryLife")
private fun batteryOptimisationIntent(context: Context, exempt: Boolean): Intent =
    if (exempt) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    }

@Composable
private fun MinuteDialog(initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val slider = rememberSliderState(value = initial.toFloat(), trackRange = 0f..59f)
    val minute = slider.value.roundToInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minute past the hour") },
        text = {
            Column {
                Text("The watch connects four times a day, this many minutes past the hour.")
                Spacer(Modifier.height(16.dp))
                Text(
                    text = minuteLabel(minute),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Slider(state = slider, onValueChange = { slider.value = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.confirm()
                    onConfirm(minute)
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FineAdjustDialog(initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var text by rememberSaveable { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()?.takeIf { abs(it) <= FINE_ADJUST_LIMIT_MS }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fine adjustment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Added to the time sent to the watch. A positive value sets the watch ahead.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        // Sign and four digits at most; the range itself is checked on the parsed value.
                        if (input.length <= 5 && input.all { it.isDigit() || it == '-' }) text = input
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = value == null && text.isNotEmpty() && text != "-",
                    suffix = { Text("ms") },
                    supportingText = { Text("From −$FINE_ADJUST_LIMIT_MS to $FINE_ADJUST_LIMIT_MS") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (value != null) {
                        haptics.confirm()
                        onConfirm(value)
                    }
                },
                enabled = value != null,
            ) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LanguageDialog(selected: Language, onSelect: (Language) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weekday language") },
        text = {
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Language.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = language == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    haptics.confirm()
                                    onSelect(language)
                                },
                            )
                            .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = language.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnpairDialog(watch: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unpair this watch?") },
        text = {
            Text("G-Sync stops syncing the $watch until you pair it again. Alarms and settings stay on the watch.")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.confirm()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Unpair")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "English" for [Language.ENGLISH]. */
private val Language.label: String
    get() = name.lowercase().replaceFirstChar { it.titlecase() }

/** "+120 ms", "−40 ms", "0 ms". */
private fun fineAdjustLabel(ms: Int): String =
    when {
        ms > 0 -> "+$ms ms"
        ms < 0 -> "−${-ms} ms"
        else -> "0 ms"
    }

/** The version of the app. Offers an update once GitHub has a newer one; a tap looks for one. */
@Composable
private fun UpdateRow(update: AppUpdate, onCheck: () -> Unit, onShow: () -> Unit) {
    val version = "Version ${update.installed}"
    when (val step = update.step) {
        UpdateStep.Idle -> SettingRow(title = version, supporting = "Tap to look for a newer version", onClick = onCheck)
        UpdateStep.Checking -> SettingRow(title = version, supporting = "Looking for a newer version…")
        UpdateStep.UpToDate -> SettingRow(title = version, supporting = "This is the latest version", onClick = onCheck)
        is UpdateStep.Available -> SettingRow(
            title = "Version ${step.version} is available",
            supporting = "You have ${update.installed}. Tap to see what is new and to update",
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = onShow,
        )
        is UpdateStep.Downloading -> SettingRow(title = version, supporting = "Downloading ${step.version}: ${step.percent}%")
        is UpdateStep.Confirming -> SettingRow(
            title = version,
            supporting = "Android asks you to confirm the update to ${step.version}",
            onClick = onCheck,
        )
        is UpdateStep.Failed -> SettingRow(title = version, supporting = "${step.message} Tap to try again", onClick = onCheck)
    }
}

@Composable
private fun UpdateDialog(update: UpdateStep.Available, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Version ${update.version}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (update.notes.isNotEmpty()) Text(update.notes)
                Text(
                    text = "G-Sync downloads it from GitHub and checks that it is signed with the same key as " +
                        "this app. Android then asks you to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.confirm()
                    onConfirm()
                },
            ) {
                Text("Download and install")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
