package com.kirtan.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spanish file must be a faithful mirror of the English one.
 *
 * A translation that names a string English doesn't have is dead text; one that
 * drops or renumbers a placeholder crashes at format time on a Spanish phone
 * only — exactly the bug nobody on an English device would ever see.
 */
class LocalisationTest {
    private val en = EnglishStrings.parse(EnglishStrings.resFile("values"))
    private val es = EnglishStrings.parse(EnglishStrings.resFile("values-es"))
    private val placeholder = Regex("""%(\d+\$)?[sdf]""")

    private fun slots(text: String) = placeholder.findAll(text).map { it.value }.toSortedSet()

    @Test
    fun `every Spanish string exists in English with the same placeholders`() {
        for ((name, text) in es.strings) {
            val english = en.strings[name]
            assertTrue("values-es has $name, values does not", english != null)
            assertEquals("placeholders differ in $name", slots(english!!), slots(text))
        }
    }

    @Test
    fun `every Spanish plural exists in English with the same placeholders`() {
        for ((name, forms) in es.plurals) {
            val english = en.plurals[name]
            assertTrue("values-es has plural $name, values does not", english != null)
            val want = slots(english!!.getValue("other"))
            for ((quantity, text) in forms) {
                assertEquals("placeholders differ in $name/$quantity", want, slots(text))
            }
        }
    }

    @Test
    fun `every English string has a Spanish translation`() {
        val missing = en.strings.keys - es.strings.keys - setOf("app_name", "notif_title")
        assertTrue("untranslated: $missing", missing.isEmpty())
        assertTrue("untranslated plurals", (en.plurals.keys - es.plurals.keys).isEmpty())
    }

    @Test
    fun `English strings resolve with their arguments`() {
        EnglishStrings.install()
        try {
            assertEquals("Delete Ta?", Strings.get(R.string.beats_delete_title, "Ta"))
            assertEquals("1 cell", Strings.plural(R.plurals.beat_cells, 1, 1))
            assertEquals("8 cells", Strings.plural(R.plurals.beat_cells, 8, 8))
        } finally {
            Strings.install(null)
        }
    }
}
