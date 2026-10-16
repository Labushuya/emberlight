package io.github.labushuya.emberlight.demo

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.labushuya.emberlight.R
import io.github.labushuya.emberlight.core.media.di.AlternativeMediaSource
import io.github.labushuya.emberlight.extensions.AppOverlay
import io.github.labushuya.emberlight.extensions.CoilComponents
import io.github.labushuya.emberlight.extensions.LaunchIntentHandler
import javax.inject.Inject
import javax.inject.Singleton

/** Liest `--ez emberlight.demo true|false` beim Start und speichert den Schalter. */
@Singleton
internal class DemoIntentHandler
    @Inject
    constructor(
        private val store: DemoModeStore,
    ) : LaunchIntentHandler {
        override fun onLaunchIntent(intent: Intent) {
            if (intent.hasExtra(EXTRA_DEMO)) {
                store.setEnabled(intent.getBooleanExtra(EXTRA_DEMO, false))
            }
        }

        companion object {
            const val EXTRA_DEMO = "emberlight.demo"
        }
    }

/** Dezenter, gut sichtbarer Hinweis „DEMO“ oben rechts, solange der Demo-Modus aktiv ist. */
@Singleton
internal class DemoBadge
    @Inject
    constructor(
        private val store: DemoModeStore,
    ) : AppOverlay {
        @Composable
        override fun Overlay(modifier: Modifier) {
            val enabled by store.enabled.collectAsStateWithLifecycle(initialValue = false)
            if (enabled) {
                val label = stringResource(R.string.demo_badge)
                Box(
                    modifier =
                        modifier
                            .statusBarsPadding()
                            .padding(8.dp)
                            .testTag(DEMO_BADGE_TAG)
                            .semantics { contentDescription = label }
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }

/** Testmarke des Hinweises, damit Tests den Demo-Modus sichtbar prüfen können. */
internal const val DEMO_BADGE_TAG = "demo-badge"

/** Alle Beiträge des Demo-Source-Sets. Gebunden nur in Debug- und Benchmark-Builds (ADR 0011). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DemoModule {
    @Binds
    @IntoSet
    abstract fun alternativeSource(impl: DemoMediaSource): AlternativeMediaSource

    @Binds
    @IntoSet
    abstract fun coilComponents(impl: DemoThumbnailComponents): CoilComponents

    @Binds
    @IntoSet
    abstract fun launchIntent(impl: DemoIntentHandler): LaunchIntentHandler

    @Binds
    @IntoSet
    abstract fun overlay(impl: DemoBadge): AppOverlay
}
