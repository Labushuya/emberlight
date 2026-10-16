package io.github.labushuya.emberlight.extensions

import coil3.ComponentRegistry

/**
 * Zusätzliche Coil-Komponenten, etwa einen Decoder oder Fetcher eines Entwicklungs-Builds. Sie werden vor den Standardkomponenten
 * eingetragen, damit sie zuerst gefragt werden. Im Release-Build gibt es keine Beiträge.
 */
interface CoilComponents {
    fun contribute(builder: ComponentRegistry.Builder)
}
