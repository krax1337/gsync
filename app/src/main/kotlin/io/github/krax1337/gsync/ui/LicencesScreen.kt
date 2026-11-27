package io.github.krax1337.gsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The notices in the APK's assets, in the order they are shown: a heading and the file under
 * `notices/`. The build copies the files from the repository (`BundleNotices` in build.gradle.kts).
 */
private val NOTICES = listOf(
    "G-Sync" to "NOTICE",
    "GShockAPI: MIT License" to "GShockAPI-LICENSE.txt",
    "DSEG font: SIL Open Font License 1.1" to "DSEG-LICENSE.txt",
    "G-Sync and its libraries: Apache License 2.0" to "LICENSE",
)

private val BLANK_LINES = Regex("""\n\s*\n""")
private val LINE_BREAK = Regex("""\s*\n\s*""")

/** The licences of G-Sync and of the work inside it. They have to travel with every copy of the app. */
@Composable
internal fun LicencesScreen(padding: PaddingValues, onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val notices = remember(assets) {
        NOTICES.map { (title, file) ->
            title to paragraphs(assets.open("notices/$file").bufferedReader().use { it.readText() })
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        PageTopBar(title = "Licences", onBack = onBack)
        ScreenColumn(PaddingValues(0.dp)) {
            notices.forEachIndexed { index, (title, paragraphs) ->
                if (index > 0) Gap()
                Section(title) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            paragraphs.forEach { paragraph ->
                                Text(
                                    text = paragraph,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [text] as paragraphs that wrap to the screen: the line breaks a text file has inside a paragraph
 * are taken out, and so are its ruled lines.
 */
private fun paragraphs(text: String): List<String> =
    text.lineSequence()
        .filterNot { line -> line.isNotBlank() && line.all { it == '-' || it == ' ' } }
        .joinToString("\n")
        .split(BLANK_LINES)
        .map { it.trim().replace(LINE_BREAK, " ") }
        .filter { it.isNotEmpty() }
