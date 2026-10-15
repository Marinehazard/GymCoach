// core/src/main/kotlin/com/gymcoach/core/coach/SupplementCaveat.kt
package com.gymcoach.core.coach

import com.gymcoach.core.explain.RuleIds
import java.util.Locale

/**
 * §6.12 SUP-12 post-check: true when the assistant text names a supplement and lacks the caveat sentence; the UI then
 * appends [TEXT] beneath the message (never into the transcript) and sets `caveatAppended`.
 */
object SupplementCaveat {
    const val TEXT: String = "Not medical advice; check with a physician or pharmacist if you take medication or have a heart, kidney, liver, thyroid or psychiatric condition; prefer third-party-tested products; legal status differs by country."

    /** SUP-12 caveat sentence; POL-03 supplement advice is generic education and always carries it. */
    val RULE_IDS: List<String> = listOf(RuleIds.SUP_12, RuleIds.POL_03)

    val VOCABULARY: List<String> = listOf(
        "creatine", "kreatin", "caffeine", "koffein", "whey", "casein", "protein powder", "beta-alanine", "citrulline",
        "melatonin", "omega-3", "fish oil", "vitamin d", "multivitamin", "electrolyte", "bcaa", "hmb", "glutamine",
        "booster", "fat burner", "sarm", "prohormone", "ashwagandha", "pre-workout",
    )

    fun check(assistantText: String): Boolean {
        val t = assistantText.lowercase(Locale.ROOT)
        if (t.contains("not medical advice")) return false
        return VOCABULARY.any { t.contains(it) }
    }
}
