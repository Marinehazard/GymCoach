// core/src/main/kotlin/com/gymcoach/core/coach/RedFlagScanner.kt
package com.gymcoach.core.coach

import com.gymcoach.core.explain.RuleIds
import java.util.Locale

data class RedFlagHit(val phrase: String, val emergency: Boolean)

/**
 * §6.12 on-device RED-01 pre-send check (no model needed). Case-insensitive EN + DE vocabulary; a phrase matches only
 * at a word start (so "numb" does not fire inside another word) and "number" is never read as "numb". Emergency
 * phrases (chest pain, breathlessness, fainting/dizziness, palpitations — "stop now and call emergency services")
 * win over the other hand-off phrases when both occur.
 */
object RedFlagScanner {
    /** RED-01 hand-off card; POL-02 no diagnosis — red flags hand off to professionals. */
    val RULE_IDS: List<String> = listOf(RuleIds.RED_01, RuleIds.POL_02)

    val EMERGENCY: List<String> = listOf(
        "chest pain", "brustschmerz", "pressure in my chest", "radiating", "short of breath", "atemnot",
        "fainted", "faint", "dizzy", "schwindelig", "palpitations", "herzrasen",
    )
    val REFER: List<String> = listOf(
        "numb", "taub", "tingling", "kribbeln", "can't feel", "bladder", "bowel", "saddle", "hot and swollen", "locked",
        "gave way", "popped", "knacken mit", "cola urine", "dark urine", "dunkler urin", "fever", "fieber", "calf swollen",
        "wade geschwollen", "worst headache",
    )

    fun scan(text: String): RedFlagHit? {
        val t = text.lowercase(Locale.ROOT).replace('’', '\'')
        for (p in EMERGENCY) if (matches(t, p)) return RedFlagHit(p, emergency = true)
        for (p in REFER) if (matches(t, p)) return RedFlagHit(p, emergency = false)
        return null
    }

    private fun matches(t: String, phrase: String): Boolean {
        var from = 0
        while (true) {
            val i = t.indexOf(phrase, from)
            if (i < 0) return false
            val wordStart = i == 0 || !t[i - 1].isLetter()
            val falseFriend = phrase == "numb" && t.startsWith("number", i)
            if (wordStart && !falseFriend) return true
            from = i + 1
        }
    }
}
