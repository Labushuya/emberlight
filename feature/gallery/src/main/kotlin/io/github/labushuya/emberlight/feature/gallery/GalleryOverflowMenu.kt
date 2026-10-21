package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

/**
 * Überlaufmenü der Galerie-Leiste. Hier liegt der Weg zu „Über & Lizenzen“ (ADR 0020). Die Galerie hat sonst keine Aktionen
 * in der Leiste, deshalb reicht ein einzelner Eintrag.
 */
@Composable
internal fun GalleryOverflowMenu(onAbout: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.feature_gallery_menu_more),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.feature_gallery_menu_about)) },
                onClick = {
                    expanded = false
                    onAbout()
                },
            )
        }
    }
}
