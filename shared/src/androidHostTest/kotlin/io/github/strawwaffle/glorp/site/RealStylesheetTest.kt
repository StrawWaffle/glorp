package io.github.strawwaffle.glorp.site

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reads a saved copy of the site's real emote stylesheet, when one is given
 * (`-Dglorp.emotes=<dir with emotes.css and emotes.json>`), and checks that
 * the context rules parse and that the effects people see most come out.
 */
class RealStylesheetTest {
    @Test
    fun readsTheRealStylesheet() {
        val dir = System.getProperty("glorp.emotes")?.let(::File) ?: return
        val css = File(dir, "emotes.css").readText()
        val json = File(dir, "emotes.json").readText()
        val emotes = Emotes.parse(json, css)
        val names = emotes.all.map { it.prefix }.toSet()
        var changed = 0
        val report = StringBuilder()
        for (a in listOf("BLADE", "Clap", "GIGACHAD", "Sippy", "ApeHands", "Chatting", "RapThis", "BERN")) for (b in listOf("BLADE", "Chatting", "ApeHands", "Sippy", "KYSNOW")) {
            if (a !in names || b !in names) continue
            val aloneA = emotes.inLine(LineModel.message(emptyList(), null, false, false, listOf(a))).single()
            val aloneB = emotes.inLine(LineModel.message(emptyList(), null, false, false, listOf(b))).single()
            val pair = emotes.inLine(LineModel.message(emptyList(), null, false, false, listOf(a, b)))
            if (aloneA != pair[0] || aloneB != pair[1]) {
                changed++
                report.append("$a+$b(${pair[0]?.rest?.sx},${pair[1]?.rest?.sx}) ")
            }
        }
        println("Context changes: $changed: $report")
        assertTrue(changed > 0, "no neighbour rule matched anything")
    }
}
