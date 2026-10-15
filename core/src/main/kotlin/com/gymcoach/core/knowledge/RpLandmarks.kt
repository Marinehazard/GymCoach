// core/src/main/kotlin/com/gymcoach/core/knowledge/RpLandmarks.kt
package com.gymcoach.core.knowledge

import com.gymcoach.core.model.Band
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.RpLandmark

/**
 * R4 §4.5 RP volume landmarks (direct-count sets/week, intermediate lifters). The RP "Back" row is stored under LATS and
 * stands for the back aggregate (lats ∪ upper_back); the "Abs" row covers obliques; "Traps" is traps_upper. Muscles
 * without an RP guide (lower_back, adductors, glute_med, rotator_cuff, hip_flexors) are "covered by compounds" with an
 * optional 2–6 direct sets/week cap and never alarm.
 */
object RpLandmarks {

    /** Muscles RP publishes no guide for (R4 §4.5). */
    val NO_GUIDE: Set<Muscle> = setOf(Muscle.LOWER_BACK, Muscle.ADDUCTORS, Muscle.GLUTE_MED, Muscle.ROTATOR_CUFF, Muscle.HIP_FLEXORS)

    /** Optional direct-sets cap for the NO_GUIDE muscles (R4 §4.5). */
    val NO_GUIDE_DIRECT_CAP: Band = Band(2, 6)

    /** The landmark row that applies to [muscle]: UPPER_BACK shares the back aggregate row (LATS), OBLIQUES the abs row. */
    fun rowMuscle(muscle: Muscle): Muscle? = when (muscle) {
        Muscle.UPPER_BACK -> Muscle.LATS
        Muscle.OBLIQUES -> Muscle.ABS
        in NO_GUIDE -> null
        else -> muscle
    }

    fun forMuscle(kb: KnowledgeBase, muscle: Muscle): RpLandmark? = rowMuscle(muscle)?.let { kb.rpLandmarks[it] }

    /** True when the muscle's row is the back aggregate (lats ∪ upper_back counted once with the larger weight, R4 §4.1). */
    fun isBackAggregate(muscle: Muscle): Boolean = muscle == Muscle.LATS || muscle == Muscle.UPPER_BACK

    /** MRV lower bound of the muscle's row (the R4-4.5 soft-cap threshold), null when RP has no guide. */
    fun mrvLo(kb: KnowledgeBase, muscle: Muscle): Int? = forMuscle(kb, muscle)?.mrv?.lo
}
