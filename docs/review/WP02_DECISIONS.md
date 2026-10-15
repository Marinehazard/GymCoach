# WP-02 decisions — knowledge assets + equipment (2026-10-15)

Scope: `core/src/main/resources/gymcoach/knowledge/*.json` (except the six `exercise_content/batch_N.json`
overlays), the `knowledge` / `equipment` Kotlin packages and their tests. Numbers come from R4 / R3 / SPEC
Appendix C / `PlannerConstants`. **No R4 number was changed.** Every item of
`docs/review/CONTENT_REVIEW_NOTES.md` is listed in part A with the decision; part B lists transcription
decisions WP-02 had to take where the sources are silent or ambiguous.

## A. Content-review notes (all KEPT as printed in R4)

| # | Exercise / topic | Note (short) | Decision | Reason |
|---|---|---|---|---|
| A1 | barbell_shrug | no SL flag although heavy loads go through the arms | kept | Flag, not a safety defect: shrugs are not in the R4 §5.5 SL list; `dumbbell_shrug` is the first substitution anyway. Integrator may add SL later. |
| A2 | side_lying_dumbbell_external_rotation | 1.0 / 0.5 kg not loadable in any preset | kept | `IncrementResolver` uses the gym's `dumbbells.step_kg` (R4 §5.4 "gym parameter overrides"), and the PROG-02 > 10 % rule extends reps first, so nothing silently breaks. |
| A3 | prone_y_raise | same increment problem | kept | Same reason as A2. |
| A4 | band_external_rotation | option lacks `band_anchor_any` | kept | Content describes an un-anchored variant; option as printed is satisfiable. |
| A5 | band_lat_pulldown | C flag + isolation profile; no rear_delts 0.25 | kept | Numbers/flags as printed; profile override 12–20 transcribed. |
| A6 | dumbbell_row | unsupported option without LB | kept | Preferred option is bench-supported; flag not a number. |
| A7 | t_bar_row | 5.0 / 2.5 vs single-sleeve 2.5 / 1.25 | kept | Load type is barbell, so `IncrementResolver` applies the UPPER_BARBELL rule (2.5, 1.0 with microplates) from the gym's plates; the row's 5.0 is not used for the step. |
| A8 | dead_hang | no SH flag | kept | Flag, low risk; R3 MOB-07 uses it as a mobility drill. |
| A9 | good_morning | `rack_any` instead of `rack_or_spotter` | kept | SPEC §3.1 binding decision: good_morning is one of the "RIR floor 2 without safeties" lifts; `KnowledgeValidator` R6 accepts `rack_any`. |
| A10 | reverse_hyperextension | no LB flag | kept | Flag; fixed-trunk machine is the gentler option. |
| A11 | hip_adduction_machine | compound substitutions | kept | Planner keeps difficulty / flag gates when swapping (§4.12). |
| A12 | ankle_dorsiflexion_rock | first substitution couch_stretch | kept | Substitution order is R4 data; mobility drill selection uses `mobility_drills.json`, not substitutions. |
| A13 | hanging_leg_raise | no LB flag | kept | Flag; integrator may add LB. |
| A14 | treadmill, outdoor_run_walk | `KN (running)` conditional flag | kept, `knee_sensitive = true` | Conservative (safety first). The running/walking distinction lives in `cardio_modalities.json` (walk row `is_running=false`, run row `is_running=true`, `knee_impact=true`) and the planner's `CardioKind`; a split into walk/run ids is a v1.1 catalogue change (would need `renamed_ids`). |
| A15 | core_time 30–90 s vs "not beyond ~60 s" | internal R4 tension | kept | Profile transcribed as printed; note kept in `rep_profiles[core_time].notes`. |
| A16 | close_grip_bench_press | Smith option inherits SP | kept | Conservative: `Availability.safetiesOrSpotter` reports `rack_or_spotter` only; a Smith-only gym gets the RIR-2 floor. R6 checks `rack_or_spotter` on every **non-Smith** option of the three bench variants. |
| A17 | low_to_high_cable_fly | "(per side)" only on cable_fly | kept | `cable_fly.increment.per_hand = true` (printed "(per side)"), `low_to_high_cable_fly` false (not printed). |
| A18 | lying_triceps_extension | no SP flag | kept | Flag as printed. |
| A19 | goblet_squat | kettlebell option needs a 4-kg step | resolved in code, data kept | `IncrementResolver` uses `kettlebells.available_kg` (next bell) when the effective load type is KETTLEBELL. |
| A20 | leg_press_horizontal | KN only | kept | Flag. |
| A21 | bulgarian_split_squat / walking_lunge / reverse_lunge | barbell/Smith options without SL | kept | Flag; preferred options are dumbbell. |
| A22 | step_up | adductors 0.25 missing | kept | Muscle weight = number; never changed silently. |
| A23 | pistol_squat | counterweight vs "+DB" | kept | Increment `reps` (note "R4: reps; +DB"), option note "counterweight". |
| A24 | standing_calf_raise | single-leg DB option | kept | Option note "single-leg" transcribed. |
| A25 | format / sources / chapter numbers | overlay format, NSCA chapters | n/a for WP-02 | Overlays already follow SPEC §3.2 (per-entry `review_notes`); `ContentBatchesTest` passes on all six files. |

## B. Transcription decisions

- **D-01 Equipment count.** R4 §2.3 has **78** rows (SPEC §3/§7.5 say 77). All 78 are transcribed; counts in tests use 78. SPEC drift reported.
- **D-02 `half_step_kg`.** R4 prints "default 2.5 if add-on present"; the param is declared **without** a default, so it is
  absent until the user sets it (otherwise every stack would claim a half step). `stack_step_kg` defaults to 5 where R4 prints it.
  Machines without printed params (e.g. `leg_extension_machine`) have none; resolver/rounder fall back to 5 kg.
- **D-03 Other params.** Defaults as printed; where R4 gives a range, the lower value with the range in the description
  (`safety_squat_bar.bar_kg` 25 "25–32"); where R4 gives no default (`plyo_box.heights_cm`, `sled.sled_kg`,
  `medicine_balls.available_kg`, `treadmill.max_incline_pct`) the spec has none. `resistance_bands.levels` is a string list
  (`type: "string_list"`, default light/medium/heavy/x-heavy) — the R4 schema enum lacks a string-list type (drift).
- **D-04 Rule 5 exemptions.** `bird_dog` (abs 0.5, lower_back 0.5, glutes 0.25) and the POWER pattern (R4 §4.3 "movement-specific at 0.5")
  have no weight-1.0 muscle as printed; rule 5 requires 1..2 primaries except for these.
- **D-05 `optional_equipment` tag.** The R4 §6.3 rule-3 list plus `leg_press_horizontal`, `cable_glute_kickback`, `jump_rope`
  (their equipment is ○, not ✓, in `base_gym`). `machine_lateral_raise` carries the tag for R4's "lateral_raise_machine".
- **D-06 `big_lift`.** The six barbell main lifts of §4.5: back_squat, safety_bar_squat, conventional_deadlift, trap_bar_deadlift,
  barbell_bench_press, overhead_press_barbell.
- **D-07 Rep profiles.** "a→b" drifts become `rir [b, a]` + `rir_last_week [b, b]`; the barbell_compound row is the SPEC §3.1 example
  verbatim. R4 prints no RIR for `core_time` (placeholder [2,2], note), `cardio` and `mobility` ([4,4] = "no RIR", note);
  `power` uses [3,4] ("RIR ≥ 3"). Sets bands from SPEC §3.1.
- **D-08 `rep_overrides`.** A parenthesised range in the profile column replaces `range` for **all four** goal keys (rir/sets copied
  from the profile; note "R4 profile column: …"). SPEC §3.1's example `"rir": [2,0]` is not a valid Band (lo > hi) and was not used.
  "cardio (intervals)" / "cardio (HIIT-friendly)" become tags `cardio_intervals` / `cardio_hiit_friendly`.
- **D-09 Increments.** `per_hand = true` for dumbbell/kettlebell load types and for "(per side)". Leading "−" → `direction: assist`;
  `band_assisted_pull_up` ("lighter band = harder") also `assist`. "+2.5 / 1.25" → kg with an "added external load" note.
  `reps; +2.5 …` keeps the number as `default_kg`. "next ball" → kind kg without default.
- **D-10 Progression kinds** per SPEC §3.1; barbell/Smith compounds `linear_load`, barbell isolation `double_progression`,
  `core_reps` follows the load type, `box_jump` `rep_progression`.
- **D-11 Option notes.** Non-load-type parentheses become `note`; every option naming `rack_or_spotter` without a printed note gets
  "Safeties set just below the bottom position, or a spotter." `flags` notes ("KN (running)", "LB (bent-over option)") cannot be
  stored (boolean flags); the flag is set.
- **D-12 Aliases** (EN + DE) were authored by WP-02 (R4 prints none for exercises); search terms only.
- **D-13 Templates.** Priority isolation slots (`FULL_A/B` s6/s7) list the §4.6 priority pattern family (shoulder_abduction … vertical_pull)
  with empty `target_muscles`. Strength variants exactly as §3.4; `UPPER_B_S.s7` keeps target chest/triceps.
- **D-14 rules.json.** Statement = R3 §10 cell verbatim; `url` = first R3 §11 reference of the first cited author/year (absent when §11
  has none). Composite tags normalised per §3.5 with "R3 tag: …" notes. R3 §12 items are not §10 rows, so no rule is `unverified`.
  `user_adjustable` follows the normalised tag for **every** entry, including `ROUNDING` / `OVERRIDE` (SPEC's ROUNDING example says false —
  drift; harmless, they have no constants). Planner-convention statements written from SPEC §4.4–4.9.
- **D-15 cardio_modalities.json.** Treadmill and outdoor_run_walk have a walk row and a run row; `KnowledgeBase.cardioModalities` keys
  the walk row by exercise id and the run row by `"<id>:run"` (`KnowledgeBase.cardioModality(id, running)`). `battle_ropes` (an R4 cardio
  id absent from CAR-08) gets RPE/HR, HIIT high, adjacency good (upper-body dominant, like ski erg) — convention. Swimming has no R4 id.
- **D-16 mobility_drills.json.** File order puts the R4-exercise drills first so that the first dynamic matches per pattern equal the
  SPEC §5.7 lists; drill id == exercise id except `knee_to_wall_pulses` (§3.6 example). `dead_hang` is a dynamic pre-session drill
  with `dose_seconds [20, 30]` (§5.7 "dead_hang (20 s)"). Static drills 30–60 s; strength-through-ROM drills carry no patterns/dose.
- **D-17 supplements.json.** Tier-C rows have ids `SUP-11.1` … `SUP-11.8`; composite evidence tags normalised with the printed tag in `notes`.
- **D-18 nutrition_rules.json.** NUT-14 is split into its seven heuristics; (4) is moderate (Turicchi 2020), the others weak.
  NUT-13 "moderate/weak" → weak.
- **D-19 load_transfer.json.** "any hinge → kettlebell_swing" expanded to every hinge exercise; "bulgarian ↔ reverse/split/walking"
  expanded both ways; "lat_pulldown → pull_up body weight" stored as factor 1.0 with a note; "core cable 0.20" expanded to the four
  core patterns; "any isolation 0.08" expanded to the isolation patterns × {dumbbell (per hand), machine, cable, barbell}.
