package io.github.labushuya.emberlight

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.labushuya.emberlight.feature.gallery.GalleryRoute
import io.github.labushuya.emberlight.feature.gallery.album.AlbumRoute
import io.github.labushuya.emberlight.feature.space.SpaceDetailRoute
import io.github.labushuya.emberlight.feature.space.SpaceEditRoute
import io.github.labushuya.emberlight.feature.space.SpaceRunningRoute
import io.github.labushuya.emberlight.feature.space.SpaceSetupRoute
import io.github.labushuya.emberlight.feature.space.SpacesRoute
import kotlin.reflect.KClass
import io.github.labushuya.emberlight.core.design.R as DesignR

/**
 * Die beiden Ziele in der Navigationsleiste. Die Reihenfolge bestimmt die Anzeige, Galerie ist Start. Zu einem Ziel gehören
 * alle Routen, die darunter liegen: Die Album-Ansicht ist Teil der Galerie und hält deren Eintrag markiert.
 */
enum class TopLevelDestination(
    @param:DrawableRes val selectedIcon: Int,
    @param:DrawableRes val unselectedIcon: Int,
    @param:StringRes val labelRes: Int,
    val routes: List<KClass<*>>,
) {
    GALLERY(
        selectedIcon = DesignR.drawable.ic_photo_library_filled,
        unselectedIcon = DesignR.drawable.ic_photo_library_outlined,
        labelRes = R.string.app_nav_gallery,
        routes = listOf(GalleryRoute::class, AlbumRoute::class),
    ),
    SPACES(
        selectedIcon = DesignR.drawable.ic_groups_filled,
        unselectedIcon = DesignR.drawable.ic_groups_outlined,
        labelRes = R.string.app_nav_spaces,
        // Detail, Formular, Einrichtung und laufender Space gehören zum Ziel Spaces: Die Leiste bleibt dort markiert.
        routes =
            listOf(
                SpacesRoute::class,
                SpaceDetailRoute::class,
                SpaceEditRoute::class,
                SpaceSetupRoute::class,
                SpaceRunningRoute::class,
            ),
    ),
}
