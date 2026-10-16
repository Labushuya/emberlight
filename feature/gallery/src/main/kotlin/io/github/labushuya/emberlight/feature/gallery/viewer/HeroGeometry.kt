package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/*
 * Geometrie der Hero-Animation (ADR 0010). Reine Funktionen ohne Android: Ziel- und Quellrechteck, Zuschnitt und die
 * Interpolation zwischen Kachel und Vollbild. Die Zeichnung setzt diese Rechtecke mit `drawImage` um.
 */

/** Linear zwischen [a] und [b]. [t] ist nicht begrenzt, der Aufrufer begrenzt. */
internal fun lerpFloat(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

/** Linear zwischen zwei Rechtecken, Kante für Kante. */
internal fun lerpRect(
    a: Rect,
    b: Rect,
    t: Float,
): Rect =
    Rect(
        left = lerpFloat(a.left, b.left, t),
        top = lerpFloat(a.top, b.top, t),
        right = lerpFloat(a.right, b.right, t),
        bottom = lerpFloat(a.bottom, b.bottom, t),
    )

/**
 * Größtes Rechteck mit dem Seitenverhältnis [aspect] (Breite durch Höhe), zentriert in [bounds]. Ist das Verhältnis
 * ungültig, füllt das Rechteck [bounds] aus. Das ist dieselbe Fläche, die die Vollbildansicht für `Fit` nutzt.
 */
internal fun fitRect(
    bounds: Rect,
    aspect: Float,
): Rect {
    if (!aspect.isFinite() || aspect <= 0f || bounds.width <= 0f || bounds.height <= 0f) return bounds
    val boundsAspect = bounds.width / bounds.height
    val size =
        if (aspect >= boundsAspect) {
            Offset(bounds.width, bounds.width / aspect)
        } else {
            Offset(bounds.height * aspect, bounds.height)
        }
    val center = bounds.center
    return centeredRect(center, size.x / 2f, size.y / 2f)
}

/**
 * Zentrierter Zuschnitt eines Bildes der Größe [imageWidth] mal [imageHeight] auf das Seitenverhältnis [targetAspect]. Das
 * Ergebnis liegt in Bildpixeln. Es entspricht dem Zuschnitt `ContentScale.Crop` der Kachel.
 */
internal fun cropSourceRect(
    imageWidth: Int,
    imageHeight: Int,
    targetAspect: Float,
): Rect {
    val w = imageWidth.toFloat()
    val h = imageHeight.toFloat()
    if (!targetAspect.isFinite() || targetAspect <= 0f || w <= 0f || h <= 0f) return Rect(0f, 0f, w, h)
    val imageAspect = w / h
    return if (imageAspect > targetAspect) {
        val cropWidth = h * targetAspect
        val left = (w - cropWidth) / 2f
        Rect(left, 0f, left + cropWidth, h)
    } else {
        val cropHeight = w / targetAspect
        val top = (h - cropHeight) / 2f
        Rect(0f, top, w, top + cropHeight)
    }
}

/** Ein Zeichenschritt der Hero-Animation: Ziel auf dem Bildschirm und Ausschnitt im Bild. */
internal data class HeroFrame(
    val dst: Rect,
    val src: Rect,
)

/**
 * Stand der Hero-Animation bei [t] (0 = Kachel, 1 = Vollbild). Das Ziel wächst von der Kachel zum Fit-Rechteck, der Ausschnitt
 * wandert vom Zuschnitt der Kachel zum vollen Bild. Beides linear in [t], also ohne Verzerrung und ohne Sprung.
 */
internal fun heroFrame(
    t: Float,
    tile: Rect,
    fit: Rect,
    crop: Rect,
    full: Rect,
): HeroFrame {
    val k = t.coerceIn(0f, 1f)
    return HeroFrame(dst = lerpRect(tile, fit, k), src = lerpRect(crop, full, k))
}

/**
 * Wendet Zieh- bzw. Rückgeste auf ein Rechteck an: Skalierung um [pivot] und danach Verschiebung um [dy]. [scale] ist 1 ohne
 * Geste.
 */
internal fun transformRect(
    rect: Rect,
    pivot: Offset,
    scale: Float,
    dy: Float,
): Rect {
    val c = rect.center
    val center = Offset(pivot.x + (c.x - pivot.x) * scale, pivot.y + (c.y - pivot.y) * scale + dy)
    return centeredRect(center, rect.width * scale / 2f, rect.height * scale / 2f)
}

/**
 * Ersatz für eine fehlende Kachel: ein zentriertes Rechteck, [factor] mal so groß wie das Fit-Rechteck. Der Übergang
 * verkleinert dann zur Mitte und blendet aus.
 */
internal fun fallbackTile(
    fit: Rect,
    factor: Float = FALLBACK_TILE_FACTOR,
): Rect = centeredRect(fit.center, fit.width * factor / 2f, fit.height * factor / 2f)

/** Größe des Ersatzrechtecks ohne Kachel, relativ zum Fit-Rechteck. */
internal const val FALLBACK_TILE_FACTOR = 0.5f

/** Rechteck mit Mittelpunkt [center] und halben Kantenlängen. */
internal fun centeredRect(
    center: Offset,
    halfWidth: Float,
    halfHeight: Float,
): Rect = Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
