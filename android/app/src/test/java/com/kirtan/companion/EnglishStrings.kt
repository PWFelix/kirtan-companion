package com.kirtan.companion

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * EnglishStrings — `values/strings.xml`, read the way Android would, for the
 * JVM unit tests.
 *
 * The tests run without Android, so there are no Resources to bind. Rather than
 * let every storage message collapse to an opaque token, tests [install] this
 * and keep asserting the real English sentences a user reads — which is what
 * those assertions were protecting in the first place.
 */
object EnglishStrings {
    private val englishFile = resFile("values")

    fun install() = Strings.install(source(englishFile))

    /** `res/<dir>/strings.xml`, wherever Gradle runs the tests from. */
    fun resFile(dir: String): File =
        listOf("src/main/res", "app/src/main/res")
            .map { File(it, "$dir/strings.xml") }
            .first { it.exists() }

    class Parsed(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    fun parse(file: File): Parsed {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val e = stringNodes.item(i) as Element
            strings[e.getAttribute("name")] = unescape(e.textContent)
        }
        val pluralNodes = doc.getElementsByTagName("plurals")
        for (i in 0 until pluralNodes.length) {
            val e = pluralNodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length).associate { j ->
                val item = items.item(j) as Element
                item.getAttribute("quantity") to unescape(item.textContent)
            }
        }
        return Parsed(strings, plurals)
    }

    /** Android's escapes: \' \" \\ and a leading \@ or \?. */
    private fun unescape(raw: String): String =
        raw.replace(Regex("""\\(.)""")) { it.groupValues[1] }

    private fun source(file: File): Strings.Source {
        val parsed = parse(file)
        val names = fieldNames(R.string::class.java)
        val pluralNames = fieldNames(R.plurals::class.java)
        return object : Strings.Source {
            override fun string(id: Int, args: Array<out Any>): String {
                val text = parsed.strings.getValue(names.getValue(id))
                return if (args.isEmpty()) text else String.format(Locale.US, text, *args)
            }

            override fun plural(id: Int, count: Int, args: Array<out Any>): String {
                val forms = parsed.plurals.getValue(pluralNames.getValue(id))
                val text = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
                return if (args.isEmpty()) text else String.format(Locale.US, text, *args)
            }
        }
    }

    private fun fieldNames(cls: Class<*>): Map<Int, String> =
        cls.fields.associate { it.getInt(null) to it.name }
}
