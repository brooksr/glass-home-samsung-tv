package com.rochehome.podcasts

/**
 * YouTube-style shouting in titles ("BREAKING: Trump White House THREATENS Dems after election DISASTER")
 * in ordinary title case ("Breaking: … Threatens … Disaster"), keeping acronyms (AI, UFO, UAP, NFL, IBM).
 *
 * Only titles with two or more shouted words of 4+ letters that aren't acronyms are touched, and in
 * those only runs of shouted words: "Smart Talks with IBM" and "CLASSIC: …" stay as they are. A shouted
 * word becomes Capitalized rather than lower case, so a shouted name ("TRUMP") still reads as one.
 * An acronym is a listed one, 4+ letters without a vowel (TSMC), or a short word that isn't a common
 * English one (IPN). Tried on all 1,900-odd episode titles on 2026-11-12: 282 changed, all of them shouting.
 */
object Headline {
    private val ACRONYMS = (
        "AI AGI UFO UAP NHI US USA UK EU UN NATO NFL NBA MLB " +
        "NHL NCAA FBI CIA NSA DOJ DHS ICE GOP MAGA DOGE NASA ESA CEO " +
        "CFO CBS NBC ABC CNN MSNBC NPR PBS BBC HBO LA NYC DC SF " +
        "JFK MLK RFK LBJ FDR IRS IVF LGBTQ DEI COVID HIV AIDS GDP TV " +
        "NYT WSJ ESPN AOC SCOTUS POTUS FLOTUS VP ID OK II III IV VI " +
        "VII VIII IX XI XII PHD MD DNA RNA ADHD OCD PTSD CPAC OPEC " +
        "IMF CDC FDA EPA FAA FCC SEC FTC ATF DEA TSA NOAA NIH SETI " +
        "MIT UCLA USC LSU OJ UFC WWE WWII WWI AM PM EV EVS GPT " +
        "LLM API SNL MVP QB PGA NASCAR CIF CSU UC IQ ISIS ICBM HAARP " +
        "AARO DOD DOE ACLU NAACP NRA CBP AT&T "
        ).split(' ').filter { it.isNotEmpty() }.toSet()
    private val COMMON = (
        "IT IS OF IN ON AT TO BY OR IF SO NO UP GO " +
        "DO BE ME WE HE MY AN AS SEE WIN RUN HIT WAR LAW " +
        "TAX ACT AGE KEY OLD RED WHO WHY GET SET LET PUT SAY SAW " +
        "BUY PAY USE LOW FEW NOR YET OWN BIG HOW THE AND FOR WHY " +
        "HOW NOW YOU ARE NOT ALL BUT OUT NEW BIG WAS HAS HIS HER " +
        "OUR GET GOT CAN ONE TWO TOO ITS HIM SHE WAY DAY MAN MEN " +
        "END TOP OFF HOT BAD SAD MAD LIE LOL OMG WOW YES WHAT THIS " +
        "THAT WITH YOUR FROM JUST HAVE WILL THEY BEEN WERE INTO OVER EVER NEVER " +
        "MUST "
        ).split(' ').filter { it.isNotEmpty() }.toSet()
    private val PLURAL = mapOf("UFOS" to "UFOs", "UAPS" to "UAPs", "CEOS" to "CEOs", "NGOS" to "NGOs", "AIS" to "AIs", "EVS" to "EVs",
        "LLMS" to "LLMs", "ICBMS" to "ICBMs", "MVPS" to "MVPs")
    private val SMALL = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "nor", "the", "to", "vs", "via",
        "with", "from", "into", "than", "per")
    private val WORD = Regex("[A-Za-z][A-Za-z'’]*")

    private fun shout(w: String) = w.length >= 2 && w.none { it.isLowerCase() }

    private fun known(w: String): Boolean {
        val u = w.uppercase().replace('’', '\'').substringBefore('\'')
        if (u in ACRONYMS || u in PLURAL) return true
        if (u.length >= 4 && u.none { it in "AEIOUY" }) return true
        return u.length <= 3 && u !in COMMON && u.lowercase() !in SMALL
    }

    private fun loud(w: String) = shout(w) && w.length >= 4 && !known(w)

    /** At the start of the title or of a sentence or part ("BREAKING: …", "… | …"). */
    private fun atStart(t: String, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && t[j] in " \"'“‘([") j--
        return j < 0 || t[j] in ":.!?|—–-"
    }

    fun tame(t: String): String {
        val words = WORD.findAll(t).toList()
        if (words.count { loud(it.value) } < 2) return t
        val runs = mutableListOf<List<MatchResult>>()
        var run = mutableListOf<MatchResult>()
        for (m in words) {
            if (shout(m.value)) run.add(m) else { if (run.isNotEmpty()) runs.add(run); run = mutableListOf() }
        }
        if (run.isNotEmpty()) runs.add(run)
        val out = StringBuilder(t)
        for (r in runs) {
            if (r.none { loud(it.value) }) continue
            for (m in r) {
                val w = m.value
                val u = w.uppercase()
                val to = when {
                    u in PLURAL -> PLURAL.getValue(u)
                    known(w) -> w
                    w.lowercase() in SMALL && !atStart(t, m.range.first) -> w.lowercase()
                    else -> w.lowercase().replaceFirstChar { it.uppercase() }
                }
                out.replace(m.range.first, m.range.last + 1, to)   // same length: only the case changes
            }
        }
        return out.toString()
    }
}
