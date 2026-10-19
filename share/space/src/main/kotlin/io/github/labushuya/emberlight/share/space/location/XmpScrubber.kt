package io.github.labushuya.emberlight.share.space.location

/**
 * XMP-Scrubber (ADR 0015, Fundorte). Ein toleranter Byte-Scanner über UTF-8. Namespaces werden über die URI
 * aufgelöst, nicht über das Präfix. Gesucht wird:
 * - `exif`: alle `GPS*`-Eigenschaften
 * - `photoshop`: City, State, Country
 * - `Iptc4xmpCore`: Location, CountryCode
 * - `Iptc4xmpExt`: LocationCreated, LocationShown
 *
 * Elemente werden samt Tags, Attribute als `name="wert"`, jeweils durch Leerzeichen gleicher Länge ersetzt. So bleibt
 * das XML wohlgeformt. Unlesbares XML führt zu `structure`, nicht zu einer Teilbereinigung.
 */
internal object XmpScrubber {
    /** Eine zu überschreibende Stelle, relativ zum Anfang des XMP-Bereichs. [end] ist exklusiv. */
    internal class Span(
        val start: Int,
        val end: Int,
    )

    private const val EXIF_NS = "http://ns.adobe.com/exif/1.0/"
    private const val PHOTOSHOP_NS = "http://ns.adobe.com/photoshop/1.0/"
    private const val IPTC_CORE_NS = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
    private const val IPTC_EXT_NS = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    private val TARGETS: Map<String, (String) -> Boolean> =
        mapOf(
            EXIF_NS to { local -> local.startsWith("GPS") },
            PHOTOSHOP_NS to { local -> local in setOf("City", "State", "Country") },
            IPTC_CORE_NS to { local -> local in setOf("Location", "CountryCode") },
            IPTC_EXT_NS to { local -> local in setOf("LocationCreated", "LocationShown") },
        )
    private val NAMESPACE_DECLARATION = Regex("xmlns:([A-Za-z_][\\w.-]*)\\s*=\\s*[\"']([^\"']*)[\"']")

    private class Tag(
        val start: Int,
        val end: Int,
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val attributes: List<Attr>,
    )

    private class Attr(
        val start: Int,
        val end: Int,
        val name: String,
    )

    /** Blankt die Standort-Eigenschaften im XMP-Bereich [start, end) der Datei. */
    fun scrubRegion(
        pass: ScrubPass,
        start: Long,
        end: Long,
    ) {
        val size = end - start
        if (size <= 0) return
        if (size > ScrubLimits.MAX_TEXT) bail(NotRemovableReason.STRUCTURE)
        val bytes = pass.bytes(start, size, end)
        for (span in locationSpans(bytes)) {
            pass.blank(start + span.start, (span.end - span.start).toLong(), 0x20, "xmp_location")
        }
    }

    /** Stellen mit Standort-Eigenschaften im Text [bytes]. Sich überlappende Stellen werden zusammengefasst. */
    internal fun locationSpans(bytes: ByteArray): List<Span> {
        val xml = String(bytes, Charsets.ISO_8859_1)
        val tags = tokenize(xml)
        val prefixes = namespaces(xml)
        val candidates = ArrayList<Span>()
        val open = ArrayList<Tag>()
        for (tag in tags) {
            when {
                tag.selfClosing -> {
                    if (isTarget(tag.name, prefixes)) candidates.add(Span(tag.start, tag.end))
                    addAttributeSpans(tag, prefixes, candidates)
                }

                tag.closing -> {
                    val opening = open.removeLastOrNull() ?: bail(NotRemovableReason.STRUCTURE)
                    if (opening.name != tag.name) bail(NotRemovableReason.STRUCTURE)
                    if (isTarget(opening.name, prefixes)) candidates.add(Span(opening.start, tag.end))
                    addAttributeSpans(opening, prefixes, candidates)
                }

                else -> {
                    open.add(tag)
                }
            }
        }
        if (open.isNotEmpty()) bail(NotRemovableReason.STRUCTURE)
        return merge(candidates)
    }

    /** Attribute: ein Ziel steht im Start-Tag. Der Bereich umfasst Name, Gleichheitszeichen und Wert. */
    private fun addAttributeSpans(
        tag: Tag,
        prefixes: Map<String, String>,
        out: MutableList<Span>,
    ) {
        for (attribute in tag.attributes) {
            if (isTarget(attribute.name, prefixes)) out.add(Span(attribute.start, attribute.end))
        }
    }

    /** Sortiert und entfernt Überlappungen. Eine äußere Stelle deckt die innere ab. */
    private fun merge(candidates: List<Span>): List<Span> {
        val sorted = candidates.sortedWith(compareBy<Span>({ it.start }, { -it.end }))
        val result = ArrayList<Span>()
        for (span in sorted) {
            val last = result.lastOrNull()
            if (last != null && span.start < last.end) continue
            result.add(span)
        }
        return result
    }

    /** Ist der Name (Präfix:Lokalname) ein Ziel? Ein nicht deklariertes Präfix gilt als Fehler. */
    private fun isTarget(
        name: String,
        prefixes: Map<String, String>,
    ): Boolean {
        val colon = name.indexOf(':')
        if (colon <= 0) return false
        val prefix = name.substring(0, colon)
        if (prefix == "xml" || prefix == "xmlns") return false
        val uri = prefixes[prefix] ?: bail(NotRemovableReason.STRUCTURE)
        val local = name.substring(colon + 1)
        return TARGETS[uri]?.invoke(local) == true
    }

    private fun namespaces(xml: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (match in NAMESPACE_DECLARATION.findAll(xml)) {
            val prefix = match.groupValues[1]
            val uri = match.groupValues[2]
            val known = map[prefix]
            if (known != null && known != uri) bail(NotRemovableReason.STRUCTURE)
            map[prefix] = uri
        }
        return map
    }

    /** Zerlegt das XML in Tags. Kommentare, Verarbeitungsanweisungen und CDATA werden übersprungen. */
    private fun tokenize(xml: String): List<Tag> {
        val tags = ArrayList<Tag>()
        var index = 0
        while (true) {
            val lt = xml.indexOf('<', index)
            if (lt < 0) break
            index =
                when {
                    xml.startsWith("<?", lt) -> skipPast(xml, "?>", lt) + 2
                    xml.startsWith("<!--", lt) -> skipPast(xml, "-->", lt) + 3
                    xml.startsWith("<![CDATA[", lt) -> skipPast(xml, "]]>", lt) + 3
                    xml.startsWith("<!", lt) -> bail(NotRemovableReason.STRUCTURE)
                    else -> parseTag(xml, lt, tags)
                }
        }
        return tags
    }

    private fun skipPast(
        xml: String,
        terminator: String,
        from: Int,
    ): Int {
        val found = xml.indexOf(terminator, from)
        if (found < 0) bail(NotRemovableReason.STRUCTURE)
        return found
    }

    /** Liest ein Tag ab [lt] und liefert die Position nach dem Tag. */
    private fun parseTag(
        xml: String,
        lt: Int,
        tags: MutableList<Tag>,
    ): Int {
        val closing = xml.getOrNull(lt + 1) == '/'
        var p = lt + if (closing) 2 else 1
        val nameStart = p
        while (p < xml.length && isNameChar(xml[p])) p++
        if (p == nameStart) bail(NotRemovableReason.STRUCTURE)
        val name = xml.substring(nameStart, p)
        val attributes = ArrayList<Attr>()
        var selfClosing = false
        while (true) {
            p = skipSpace(xml, p)
            if (p >= xml.length) bail(NotRemovableReason.STRUCTURE)
            if (xml[p] == '>') {
                p++
                break
            }
            if (xml[p] == '/' && xml.getOrNull(p + 1) == '>') {
                selfClosing = true
                p += 2
                break
            }
            if (closing) bail(NotRemovableReason.STRUCTURE)
            val attributeStart = p
            while (p < xml.length && isNameChar(xml[p])) p++
            if (p == attributeStart) bail(NotRemovableReason.STRUCTURE)
            val attributeName = xml.substring(attributeStart, p)
            p = skipSpace(xml, p)
            if (xml.getOrNull(p) != '=') bail(NotRemovableReason.STRUCTURE)
            p = skipSpace(xml, p + 1)
            val quote = xml.getOrNull(p) ?: bail(NotRemovableReason.STRUCTURE)
            if (quote != '"' && quote != '\'') bail(NotRemovableReason.STRUCTURE)
            val close = xml.indexOf(quote, p + 1)
            if (close < 0) bail(NotRemovableReason.STRUCTURE)
            p = close + 1
            attributes.add(Attr(attributeStart, p, attributeName))
        }
        tags.add(Tag(lt, p, name, closing, selfClosing, attributes))
        return p
    }

    private fun skipSpace(
        xml: String,
        from: Int,
    ): Int {
        var p = from
        while (p < xml.length && (xml[p] == ' ' || xml[p] == '\t' || xml[p] == '\r' || xml[p] == '\n')) p++
        return p
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == ':' || c == '_' || c == '-' || c == '.'
}
