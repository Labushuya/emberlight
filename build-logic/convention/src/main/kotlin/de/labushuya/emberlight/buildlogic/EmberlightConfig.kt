package de.labushuya.emberlight.buildlogic

/**
 * Zentrale Build-Konstanten.
 *
 * Die applicationId wird ausschließlich hier festgelegt. Die Namespaces der Library-Module
 * leiten sich davon ab, ein Wechsel ist also an einer Stelle erledigt.
 */
object EmberlightConfig {
    const val APPLICATION_ID = "de.labushuya.emberlight"
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
}
