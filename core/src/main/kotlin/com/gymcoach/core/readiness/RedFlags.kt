// core/src/main/kotlin/com/gymcoach/core/readiness/RedFlags.kt
package com.gymcoach.core.readiness

data class RedFlagItem(val id: String, val text: String, val emergency: Boolean)

/**
 * RED-01 warning-sign list in §5.2 order (the first four → emergency services). No diagnosis language (POL-02):
 * the texts name what the user notices, the hand-off says whom to contact. Selecting any item ends the session and
 * records Adjustment(kind = SAFETY, ruleIds = [RED-01]) — done by the caller.
 */
object RedFlags {
    val items: List<RedFlagItem> = listOf(
        RedFlagItem("chest_pain", "Chest pain or pressure, possibly spreading to the arm, jaw or back", true),
        RedFlagItem("dyspnoea", "Unusual shortness of breath for the effort", true),
        RedFlagItem("dizziness_fainting", "Dizziness, light-headedness or fainting", true),
        RedFlagItem("palpitations", "Racing or irregular heartbeat together with other symptoms", true),
        RedFlagItem("numbness_weakness", "New numbness, tingling or weakness in an arm or leg", false),
        RedFlagItem("bladder_bowel_saddle", "Loss of bladder or bowel control, or numbness around the groin, with back pain", false),
        RedFlagItem("hot_swollen_locking_joint", "A hot, swollen or locking joint", false),
        RedFlagItem("pop_loss_of_function", "A sudden pop followed by loss of strength or function", false),
        RedFlagItem("severe_headache", "Severe headache that started during lifting", false),
        RedFlagItem("calf_swelling", "Calf swelling or pain together with warmth", false),
        RedFlagItem("dark_urine_muscle_pain", "Dark urine, or extreme muscle pain or swelling", false),
        RedFlagItem("fever_illness", "Fever or feeling generally unwell", false),
    )

    fun handoffText(emergency: Boolean): String =
        if (emergency) "Stop now and call emergency services (112 / 911). Do not continue training."
        else "Stop this session and see a professional now (physician or physiotherapist) before training this area again."
}
