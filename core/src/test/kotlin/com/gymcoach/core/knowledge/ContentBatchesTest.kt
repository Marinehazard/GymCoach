package com.gymcoach.core.knowledge

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.ContentOverlay
import com.gymcoach.core.model.ContentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC §3.2: for every exercise_content/batch_N.json that EXISTS — batch number matches the file, entries == the R4 §5.12
 * id list of that batch, authoring counts hold, status is draft; across files no id appears twice; Exercise.batch names
 * the batch whose R4 §5.12 list contains the id. A missing batch file is not a failure (the loader keeps those ids todo).
 */
class ContentBatchesTest {
    private val source = ClasspathKnowledgeSource()

    /** R4 §5.12 verbatim. */
    private val r4Batches: Map<Int, List<String>> = mapOf(
        1 to listOf("barbell_bench_press", "dumbbell_bench_press", "smith_bench_press", "machine_chest_press", "incline_barbell_bench_press", "incline_dumbbell_press", "incline_machine_chest_press", "smith_incline_press", "chest_dip", "assisted_dip", "push_up", "push_up_feet_elevated", "dumbbell_floor_press", "cable_fly", "low_to_high_cable_fly", "pec_deck_fly", "dumbbell_fly", "close_grip_bench_press", "cable_triceps_pushdown", "overhead_cable_triceps_extension", "lying_triceps_extension", "dumbbell_overhead_triceps_extension", "machine_triceps_extension", "bench_dip", "diamond_push_up", "band_triceps_pushdown"),
        2 to listOf("overhead_press_barbell", "seated_dumbbell_shoulder_press", "machine_shoulder_press", "smith_shoulder_press", "arnold_press", "landmine_press", "push_press", "pike_push_up", "dumbbell_lateral_raise", "cable_lateral_raise", "machine_lateral_raise", "band_lateral_raise", "face_pull", "reverse_pec_deck", "dumbbell_rear_delt_fly", "cable_rear_delt_fly", "band_pull_apart", "barbell_shrug", "dumbbell_shrug", "cable_external_rotation", "side_lying_dumbbell_external_rotation", "band_external_rotation", "prone_y_raise", "shoulder_pass_through", "wall_slide"),
        3 to listOf("pull_up", "chin_up", "assisted_pull_up", "band_assisted_pull_up", "lat_pulldown", "band_lat_pulldown", "straight_arm_pulldown", "barbell_row", "dumbbell_row", "chest_supported_dumbbell_row", "seated_cable_row", "machine_row", "t_bar_row", "high_row_machine", "inverted_row", "landmine_row", "barbell_curl", "dumbbell_curl", "incline_dumbbell_curl", "hammer_curl", "preacher_curl", "cable_curl", "reverse_curl", "dead_hang", "thoracic_rotation"),
        4 to listOf("back_squat", "front_squat", "safety_bar_squat", "goblet_squat", "smith_machine_squat", "hack_squat", "pendulum_squat", "belt_squat", "leg_press_45", "leg_press_horizontal", "bulgarian_split_squat", "split_squat", "walking_lunge", "reverse_lunge", "step_up", "leg_extension", "sissy_squat", "spanish_squat", "bodyweight_squat", "pistol_squat", "standing_calf_raise", "seated_calf_raise", "leg_press_calf_raise", "single_leg_calf_raise", "hip_90_90", "couch_stretch"),
        5 to listOf("conventional_deadlift", "sumo_deadlift", "trap_bar_deadlift", "romanian_deadlift", "dumbbell_romanian_deadlift", "single_leg_romanian_deadlift", "good_morning", "hip_thrust", "machine_hip_thrust", "glute_bridge", "back_extension_45", "reverse_hyperextension", "glute_ham_raise", "nordic_hamstring_curl", "slider_leg_curl", "seated_leg_curl", "lying_leg_curl", "kettlebell_swing", "cable_pull_through", "cable_glute_kickback", "hip_abduction_machine", "hip_adduction_machine", "copenhagen_plank", "band_lateral_walk", "worlds_greatest_stretch", "ankle_dorsiflexion_rock"),
        6 to listOf("plank", "side_plank", "dead_bug", "bird_dog", "ab_wheel_rollout", "cable_crunch", "machine_crunch", "hanging_leg_raise", "lying_leg_raise", "pallof_press", "cable_woodchop", "farmers_carry", "suitcase_carry", "battle_ropes", "medicine_ball_slam", "box_jump", "sled_push", "treadmill", "stationary_bike", "air_bike", "rowing_erg", "elliptical", "stair_climber", "ski_erg", "jump_rope", "outdoor_run_walk", "cat_cow", "foam_rolling"),
    )

    private val sentenceEnd = Regex("[.!?](?=\\s+[A-Z0-9\"(]|\\s*$)")

    private fun existing(): List<Pair<Int, ContentOverlay>> = (1..6).mapNotNull { n ->
        val path = KnowledgeLoader.batchPath(n)
        if (source.exists(path)) n to CoreJson.assets.decodeFromString(ContentOverlay.serializer(), source.readText(path)) else null
    }

    @Test
    fun r4BatchListsCoverTheLibraryExactlyOnce() {
        val all = r4Batches.values.flatten()
        assertEquals(156, all.size)
        assertEquals(all.toSet(), KnowledgeFixture.kb.exercises.keys)
        assertEquals(listOf(26, 25, 25, 26, 26, 28), (1..6).map { r4Batches.getValue(it).size })
    }

    @Test
    fun exerciseBatchFieldNamesItsR4Batch() {
        for ((n, ids) in r4Batches) for (id in ids) assertEquals(n, KnowledgeFixture.kb.requireExercise(id).batch, id)
    }

    @Test
    fun everyExistingBatchFileMatchesItsNameAndIdList() {
        for ((n, overlay) in existing()) {
            assertEquals(n, overlay.batch, "batch_$n.json declares batch ${overlay.batch}")
            assertEquals(1, overlay.schemaVersion)
            assertEquals(r4Batches.getValue(n).toSet(), overlay.entries.keys, "batch_$n.json ids")
        }
    }

    @Test
    fun noIdAppearsInTwoBatchFiles() {
        val seen = HashMap<String, Int>()
        for ((n, overlay) in existing()) for (id in overlay.entries.keys) {
            val before = seen.put(id, n)
            assertTrue(before == null, "$id in batch $before and batch $n")
        }
    }

    @Test
    fun everyEntrySatisfiesTheAuthoringCounts() {
        for ((n, overlay) in existing()) for ((id, c) in overlay.entries) {
            val where = "batch_$n.json $id"
            val sentences = sentenceEnd.findAll(c.description.trim()).count()
            assertTrue(sentences in 2..4, "$where: description has $sentences sentences")
            assertTrue(c.setup.size in 2..4, "$where: setup ${c.setup.size}")
            assertTrue(c.cues.size in 3..6, "$where: cues ${c.cues.size}")
            assertTrue(c.mistakes.size in 3..5, "$where: mistakes ${c.mistakes.size}")
            assertTrue(c.safety.size in 1..4, "$where: safety ${c.safety.size}")
            assertTrue(c.sources.isNotEmpty(), "$where: no source")
            assertTrue((c.setup + c.cues + c.mistakes + c.safety).all { it.isNotBlank() }, "$where: blank line")
        }
    }

    @Test
    fun statusIsDraftForEveryAuthoredEntry() {
        for ((n, overlay) in existing()) for (id in overlay.entries.keys) {
            assertEquals(ContentStatus.DRAFT, overlay.status[id] ?: ContentStatus.DRAFT, "batch_$n.json $id")
        }
        for ((_, overlay) in existing()) assertTrue(overlay.status.keys.all { it in overlay.entries }, "status for an id without an entry")
    }

    @Test
    fun substitutionRationaleOnlyNamesListedSubstitutions() {
        val kb = KnowledgeFixture.kb
        for ((n, overlay) in existing()) for ((id, c) in overlay.entries) {
            val subs = kb.requireExercise(id).substitutions.toSet()
            for (k in c.substitutionRationale.keys) assertTrue(k in subs, "batch_$n.json $id: rationale for unlisted substitution $k")
        }
    }
}
