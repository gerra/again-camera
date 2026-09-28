package sh.gerra.again

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** Every language says everything English does, with the same placeholders. */
class TranslationsTest {
    private val resources = File(System.getProperty("again.composeResourcesDir"))

    private fun strings(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resources, "$folder/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun placeholders(text: String) = Regex("""%\d+\$[ds]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageIsComplete() {
        val english = strings("values")
        val languages = resources.listFiles()!!.filter { it.name.startsWith("values-") }
        assertTrue(languages.isNotEmpty())
        for (language in languages) {
            val translated = strings(language.name)
            assertEquals(english.keys, translated.keys, "${language.name} differs from English")
            for ((key, text) in english) {
                assertEquals(placeholders(text), placeholders(translated.getValue(key)), "${language.name}/$key")
            }
        }
    }
}
