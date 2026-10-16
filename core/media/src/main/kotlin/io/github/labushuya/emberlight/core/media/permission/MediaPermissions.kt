package io.github.labushuya.emberlight.core.media.permission

import io.github.labushuya.emberlight.core.media.model.MediaType

/**
 * Berechtigungsnamen als Strings. Die Konstanten in `android.Manifest` sind erst ab API 33/34 deklariert und
 * würden bei minSdk 26 die Lint-Prüfung NewApi auslösen.
 */
internal object MediaPermissionNames {
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
}

private const val API_MEDIA_PERMISSIONS = 33
private const val API_PARTIAL_ACCESS = 34

/**
 * Berechtigungen, die die App für den Medienzugriff anfordert, je nach SDK-Level.
 *
 * - API 26 bis 32: `READ_EXTERNAL_STORAGE`.
 * - API 33: `READ_MEDIA_IMAGES` und `READ_MEDIA_VIDEO`.
 * - API 34 und höher: zusätzlich `READ_MEDIA_VISUAL_USER_SELECTED` für „ausgewählte Fotos“.
 *
 * Quelle: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
 */
fun requiredPermissions(sdkInt: Int): List<String> =
    when {
        sdkInt >= API_PARTIAL_ACCESS -> {
            listOf(
                MediaPermissionNames.READ_MEDIA_IMAGES,
                MediaPermissionNames.READ_MEDIA_VIDEO,
                MediaPermissionNames.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        }

        sdkInt >= API_MEDIA_PERMISSIONS -> {
            listOf(
                MediaPermissionNames.READ_MEDIA_IMAGES,
                MediaPermissionNames.READ_MEDIA_VIDEO,
            )
        }

        else -> {
            listOf(MediaPermissionNames.READ_EXTERNAL_STORAGE)
        }
    }

/**
 * Leitet aus dem SDK-Level und den gewährten Berechtigungen den Medienzugriff ab.
 *
 * - API 33 und höher, `READ_MEDIA_IMAGES` oder `READ_MEDIA_VIDEO` gewährt: [MediaAccess.FULL].
 *   Die App fordert immer beide an. Gewährt der Nutzer nur einen der beiden Typen, gilt das hier trotzdem als FULL.
 *   Der fehlende Typ fehlt dann still in der Bibliothek. Die UI muss das später erkennen und nachfragen (offener Punkt).
 * - API 34 und höher, nur `READ_MEDIA_VISUAL_USER_SELECTED`: [MediaAccess.PARTIAL].
 * - API 32 und darunter, `READ_EXTERNAL_STORAGE` gewährt: [MediaAccess.FULL].
 * - Sonst: [MediaAccess.NONE]. Auf API 33 und höher hat `READ_EXTERNAL_STORAGE` keine Wirkung.
 */
fun mediaAccess(
    sdkInt: Int,
    granted: Set<String>,
): MediaAccess =
    when {
        sdkInt >= API_MEDIA_PERMISSIONS &&
            (
                MediaPermissionNames.READ_MEDIA_IMAGES in granted ||
                    MediaPermissionNames.READ_MEDIA_VIDEO in granted
            ) -> {
            MediaAccess.FULL
        }

        sdkInt >= API_PARTIAL_ACCESS && MediaPermissionNames.READ_MEDIA_VISUAL_USER_SELECTED in granted -> {
            MediaAccess.PARTIAL
        }

        sdkInt < API_MEDIA_PERMISSIONS && MediaPermissionNames.READ_EXTERNAL_STORAGE in granted -> {
            MediaAccess.FULL
        }

        else -> {
            MediaAccess.NONE
        }
    }

/**
 * Medientypen, deren Berechtigung fehlt, obwohl der Zugriff insgesamt [MediaAccess.FULL] ist (ADR 0007).
 *
 * Ab API 33 fordert die App `READ_MEDIA_IMAGES` und `READ_MEDIA_VIDEO` getrennt an. Gewährt der Nutzer nur einen
 * der beiden, ist der andere Typ leer, ohne dass ein Fehler auftritt. Leer ist die Menge in allen anderen Fällen:
 * vor API 33, bei [MediaAccess.PARTIAL] (dort sind beide Typen über die Auswahl abgedeckt) und bei [MediaAccess.NONE].
 */
fun missingMediaTypes(
    sdkInt: Int,
    granted: Set<String>,
): Set<MediaType> {
    if (sdkInt < API_MEDIA_PERMISSIONS || mediaAccess(sdkInt, granted) != MediaAccess.FULL) return emptySet()
    return buildSet {
        if (MediaPermissionNames.READ_MEDIA_IMAGES !in granted) add(MediaType.IMAGE)
        if (MediaPermissionNames.READ_MEDIA_VIDEO !in granted) add(MediaType.VIDEO)
    }
}
