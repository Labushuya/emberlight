package io.github.labushuya.emberlight.demo

import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.di.AlternativeMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Die synthetische Bibliothek als alternative Medienquelle, aktiv solange der Demo-Modus gesetzt ist. */
@Singleton
internal class DemoMediaSource
    @Inject
    constructor(
        private val store: DemoModeStore,
    ) : AlternativeMediaSource {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Erst beim ersten Gebrauch angelegt, damit der Start im Normalbetrieb nichts baut. */
        override val repository: MediaRepository by lazy { SyntheticMediaRepository(scope) }

        override val active: Flow<Boolean> = store.enabled
    }
