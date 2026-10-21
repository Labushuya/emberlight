package io.github.labushuya.emberlight.feature.space.form

import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRules
import java.time.ZoneId

/**
 * Ergebnis der Vorlage: das Formular für den neuen Space und die Hinweise, was angepasst wurde. [lifetimeReset] heißt, ein
 * festes Ende lag nicht mehr im zulässigen Bereich und steht jetzt auf der Voreinstellung. [limitReset] heißt, das
 * Speicherlimit war auf diesem Gerät nicht mehr verfügbar.
 */
data class TemplateForm(
    val form: SpaceForm,
    val lifetimeReset: Boolean,
    val limitReset: Boolean,
)

/**
 * Vorlage für einen neuen Space aus einem beendeten oder gelöschten Space (Paket 3.6c, ADR 0021). Übernommen werden Name,
 * Farbe, Laufzeit-Regel, Aufbewahrung und Speicherlimit. Das Titelbild kopiert die Oberfläche in den neuen Space. Medien und
 * Teilnehmer gehören nicht dazu.
 *
 * Die Regeln werden neu geprüft: Ein festes Ende, das nicht mehr in der Zukunft und höchstens 30 Tage entfernt liegt, wird
 * auf die Voreinstellung zurückgesetzt. Ein Speicherlimit über der Obergrenze des Geräts wird wie bei einem neuen Space
 * gewählt (größte zulässige Voreinstellung, sonst die Obergrenze).
 */
object SpaceTemplate {
    fun of(
        source: SpaceRecord,
        nowMillis: Long,
        zone: ZoneId,
        maxStorageBytes: Long,
    ): TemplateForm {
        val base = SpaceForm.from(source, zone).copy(coverSha256 = null)

        val until = source.settings.lifetime as? Lifetime.Until
        val lifetimeReset =
            until != null && (until.epochMillis <= nowMillis || until.epochMillis - nowMillis > UNTIL_MAX_MILLIS)
        val withLifetime =
            if (lifetimeReset) {
                base.copy(lifetime = LifetimeOption.ONE_DAY, untilDate = null, untilTime = null)
            } else {
                base
            }

        val limitReset = source.settings.storageLimitBytes > maxStorageBytes
        val withStorage =
            if (limitReset) {
                val fresh = SpaceForm.forNewSpace(maxStorageBytes)
                withLifetime.copy(storage = fresh.storage, storageCustom = fresh.storageCustom)
            } else {
                withLifetime
            }
        return TemplateForm(form = withStorage, lifetimeReset = lifetimeReset, limitReset = limitReset)
    }

    private val UNTIL_MAX_MILLIS = SpaceRules.UNTIL_MAX_DAYS * DAY_MILLIS

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
}
