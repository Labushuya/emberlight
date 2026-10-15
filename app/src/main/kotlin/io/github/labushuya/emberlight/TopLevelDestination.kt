package io.github.labushuya.emberlight

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.labushuya.emberlight.feature.gallery.GalleryRoute
import io.github.labushuya.emberlight.feature.space.SpacesRoute
import kotlin.reflect.KClass
import io.github.labushuya.emberlight.core.design.R as DesignR

/** Die beiden Ziele in der Navigationsleiste. Die Reihenfolge bestimmt die Anzeige, Galerie ist Start. */
enum class TopLevelDestination(
    @param:DrawableRes val selectedIcon: Int,
    @param:DrawableRes val unselectedIcon: Int,
    @param:StringRes val labelRes: Int,
    val route: KClass<*>,
) {
    GALLERY(
        selectedIcon = DesignR.drawable.ic_photo_library_filled,
        unselectedIcon = DesignR.drawable.ic_photo_library_outlined,
        labelRes = R.string.app_nav_gallery,
        route = GalleryRoute::class,
    ),
    SPACES(
        selectedIcon = DesignR.drawable.ic_groups_filled,
        unselectedIcon = DesignR.drawable.ic_groups_outlined,
        labelRes = R.string.app_nav_spaces,
        route = SpacesRoute::class,
    ),
}
