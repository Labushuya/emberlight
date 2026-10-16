package io.github.labushuya.emberlight.core.media.permission

/**
 * Umfang des Medienzugriffs.
 *
 * - [FULL]: alle Medien sind lesbar.
 * - [PARTIAL]: nur vom Nutzer ausgewählte Medien (Android 14+, „ausgewählte Fotos“).
 * - [NONE]: kein Zugriff.
 */
enum class MediaAccess {
    FULL,
    PARTIAL,
    NONE,
}
