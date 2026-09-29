package sh.gerra.again

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Every language says everything English does, with the same placeholders, on both platforms. */
class TranslationsTest {
    private val resources = File(System.getProperty("again.composeResourcesDir"))
    private val iosApp = File(System.getProperty("again.iosAppDir"))
    private val localesConfig = File(System.getProperty("again.localesConfig"))

    private fun xml(file: File): Document = DocumentBuilderFactory.newInstance().apply {
        // Info.plist names Apple's DTD; the test has no reason to fetch it.
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(file)

    private fun Document.elements(tag: String) = getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun strings(folder: String): Map<String, String> =
        xml(File(resources, "$folder/strings.xml")).elements("string").associate { it.getAttribute("name") to it.textContent }

    private fun placeholders(text: String) = Regex("""%\d+\$[ds]""").findAll(text).map { it.value }.sorted().toList()

    /** The languages besides English, by their composeResources folders: `values-ru` is `ru`. */
    private val languages = resources.listFiles()!!.filter { it.name.startsWith("values-") }.map { it.name.removePrefix("values-") }

    /** Info.plist's top-level keys, each with the element that follows it: a `<string>`, `<array>`, ... */
    private val infoPlist: Map<String, Element> = xml(File(iosApp, "Info.plist")).elements("dict").first().let { dict ->
        val children = (0 until dict.childNodes.length).map { dict.childNodes.item(it) }.filterIsInstance<Element>()
        children.zipWithNext().filter { (key, _) -> key.tagName == "key" }.associate { (key, value) -> key.textContent to value }
    }

    @Test
    fun everyLanguageIsComplete() {
        val english = strings("values")
        assertTrue(languages.isNotEmpty())
        for (language in languages) {
            val translated = strings("values-$language")
            assertEquals(english.keys, translated.keys, "values-$language differs from English")
            for ((key, text) in english) {
                assertEquals(placeholders(text), placeholders(translated.getValue(key)), "values-$language/$key")
            }
        }
    }

    @Test
    fun bothPlatformsOfferEveryLanguage() {
        val expected = (listOf("en") + languages).toSet()
        val ios = infoPlist.getValue("CFBundleLocalizations").getElementsByTagName("string").let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent } }
        assertEquals(expected, ios.toSet(), "CFBundleLocalizations in Info.plist")
        val android = xml(localesConfig).elements("locale").map { it.getAttribute("android:name") }
        assertEquals(expected, android.toSet(), "locales_config.xml")
    }

    /** iOS shows the permission prompts itself, from Info.plist, so they are translated beside it, not in composeResources. */
    @Test
    fun iosPermissionPromptsAreTranslated() {
        val prompts = infoPlist.keys.filter { it.endsWith("UsageDescription") }
        assertTrue(prompts.isNotEmpty())
        for (language in languages) {
            val file = File(iosApp, "$language.lproj/InfoPlist.strings")
            assertTrue(file.isFile, "$file is missing")
            val translated = Regex(""""(\w+)"\s*=\s*"([^"]+)"\s*;""").findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals(prompts.toSet(), translated.keys, "$language.lproj/InfoPlist.strings")
        }
    }
}
