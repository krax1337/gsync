package io.github.krax1337.gsync.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What the watch sent at the last sync, as text to copy into a bug report. */
@Composable
internal fun DiagnosticsScreen(padding: PaddingValues, diagnostics: String?, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        PageTopBar(title = "Diagnostics", onBack = onBack)
        ScreenColumn(PaddingValues(0.dp)) {
            Text(
                text = "How the last sync was timed and the clock tables the watch sent. Attach this to a " +
                    "bug report. It includes the coordinates of the home city.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap()
            if (diagnostics == null) {
                EmptyCard(title = "Nothing yet", text = "Sync the watch once.")
            } else {
                Panel {
                    SelectionContainer {
                        Text(
                            text = diagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Gap()
                CopyButton(diagnostics)
            }
        }
    }
}

/** Puts [text] on the clipboard. The system shows its own confirmation, so the app adds none. */
@Composable
private fun CopyButton(text: String) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            haptics.confirm()
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("G-Sync diagnostics", text)))
            }
        },
    ) {
        Text("Copy")
    }
}
