package io.github.labushuya.emberlight.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.labushuya.emberlight.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Quellcode des Projekts. Der Link öffnet sich im Systembrowser (ADR 0020). */
internal const val SOURCE_URL = "https://github.com/Labushuya/emberlight"

/** Offizieller Text der GPL-3.0 im Netz, falls der Volltext in der App nicht genügt. */
internal const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/**
 * Bildschirm „Über & Lizenzen“ (ADR 0020): Version, Lizenz der App, Quellcode-Link, Bibliotheken mit Lizenz, Schriften
 * und GPL. Die Inhalte kommen aus Ressourcen und Assets, siehe [loadAboutContent]. Mit [content] läuft der Bildschirm
 * ohne Laden, etwa in Tests und Goldens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: AboutContent? = null,
) {
    val context = LocalContext.current
    val loaded by produceState(initialValue = content, content, context) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadAboutContent(context) }
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.about_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val data = loaded
        if (data == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AboutBody(content = data, padding = padding, context = context)
        }
    }
}

@Composable
private fun AboutBody(
    content: AboutContent,
    padding: PaddingValues,
    context: Context,
) {
    var libraryShown by remember { mutableStateOf<LibraryEntry?>(null) }
    var documentShown by remember { mutableStateOf<LicenseDocument?>(null) }

    LazyColumn(contentPadding = padding) {
        item { AppHeader(versionName = content.versionName) }
        item {
            OutlinedButton(
                onClick = { openUrl(context, SOURCE_URL) },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.about_source_button))
            }
        }
        item { SectionHeading(text = stringResource(R.string.about_libraries_title)) }
        when (val libraries = content.libraries) {
            is LibraryList.Loaded -> {
                item { BodyText(R.string.about_libraries_intro) }
                items(libraries.libraries) { library ->
                    LibraryRow(library = library, onClick = { libraryShown = library })
                }
            }

            LibraryList.NotIncluded -> {
                item { BodyText(R.string.about_libraries_not_included) }
            }

            LibraryList.Unreadable -> {
                item { BodyText(R.string.about_libraries_unreadable) }
            }
        }
        item { SectionHeading(text = stringResource(R.string.about_fonts_title)) }
        items(content.fonts) { font ->
            DocumentRow(
                title = font.title,
                subtitle = stringResource(R.string.about_font_license),
                onClick = { documentShown = font },
            )
        }
        item { SectionHeading(text = stringResource(R.string.about_license_title)) }
        item { BodyText(R.string.about_gpl_summary) }
        item {
            OutlinedButton(
                onClick = { documentShown = content.gpl },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.about_gpl_show))
            }
        }
        item {
            OutlinedButton(
                onClick = { openUrl(context, GPL_URL) },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.about_gpl_link))
            }
        }
    }

    libraryShown?.let { library ->
        LibraryDialog(library = library, onDismiss = { libraryShown = null })
    }
    documentShown?.let { document ->
        TextDialog(
            title = document.title,
            text = document.text ?: stringResource(R.string.about_license_text_missing),
            onDismiss = { documentShown = null },
        )
    }
}

@Composable
private fun AppHeader(versionName: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (versionName.isNotEmpty()) {
            Text(
                text = stringResource(R.string.about_version, versionName),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = stringResource(R.string.about_free_software),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun BodyText(resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun LibraryRow(
    library: LibraryEntry,
    onClick: () -> Unit,
) {
    // Ohne Lizenzangabe steht das sichtbar da, statt eine leere Stelle zu lassen (ADR 0020).
    val licenseNames =
        library.licenses
            .joinToString(
                separator = ", ",
            ) { it.name }
            .ifEmpty { stringResource(R.string.about_license_unknown) }
    val detail = stringResource(R.string.about_library_detail, library.version, licenseNames)
    val showLabel = stringResource(R.string.about_library_show_license)
    ListItem(
        headlineContent = { Text(text = library.name) },
        supportingContent = { Text(text = detail) },
        modifier =
            Modifier.clickable(
                role = Role.Button,
                onClickLabel = showLabel,
                onClick = onClick,
            ),
    )
}

@Composable
private fun DocumentRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val showLabel = stringResource(R.string.about_library_show_license)
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = { Text(text = subtitle) },
        modifier =
            Modifier.clickable(
                role = Role.Button,
                onClickLabel = showLabel,
                onClick = onClick,
            ),
    )
}

@Composable
private fun LibraryDialog(
    library: LibraryEntry,
    onDismiss: () -> Unit,
) {
    val missing = stringResource(R.string.about_license_text_missing)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.about_close)) }
        },
        title = { Text(text = library.name, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.about_library_version, library.version),
                    style = MaterialTheme.typography.bodyMedium,
                )
                library.licenses.forEach { license ->
                    Text(
                        text = license.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = license.text ?: missing,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun TextDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.about_close)) }
        },
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text = text, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/** Öffnet einen Link im Systembrowser. Ohne passende App erscheint ein Hinweis statt eines Absturzes. */
internal fun openUrl(
    context: Context,
    url: String,
) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
    }
}
