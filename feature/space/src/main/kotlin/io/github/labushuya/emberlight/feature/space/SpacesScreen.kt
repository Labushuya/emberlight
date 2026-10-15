package io.github.labushuya.emberlight.feature.space

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.labushuya.emberlight.core.design.components.EmberlightPlaceholder
import io.github.labushuya.emberlight.core.design.R as DesignR

/** Platzhalter, bis Spaces in Meilenstein 3 gebaut werden. */
@Composable
internal fun SpacesScreen(modifier: Modifier = Modifier) {
    EmberlightPlaceholder(
        icon = DesignR.drawable.ic_groups_filled,
        title = stringResource(R.string.feature_space_title),
        message = stringResource(R.string.feature_space_text),
        modifier = modifier,
    )
}
