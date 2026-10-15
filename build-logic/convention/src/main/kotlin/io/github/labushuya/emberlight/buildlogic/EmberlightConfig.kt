package io.github.labushuya.emberlight.buildlogic

/**
 * Zentrale Build-Konstanten.
 *
 * Die applicationId wird ausschließlich hier festgelegt. Die Namespaces der Library-Module
 * leiten sich davon ab, ein Wechsel ist also an einer Stelle erledigt.
 */
object EmberlightConfig {
    const val APPLICATION_ID = "io.github.labushuya.emberlight"
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26

    /** JDK für Unit-Tests (Robolectric 4.17 mit SDK 37 braucht 21). Der Build selbst bleibt auf JDK 17. */
    const val TEST_JAVA_VERSION = 21
}
