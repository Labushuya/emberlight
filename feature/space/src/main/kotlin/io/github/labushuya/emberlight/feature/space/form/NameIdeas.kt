package io.github.labushuya.emberlight.feature.space.form

import kotlin.random.Random

/**
 * Namensvorschläge für einen Space (Paket 3.2, Entscheidung 1): ein Adjektiv und ein Substantiv aus den Wortlisten der
 * Sprache, etwa „Tanzende Waschbären“ oder „Dancing Raccoons“. Die Wortlisten kommen als Ressourcen, nie als Texte im Code.
 *
 * Deutsche Adjektive stehen schon in der Form, die zu einem Plural ohne Artikel passt (starke Deklination, Endung „-e“),
 * die Substantive im Plural. Eine Zufallsquelle wird von außen übergeben, damit Tests sie festlegen können.
 */
object NameIdeas {
    /** Ein Vorschlag aus je einem Eintrag der beiden Listen. Leere Listen sind ein Fehler der Ressourcen. */
    fun suggest(
        adjectives: List<String>,
        nouns: List<String>,
        random: Random,
    ): String {
        require(
            adjectives.isNotEmpty() && nouns.isNotEmpty(),
        ) { "Die Wortlisten für Namensvorschläge dürfen nicht leer sein." }
        return "${adjectives[random.nextInt(adjectives.size)]} ${nouns[random.nextInt(nouns.size)]}"
    }
}
