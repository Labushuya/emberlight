package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.labushuya.emberlight.core.design.components.EmberlightPlaceholder
import io.github.labushuya.emberlight.core.design.R as DesignR

/** Platzhalter, bis die Galerie in Meilenstein 2 gebaut wird. */
@Composable
internal fun GalleryScreen(modifier: Modifier = Modifier) {
    EmberlightPlaceholder(
        icon = DesignR.drawable.ic_photo_library_filled,
        title = stringResource(R.string.feature_gallery_title),
        message = stringResource(R.string.feature_gallery_text),
        modifier = modifier,
    )
}
