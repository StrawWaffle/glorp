package io.github.strawwaffle.glorp.site

/**
 * The emote stylesheet's rules that depend on where an emote is: next to
 * another emote (`.emote.BLADE + .emote.BLADE`), in a line that holds one
 * (`.msg-chat:has(.emote.BERN) .emote.KYSNOW`), first or last of its kind,
 * or in a combo of some size (`[data-combo-group="x10"]`). They're matched
 * here against a small model of the line chat-gui would build, with the
 * selectors the stylesheet uses; anything else (hover, `::before`) never
 * matches, so an emote it can't place shows as it would on its own.
 */
internal class EmoteContext private constructor(
    /** Rules by the emote names their subject names; "*" for ones that name none. */
    private val byName: Map<String, List<ContextRule>>,
) {
    val isEmpty: Boolean get() = byName.isEmpty()

    /**
     * The declarations that apply to each emote of [line] beyond its own
     * rules, in cascade order (lowest first); an empty list where none do.
     */
    fun declarations(line: LineModel, pseudo: String? = null): List<List<Pair<String, String>>> = line.emotes.map { el ->
        val name = el.emoteName ?: return@map emptyList()
        val candidates = byName[name].orEmpty() + byName["*"].orEmpty()
        // [pseudo] "before" or "after": the rules for that part of it (NoTears' banana beside another NoTears).
        candidates.filter { it.pseudo == pseudo }.mapNotNull { rule ->
            val spec = rule.selectors.filter { it.matches(el) }.maxOfOrNull { it.specificity } ?: return@mapNotNull null
            Triple(spec, rule.order, rule.declarations)
        }.sortedWith(compareBy({ it.first }, { it.second })).flatMap { it.third }
    }

    companion object {
        val None = EmoteContext(emptyMap())

        /** The properties Glorp draws from; rules setting none of them are skipped. */
        private val drawn = setOf(
            "animation", "width", "height", "background-position", "background-size", "background-image",
            "transform", "transform-origin", "filter", "opacity",
            // Longhands a neighbour rule changes timing with (`.emote.SOY + .emote.Clap`).
            "animation-duration", "animation-delay", "animation-iteration-count", "animation-direction",
            "translate", "rotate", "scale",
            "margin", "margin-left", "margin-right", "margin-inline", "vertical-align",
        )

        fun parse(css: String): EmoteContext {
            val out = HashMap<String, MutableList<ContextRule>>()
            var order = 0
            for (match in Regex("""(?:^|(?<=[}]))([^{}@]+)\{([^{}]*)\}""").findAll(css)) {
                val selectorText = match.groupValues[1].trim()
                if ("emote" !in selectorText) continue
                val all = match.groupValues[2].split(';').mapNotNull { decl ->
                    val colon = decl.indexOf(':')
                    if (colon <= 0) null
                    else decl.substring(0, colon).trim().let { if (it.startsWith("--")) it else it.lowercase() } to decl.substring(colon + 1).replace("!important", "").trim()
                }
                val declarations = all.filter { (name, _) -> name in drawn || name.startsWith("--") }
                // Selectors by the part of the emote they style: itself, or its ::before or ::after.
                val byPart = splitTopLevel(selectorText, ',').map { it.trim() }.groupBy { pseudoEnd.find(it)?.groupValues?.get(1) }
                for ((pseudo, texts) in byPart) {
                    val decls = if (pseudo == null) declarations else all
                    if (decls.isEmpty()) continue
                    val selectors = texts.mapNotNull { text ->
                        val subject = if (pseudo == null) text else text.removeRange(pseudoEnd.find(text)!!.range)
                        // An emote's own rules are read with it; only the ones that depend on context belong here.
                        if (ownRule.matches(subject)) null else runCatching { SelectorParser(subject).complex() }.getOrNull()
                    }.filter { !it.never }
                    if (selectors.isEmpty()) continue
                    val rule = ContextRule(selectors, decls, order++, pseudo)
                    val names = selectors.flatMap { it.subjectNames() }.ifEmpty { listOf("*") }.toSet()
                    for (name in names) out.getOrPut(name) { ArrayList() } += rule
                }
            }
            return EmoteContext(out)
        }

        private val ownRule = Regex("""^(?:\.text\s*>\s*)?\.emote\.[A-Za-z0-9_-]+$""")
        private val pseudoEnd = Regex("""::?(before|after)$""")
    }
}

private class ContextRule(val selectors: List<Complex>, val declarations: List<Pair<String, String>>, val order: Int, val pseudo: String? = null)

// --- A model of a chat line -------------------------------------------------

/** An element of the line as chat-gui builds it, with what selectors look at. */
internal class El(val tag: String, val classes: Set<String>, val attrs: Map<String, String> = emptyMap()) {
    var parent: El? = null
        private set
    val children = ArrayList<El>()

    fun add(child: El): El {
        child.parent = this
        children += child
        return child
    }

    val emoteName: String? get() = if ("emote" in classes) classes.firstOrNull { it != "emote" } else null
}

/**
 * One line: `div.msg-chat.msg-user` (with the sender's flairs as classes)
 * holding the name and `span.text`, whose children are the emotes and links;
 * or, for a combo, `div.msg-chat.msg-emote[data-combo-group]` with one emote.
 */
internal class LineModel(val root: El, val emotes: List<El>) {
    companion object {
        /** [parts] in order: an emote's name, or null for a link (text between them isn't an element). */
        fun message(
            features: List<String>, nameFlair: String?, continued: Boolean, green: Boolean, parts: List<String?>,
            /** The sender, as chat-gui's `data-username`: lower case. Some emotes look different for one person. */
            nick: String? = null,
            /** As if the pointer were on the emotes (and so on the line): `:hover` rules apply. */
            hovered: Boolean = false,
        ): LineModel {
            val hover = if (hovered) mapOf("hover" to "1") else emptyMap()
            val classes = buildSet {
                add("msg-chat"); add("msg-user"); addAll(features)
                if (continued) add("msg-continue")
            }
            val root = El("div", classes, nick?.let { mapOf("data-username" to it.lowercase()) }.orEmpty() + hover)
            if (features.isNotEmpty()) {
                val badges = root.add(El("span", setOf("features")))
                for (f in features) badges.add(El("i", setOf("flair", f)))
            }
            root.add(El("a", setOfNotNull("user", nameFlair)))
            root.add(El("span", setOf("ctrl")))
            var text = root.add(El("span", setOf("text"), hover))
            if (green) text = text.add(El("span", setOf("greentext")))
            val emotes = ArrayList<El>()
            for (part in parts) {
                if (part == null) text.add(El("a", setOf("externallink")))
                else emotes += text.add(El("div", setOf("emote", part), hover))
            }
            return LineModel(root, emotes)
        }

        fun combo(emote: String, count: Int): LineModel {
            val group = when {
                count >= 50 -> "x50"; count >= 30 -> "x30"; count >= 20 -> "x20"
                count >= 10 -> "x10"; count >= 5 -> "x5"; else -> "x2"
            }
            val root = El("div", setOf("msg-chat", "msg-emote"), mapOf("data-combo" to "$count", "data-combo-group" to group))
            val text = root.add(El("span", setOf("text")))
            val el = text.add(El("div", setOf("emote", emote)))
            root.add(El("span", setOf("chat-combo", group)))
            return LineModel(root, listOf(el))
        }
    }
}

// --- Selectors ---------------------------------------------------------------

/** Specificity as one number: ids, then classes/attributes/pseudo-classes, then tags. */
private fun spec(ids: Int, classes: Int, tags: Int) = ids * 10_000 + classes * 100 + tags

private sealed interface Simple {
    fun matches(el: El): Boolean
    val specificity: Int
    val never: Boolean get() = false
}

private data class Tag(val name: String) : Simple {
    override fun matches(el: El) = el.tag == name
    override val specificity get() = spec(0, 0, 1)
}

private data class Cls(val name: String) : Simple {
    override fun matches(el: El) = name in el.classes
    override val specificity get() = spec(0, 1, 0)
}

private data class Attr(val name: String, val op: String?, val value: String?) : Simple {
    override fun matches(el: El): Boolean {
        val v = el.attrs[name] ?: return false
        val want = value ?: return true
        return when (op) {
            "=" -> v == want
            "^=" -> v.startsWith(want)
            "$=" -> v.endsWith(want)
            "*=" -> want in v
            "~=" -> want in v.split(' ')
            else -> false
        }
    }
    override val specificity get() = spec(0, 1, 0)
}

private class Not(val list: List<Complex>) : Simple {
    override fun matches(el: El) = list.none { it.matches(el) }
    override val specificity get() = list.maxOfOrNull { it.specificity } ?: 0
}

private class Is(val list: List<Complex>, val zero: Boolean) : Simple {
    override fun matches(el: El) = list.any { it.matches(el) }
    override val specificity get() = if (zero) 0 else list.maxOfOrNull { it.specificity } ?: 0
}

/** `:has(+ .emote)` and the like: a selector relative to this element. */
private class Has(val list: List<Complex>) : Simple {
    override fun matches(el: El) = list.any { it.matchesRelative(el) }
    override val specificity get() = list.maxOfOrNull { it.specificity } ?: 0
}

/** `:nth-child(an+b)`, `:nth-of-type`, `:first-child`, `:last-of-type`… */
private class Nth(val a: Int, val b: Int, val ofType: Boolean, val fromEnd: Boolean) : Simple {
    override fun matches(el: El): Boolean {
        val siblings = el.parent?.children?.filter { !ofType || it.tag == el.tag } ?: listOf(el)
        val index = siblings.indexOf(el).let { if (fromEnd) siblings.size - it else it + 1 }
        if (a == 0) return index == b
        val n = (index - b)
        return n % a == 0 && n / a >= 0
    }
    override val specificity get() = spec(0, 1, 0)
}

private class Only(val ofType: Boolean) : Simple {
    override fun matches(el: El) = (el.parent?.children?.count { !ofType || it.tag == el.tag } ?: 1) == 1
    override val specificity get() = spec(0, 1, 0)
}

/** `:hover`: an element of a line built as pointed at. */
private object Hover : Simple {
    override fun matches(el: El) = el.attrs["hover"] == "1"
    override val specificity get() = spec(0, 1, 0)
}

/** Pseudo-elements and anything not modelled: never matches. */
private object Never : Simple {
    override fun matches(el: El) = false
    override val specificity get() = 0
    override val never get() = true
}

private class Compound(val parts: List<Simple>) {
    fun matches(el: El) = parts.all { it.matches(el) }
    val specificity get() = parts.sumOf { it.specificity }
    val never get() = parts.any { it.never }
}

/**
 * A complex selector: compounds joined by combinators (' ', '>', '+', '~').
 * [lead] is the combinator before the first compound, for `:has(+ …)`.
 */
private class Complex(val compounds: List<Compound>, val combinators: List<Char>, val lead: Char? = null) {
    val specificity: Int get() = compounds.sumOf { it.specificity }
    val never: Boolean get() = compounds.any { it.never }

    fun matches(el: El): Boolean = matchFrom(compounds.size - 1, el)

    /** The emote names the subject (last compound) requires, through `:is()` too. */
    fun subjectNames(): List<String> {
        val subject = compounds.last()
        val direct = subject.parts.filterIsInstance<Cls>().map { it.name }.filter { it != "emote" }
        if (direct.isNotEmpty()) return direct
        return subject.parts.filterIsInstance<Is>().flatMap { inner -> inner.list.flatMap { it.subjectNames() } }
    }

    private fun matchFrom(i: Int, el: El): Boolean {
        if (!compounds[i].matches(el)) return false
        if (i == 0) return true
        return when (combinators[i - 1]) {
            '>' -> el.parent?.let { matchFrom(i - 1, it) } == true
            ' ' -> generateSequence(el.parent) { it.parent }.any { matchFrom(i - 1, it) }
            '+' -> previous(el)?.let { matchFrom(i - 1, it) } == true
            '~' -> earlier(el).any { matchFrom(i - 1, it) }
            else -> false
        }
    }

    /** Whether something relative to [anchor] (`:has(+ .x)`, `:has(.x)`) matches. */
    fun matchesRelative(anchor: El): Boolean {
        val candidates: Sequence<El> = when (lead ?: ' ') {
            '+' -> listOfNotNull(next(anchor)).asSequence()
            '~' -> later(anchor)
            '>' -> anchor.children.asSequence()
            else -> descendants(anchor)
        }
        // The first compound must sit where [lead] says; the rest chains forward.
        return candidates.any { first -> matchForward(0, first) }
    }

    private fun matchForward(i: Int, el: El): Boolean {
        if (!compounds[i].matches(el)) return false
        if (i == compounds.size - 1) return true
        val nextOnes: Sequence<El> = when (combinators[i]) {
            '+' -> listOfNotNull(next(el)).asSequence()
            '~' -> later(el)
            '>' -> el.children.asSequence()
            else -> descendants(el)
        }
        return nextOnes.any { matchForward(i + 1, it) }
    }
}

private fun previous(el: El): El? = el.parent?.children?.let { it.getOrNull(it.indexOf(el) - 1) }
private fun next(el: El): El? = el.parent?.children?.let { it.getOrNull(it.indexOf(el) + 1) }
private fun earlier(el: El): Sequence<El> = el.parent?.children?.let { it.subList(0, it.indexOf(el)).asReversed().asSequence() } ?: emptySequence()
private fun later(el: El): Sequence<El> = el.parent?.children?.let { it.subList(it.indexOf(el) + 1, it.size).asSequence() } ?: emptySequence()
private fun descendants(el: El): Sequence<El> = el.children.asSequence().flatMap { sequenceOf(it) + descendants(it) }

/** Splits on [separator] outside brackets, parentheses and quotes. */
internal fun splitTopLevel(text: String, separator: Char): List<String> {
    val parts = ArrayList<String>()
    var depth = 0
    var quote: Char? = null
    var start = 0
    for ((i, c) in text.withIndex()) {
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '(' || c == '[' -> depth++
            c == ')' || c == ']' -> depth--
            c == separator && depth == 0 -> { parts += text.substring(start, i); start = i + 1 }
        }
    }
    parts += text.substring(start)
    return parts
}

/** Reads the selectors the emote stylesheet writes; throws on what it doesn't know how to read. */
private class SelectorParser(private val s: String) {
    private var i = 0

    fun complex(allowLead: Boolean = false): Complex {
        skipSpace()
        var lead: Char? = null
        if (allowLead && i < s.length && s[i] in ">+~") { lead = s[i++]; skipSpace() }
        val compounds = arrayListOf(compound())
        val combinators = ArrayList<Char>()
        while (true) {
            val hadSpace = skipSpace()
            if (i >= s.length || s[i] == ')' || s[i] == ',') break
            val c = s[i]
            if (c in ">+~") { i++; skipSpace(); combinators += c } else if (hadSpace) combinators += ' ' else error("at $i in $s")
            compounds += compound()
        }
        return Complex(compounds, combinators, lead)
    }

    private fun list(allowLead: Boolean): List<Complex> {
        val out = arrayListOf(complex(allowLead))
        while (true) {
            skipSpace()
            if (i < s.length && s[i] == ',') { i++; out += complex(allowLead) } else break
        }
        expect(')')
        return out
    }

    private fun compound(): Compound {
        val parts = ArrayList<Simple>()
        while (i < s.length) {
            when (s[i]) {
                '.' -> { i++; parts += Cls(ident()) }
                '[' -> parts += attr()
                ':' -> parts += pseudo()
                '*' -> i++
                else -> if (s[i].isLetter()) parts += Tag(ident().lowercase()) else break
            }
        }
        if (parts.isEmpty() && s.getOrNull(i - 1) != '*') error("empty compound at $i in $s")
        return Compound(parts)
    }

    private fun attr(): Simple {
        expect('[')
        val name = ident()
        skipSpace()
        if (s[i] == ']') { i++; return Attr(name, null, null) }
        val op = if (s[i] == '=') "=" else s.substring(i, i + 2)
        i += op.length
        skipSpace()
        val value = if (s[i] == '"' || s[i] == '\'') {
            val q = s[i++]
            val end = s.indexOf(q, i)
            s.substring(i, end).also { i = end + 1 }
        } else ident()
        skipSpace()
        expect(']')
        return Attr(name, op, value)
    }

    private fun pseudo(): Simple {
        expect(':')
        if (s.getOrNull(i) == ':') { i++; ident(); skipArgs(); return Never }
        val name = ident().lowercase()
        return when (name) {
            "not" -> { expect('('); Not(list(false)) }
            "is", "matches" -> { expect('('); Is(list(false), zero = false) }
            "where" -> { expect('('); Is(list(false), zero = true) }
            "has" -> { expect('('); Has(list(true)) }
            "first-child" -> Nth(0, 1, ofType = false, fromEnd = false)
            "last-child" -> Nth(0, 1, ofType = false, fromEnd = true)
            "first-of-type" -> Nth(0, 1, ofType = true, fromEnd = false)
            "last-of-type" -> Nth(0, 1, ofType = true, fromEnd = true)
            "only-child" -> Only(ofType = false)
            "only-of-type" -> Only(ofType = true)
            // Pointed at: only in a line built for the emote card's held emote (see LineModel.message's `hovered`).
            "hover" -> Hover
            "nth-child", "nth-of-type", "nth-last-child", "nth-last-of-type" -> {
                expect('(')
                val arg = s.substring(i, s.indexOf(')', i)).trim().lowercase()
                i = s.indexOf(')', i) + 1
                val (a, b) = anPlusB(arg)
                Nth(a, b, ofType = name.endsWith("of-type"), fromEnd = "last" in name)
            }
            else -> { skipArgs(); Never }
        }
    }

    private fun anPlusB(arg: String): Pair<Int, Int> = when {
        arg == "odd" -> 2 to 1
        arg == "even" -> 2 to 0
        'n' !in arg -> 0 to arg.toInt()
        else -> {
            val (aText, bText) = arg.split('n', limit = 2)
            val a = when (aText.trim()) { "", "+" -> 1; "-" -> -1; else -> aText.trim().toInt() }
            a to (bText.replace(" ", "").ifEmpty { "0" }.toInt())
        }
    }

    private fun skipArgs() {
        if (s.getOrNull(i) != '(') return
        var depth = 0
        while (i < s.length) {
            if (s[i] == '(') depth++
            if (s[i] == ')') { depth--; if (depth == 0) { i++; return } }
            i++
        }
    }

    private fun ident(): String {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
        if (i == start) error("expected a name at $i in $s")
        return s.substring(start, i)
    }

    private fun skipSpace(): Boolean {
        val start = i
        while (i < s.length && s[i].isWhitespace()) i++
        return i > start
    }

    private fun expect(c: Char) {
        skipSpace()
        if (s.getOrNull(i) != c) error("expected $c at $i in $s")
        i++
    }
}
