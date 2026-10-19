package io.github.labushuya.emberlight.feature.space.form

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.share.space.management.SpaceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.random.Random

/**
 * Namensvorschläge (Paket 3.2, Entscheidung 1). Die Zufallsquelle ist fest, die Wortlisten kommen aus den Ressourcen
 * der jeweiligen Sprache.
 */
@RunWith(AndroidJUnit4::class)
class NameIdeasTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun wordLists(locale: Locale): Pair<List<String>, List<String>> {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val resources = context.createConfigurationContext(configuration).resources
        return resources.getStringArray(R.array.space_name_adjectives).toList() to
            resources.getStringArray(R.array.space_name_nouns).toList()
    }

    @Test
    fun aFixedRandomSourceGivesTheSameSuggestion() {
        val adjectives = listOf("Tanzende", "Wilde", "Müde", "Fröhliche")
        val nouns = listOf("Waschbären", "Igel", "Pinguine")

        val first = NameIdeas.suggest(adjectives, nouns, Random(42))
        val second = NameIdeas.suggest(adjectives, nouns, Random(42))

        assertEquals(first, second)
    }

    @Test
    fun aSuggestionIsAnAdjectiveFollowedByANoun() {
        assertEquals("Tanzende Waschbären", NameIdeas.suggest(listOf("Tanzende"), listOf("Waschbären"), Random(1)))
    }

    @Test
    fun differentSeedsCanGiveDifferentNames() {
        val adjectives = wordLists(Locale.GERMAN).first
        val nouns = wordLists(Locale.GERMAN).second

        val names = (1..20).map { NameIdeas.suggest(adjectives, nouns, Random(it)) }.toSet()

        assertTrue(names.size > 1)
        assertNotEquals(names.first(), names.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyWordListsAreAnError() {
        NameIdeas.suggest(emptyList(), listOf("Igel"), Random(1))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishListsHaveAtLeastFortyWordsEach() {
        val (adjectives, nouns) = wordLists(Locale.ENGLISH)
        assertTrue("Adjektive: ${adjectives.size}", adjectives.size >= MIN_WORDS)
        assertTrue("Substantive: ${nouns.size}", nouns.size >= MIN_WORDS)
    }

    @Test
    @Config(qualifiers = "de")
    fun germanListsHaveAtLeastFortyWordsEach() {
        val (adjectives, nouns) = wordLists(Locale.GERMAN)
        assertTrue("Adjektive: ${adjectives.size}", adjectives.size >= MIN_WORDS)
        assertTrue("Substantive: ${nouns.size}", nouns.size >= MIN_WORDS)
    }

    @Test
    fun everyCombinationFitsTheNameLimitInBothLanguages() {
        for (locale in listOf(Locale.ENGLISH, Locale.GERMAN)) {
            val (adjectives, nouns) = wordLists(locale)
            for (adjective in adjectives) {
                for (noun in nouns) {
                    val name = "$adjective $noun"
                    assertTrue(
                        "$locale: \"$name\" ist zu lang",
                        name.codePointCount(0, name.length) <= SpaceRules.NAME_MAX_CHARS,
                    )
                }
            }
        }
    }

    @Test
    fun germanAdjectivesAreInTheFormThatFitsAPluralWithoutArticle() {
        // Starke Deklination im Nominativ Plural: alle Adjektive enden auf „-e“, die Substantive stehen im Plural und groß.
        val (adjectives, nouns) = wordLists(Locale.GERMAN)
        adjectives.forEach { adjective ->
            assertTrue("„$adjective“ endet nicht auf -e", adjective.endsWith("e"))
            assertTrue("„$adjective“ ist nicht groß geschrieben", adjective.first().isUpperCase())
        }
        nouns.forEach { noun -> assertTrue("„$noun“ ist nicht groß geschrieben", noun.first().isUpperCase()) }
    }

    private companion object {
        const val MIN_WORDS = 40
    }
}
