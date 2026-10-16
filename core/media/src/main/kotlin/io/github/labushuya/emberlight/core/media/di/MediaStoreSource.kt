package io.github.labushuya.emberlight.core.media.di

import javax.inject.Qualifier

/**
 * Kennzeichnet die MediaStore-Implementierung von `MediaRepository`. Die öffentliche Bindung in
 * `MediaRepositoryModule` reicht sie durch. So kann eine Test-Installation das Repository ersetzen, ohne auf interne
 * Klassen zuzugreifen.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MediaStoreSource
