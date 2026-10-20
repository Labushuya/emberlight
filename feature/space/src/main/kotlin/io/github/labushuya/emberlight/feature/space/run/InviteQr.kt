package io.github.labushuya.emberlight.feature.space.run

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.labushuya.emberlight.feature.space.R

/**
 * Adresse des Beitritts (ADR 0018): `http://<ip>:<port>/#i=<einladung>`. Das Geheimnis steht nur im Fragment, und der
 * Browser schickt das Fragment nie an den Server.
 */
internal object InviteLink {
    fun of(
        ipv4: String,
        port: Int,
        secret: String,
    ): String = "http://$ipv4:$port/#i=$secret"
}

/** QR-Code für den Beitritt: Fehlerkorrektur M, Ruhezone von vier Modulen (ADR 0018). */
internal object InviteQr {
    /** Ruhezone in Modulen, nach QR-Standard vier. */
    const val QUIET_ZONE_MODULES = 4

    /** Ungefähre Kantenlänge des Bildes in Pixeln. Die Anzeige skaliert auf die Breite, die Bitmap bleibt scharf. */
    private const val TARGET_PIXELS = 1024

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Matrix mit Ruhezone. Die Kantenlänge ist die Modulzahl plus Ruhezone. */
    fun matrix(content: String): BitMatrix {
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** Bitmap mit ganzzahligen Modulgrößen, damit keine Kanten verschwimmen. */
    fun bitmap(content: String): Bitmap {
        val matrix = matrix(content)
        val size = matrix.width
        val scale = (TARGET_PIXELS / size).coerceAtLeast(1)
        val pixelSide = size * scale
        val pixels = IntArray(pixelSide * pixelSide)
        for (y in 0 until pixelSide) {
            for (x in 0 until pixelSide) {
                pixels[y * pixelSide + x] = if (matrix.get(x / scale, y / scale)) BLACK else WHITE
            }
        }
        return Bitmap.createBitmap(pixels, pixelSide, pixelSide, Bitmap.Config.ARGB_8888)
    }
}

/**
 * QR-Code des laufenden Space. Die Breite ist mindestens 70 % der verfügbaren Breite (ADR 0018). Die Bitmap wird je
 * Inhalt einmal erzeugt und dann wiederverwendet.
 */
@Composable
internal fun InviteQrImage(
    content: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { InviteQr.bitmap(content).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.space_invite_qr_description),
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(1f),
    )
}

/**
 * Hält den Bildschirm an, solange der QR-Code steht: Bildschirm bleibt an, Helligkeit auf Maximum. Beim Verlassen wird alles
 * auf den vorherigen Stand gesetzt (ADR 0018).
 */
@Composable
internal fun KeepScreenBrightWhileShown() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            val previousBrightness = window.attributes.screenBrightness
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes =
                window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            onDispose {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
            }
        }
    }
}

/** Die Aktivität, zu der dieser Kontext gehört. Ohne Aktivität keine Fensterattribute. */
internal fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
