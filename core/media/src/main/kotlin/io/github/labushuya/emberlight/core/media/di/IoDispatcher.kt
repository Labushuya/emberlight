package io.github.labushuya.emberlight.core.media.di

import javax.inject.Qualifier

/** Kennzeichnet den Dispatcher für blockierende Medienabfragen (Dispatchers.IO im Produktivbetrieb). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class IoDispatcher
