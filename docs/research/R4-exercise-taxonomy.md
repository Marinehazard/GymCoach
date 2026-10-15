# R4 — Equipment taxonomy, movement/muscle taxonomy, exercise library plan

Status: research deliverable, 2026-09-04. Written against `docs/REQUIREMENTS.md`
(offline-first Android app, `:core` pure-Kotlin domain, assets shipped as JSON, metric
default, sacred-content discipline). Everything below is a *plan* for the assets
`exercises.json` and `equipment.json`; the six content-authoring batches (descriptions,
form cues, mistakes, safety notes) are defined in §5.9 so six agents can write them
independently.

How to read this file:

- §1 — sources and what was / was not verified live.
- §2 — equipment taxonomy (IDs, categories, aliases, presets, requirement algebra).
- §3 — movement-pattern taxonomy.
- §4 — muscle taxonomy and the fractional set-counting convention (with citations).
- §5 — exercise library plan: 156 exercises, all fields, six authoring batches.
- §6 — JSON Schema (draft-07) for both asset files, one complete example each.
- §7 — open questions and unverified items.

Conventions used in the tables:

- Equipment requirement notation: `a+b+c ; d+e` means **ANY of** the `;`-separated
  options, each option being an **ALL-of** list joined by `+`. `—` means no equipment
  (always available). An option may carry a load-type override in parentheses, e.g.
  `preacher_bench+dumbbells (dumbbell)`. Items may be concrete equipment IDs, `any_of`
  groups or `all_of` bundles (§2.4).
- Muscle weights: `chest 1.0, front_delts 0.5, triceps 0.5`. A dagger `†` marks rows
  whose target/synergist listing was checked live against the corresponding ExRx.net
  page on 2026-09-04 (§4.4); un-daggered rows are mapped by rule (§4.3) from the same
  joint actions.
- Flags column letters: `C` compound, `SL` spinal_loading, `SH` shoulder_sensitive,
  `KN` knee_sensitive, `LB` low_back_sensitive, `SP` requires_spotter_or_safeties.
- Increment column: `default / smallest-sensible` in kg; `band` = next band level;
  `reps` = progress by repetitions/leverage; `time` = seconds/metres; `n/a` = cardio /
  mobility. A leading `−` marks assisted machines (progress = *less* assistance).
- Profile column = rep/RIR profile ID from §5.2.

---

## 1. Sources and verification status

Fetched live on 2026-09-04 unless stated otherwise.

| # | Source | Used for | Verification |
|---|--------|----------|--------------|
| S1 | ExRx.net exercise pages (Target / Synergists / Dynamic Stabilizers / Stabilizers), e.g. https://exrx.net/WeightExercises/PectoralSternal/BBBenchPress , https://exrx.net/WeightExercises/Quadriceps/BBSquat , https://exrx.net/WeightExercises/ErectorSpinae/BBDeadlift , https://exrx.net/WeightExercises/LatissimusDorsi/BWPullup , https://exrx.net/WeightExercises/Hamstrings/BBStraightLegDeadlift , https://exrx.net/WeightExercises/BackGeneral/BBBentOverRow , https://exrx.net/WeightExercises/DeltoidAnterior/BBMilitaryPress , https://exrx.net/WeightExercises/GluteusMaximus/BBHipThrust , https://exrx.net/WeightExercises/Hamstrings/LVSeatedLegCurl , https://exrx.net/WeightExercises/Hamstrings/LVLyingLegCurl , https://exrx.net/WeightExercises/Quadriceps/LVLegExtension , https://exrx.net/WeightExercises/PectoralSternal/DBFly , https://exrx.net/WeightExercises/DeltoidLateral/DBLateralRaise , https://exrx.net/WeightExercises/Biceps/BBCurl , https://exrx.net/WeightExercises/Triceps/CBPushdown , https://exrx.net/WeightExercises/Gastrocnemius/BBStandingCalfRaise , https://exrx.net/WeightExercises/Soleus/LVSeatedCalfRaise , https://exrx.net/WeightExercises/BackGeneral/CBSeatedRow , https://exrx.net/WeightExercises/RectusAbdominis/CBKneelingCrunch , https://exrx.net/WeightExercises/Obliques/CBTwist , https://exrx.net/WeightExercises/Quadriceps/DBLunge , https://exrx.net/WeightExercises/Quadriceps/BBSplitSquat , https://exrx.net/WeightExercises/Quadriceps/BWStepUp , https://exrx.net/WeightExercises/Quadriceps/BBFrontSquat , https://exrx.net/WeightExercises/Quadriceps/SLHackSquat , https://exrx.net/WeightExercises/LatissimusDorsi/CBFrontPulldown , https://exrx.net/WeightExercises/ErectorSpinae/WtHyperextension , https://exrx.net/WeightExercises/DeltoidPosterior/DBRearLateralRaise , https://exrx.net/WeightExercises/PectoralSternal/BWChestDip , https://exrx.net/WeightExercises/HipAbductor/LVSeatedHipAbduction , https://exrx.net/WeightExercises/HipAdductors/LVSeatedHipAdduction , https://exrx.net/WeightExercises/PectoralSternal/LVChestPress , https://exrx.net/WeightExercises/DeltoidAnterior/LVShoulderPress , https://exrx.net/WeightExercises/GluteusMaximus/CBGluteKickBack , https://exrx.net/WeightExercises/PectoralClavicular/BBInclineBenchPress , https://exrx.net/WeightExercises/PectoralSternal/DBBenchPress , https://exrx.net/WeightExercises/Triceps/BBCloseGripBenchPress | Muscle mapping (§4.3, §4.4) | Fetched live with curl (site blocks the WebFetch agent with 403). Listings transcribed in §4.4. |
| S2 | Pelland JC, Remmert JF, Robinson ZP, Hinson SR, Zourdos MC. *The Resistance Training Dose Response: Meta-Regressions Exploring the Effects of Weekly Volume and Frequency on Muscle Hypertrophy and Strength Gains.* Sports Medicine 56(2):481–505, published online 2025-12-04. DOI 10.1007/s40279-025-02344-w. https://link.springer.com/article/10.1007/s40279-025-02344-w ; PubMed https://pubmed.ncbi.nlm.nih.gov/41343037/ | Fractional set-counting convention (§4.2) | Citation verified via Crossref API and Europe PMC (abstract text quoted in §4.2). Full text not read. |
| S3 | Renaissance Periodization, *Training Volume Landmarks for Muscle Growth* — https://rpstrength.com/blogs/articles/training-volume-landmarks-muscle-growth | RP's direct-count convention; MV/MEV/MAV/MRV definitions | Fetched live; quotes in §4.2. |
| S4 | RP per-muscle hypertrophy guides (hub: https://rpstrength.com/blogs/articles/hypertrophy-training-guide-central-hub): chest, back, quads, hamstrings, glutes, biceps, triceps, front/side/rear delts, calves, abs, traps, forearms — all at `https://rpstrength.com/blogs/articles/<muscle>-hypertrophy-training-tips` | Volume landmarks table (§4.5), rep-range and RIR guidance (§5.2) | 14 pages fetched with curl and the landmark tables parsed from HTML directly (numbers in §4.5 are verbatim). The *back* page embeds its table as an image (`back1.png`); that image was downloaded and read visually — a WebFetch text summary of the same page had given different (wrong) numbers, so never trust summarised numbers for that page. |
| S5 | Stronger by Science, *Overshooting, Undershooting, Or Just Right? How To Perfect Your Ability To Predict Repetitions In Reserve* — https://www.strongerbyscience.com/reps-in-reserve/ | RIR guidance (§5.2): people under-predict RIR by ≈1 rep; take an occasional last set to failure where safe | Fetched live with curl. |
| S6 | Stronger by Science, *The "Hypertrophy Rep Range" – Fact or Fiction?* — https://www.strongerbyscience.com/hypertrophy-range-fact-fiction/ | Rep-range policy (§5.2): similar growth 30–85 % 1RM when sets are near failure | Fetched live with curl. Key sentence: as long as sets are taken to failure or near failure, growth is very similar across rep ranges — "There is no 'hypertrophy zone.'" |
| S7 | NSCA, *Essentials of Strength Training and Conditioning*, ed. G. G. Haff & N. T. Triplett. 4th ed., Human Kinetics 2016 (the widely cited edition); 5th ed. listed by Human Kinetics (ISBN 9781718216273, product page shows ©2027, chapters 16 "Exercise Technique for Free Weight and Machine Training", 17 "Exercise Technique for Alternative Modes and Nontraditional Implement Training", 18 "Program Design for Resistance Training") — https://us.humankinetics.com/products/essentials-of-strength-training-and-conditioning-5th-edition-with-hkpropel-access | Exercise-technique conventions (spotting rules, safeties, breathing) for the authoring batches | Book text NOT fetched; only the product page. The 4th-edition chapter numbers quoted in §5.9 are from memory and must be checked against the physical book before being cited in-app. |
| S8 | Human Kinetics / vendor pages on weight-stack increments (e.g. Body-Solid 2.5 lb stack adapter plate; Life Fitness stacks with 10 lb / 5 kg plates) | Default increments for machines (§5.4) | Secondary web sources via search only; treated as *defaults*, overridable per gym. |

Not verified live (flagged where used): typical dumbbell rack steps (2 kg in most EU
commercial racks, 2.5 kg in others, 1 kg below 10 kg), kettlebell steps (4 kg), plate
sets including 1.25 kg. These are field conventions and are exposed as per-gym
parameters, so a wrong default costs the user one edit, never a silently wrong number.

---

## 2. Equipment taxonomy

### 2.1 Categories

| category | meaning |
|----------|---------|
| `free_weights` | bars, plates, dumbbells, kettlebells |
| `racks_benches` | benches, racks, Smith machine, preacher bench |
| `cables` | cable stacks and cable-based machines plus their attachments |
| `machines_upper` | selectorized/plate-loaded machines for chest, shoulders, arms, back, torso |
| `machines_lower` | machines for hips, thighs, calves, lower back |
| `bodyweight_stations` | bars, dip stations, hyper/GHD benches, boxes, suspension trainer, landmine, sled, ropes, balls, mats |
| `cardio` | cardio machines and jump rope |
| `accessories` | bands, anchors, rollers, straps, belts, ab wheel, ankle strap, and the `spotter` session flag |

### 2.2 Requirement algebra

An exercise carries `equipment_options: [ {all_of:[...]}, {all_of:[...]}, ... ]`.
The exercise is *available* in a gym set if **at least one** option has **all** of its
items satisfied. An item is satisfied if:

1. it is a concrete equipment ID present in the gym set, or
2. it is an `any_of` group and at least one member is present, or
3. it is an `all_of` bundle and every member is present, or
4. it is a session flag (`spotter`) that the user toggled on for today's session.

Parameters (dumbbell max kg, stack step) do not affect *availability*; they affect
*progression* (§5.4) and produce an explicit "dumbbells max 20 kg — prescribed 24 kg"
warning with a ranked substitution instead of silently changing the prescription
(sacred-content rule from REQUIREMENTS).

`all_of: []` (empty) is legal and means "no equipment needed" (bodyweight / mobility).

### 2.3 Equipment table

Columns: `base` = default "base gym" set (typical well-equipped commercial gym),
`home` = typical home gym (garage rack setup; Johannes' real set is unknown and will be
entered by him), `hotel` = typical hotel gym, `kit` = packable travel kit. `✓` in the
default set, `○` common but not default (user adds), blank = rare.

| id | display name | category | aliases | base | home | hotel | kit | params / notes |
|----|--------------|----------|---------|:----:|:----:|:-----:|:---:|----------------|
| `barbell` | Olympic barbell | free_weights | straight bar, Olympic bar, 20 kg bar, Langhantel | ✓ | ✓ | | | `bar_kg` (default 20; 15 kg women's bar exists) |
| `ez_bar` | EZ curl bar | free_weights | EZ bar, curl bar, SZ-Stange | ✓ | ○ | | | `bar_kg` (default 10; varies 7–12) |
| `trap_bar` | Trap / hex bar | free_weights | hex bar, Hexbar | ✓ | | | | `bar_kg` (default 25; open vs closed irrelevant) |
| `safety_squat_bar` | Safety squat bar | free_weights | SSB, Yoke bar | ○ | | | | `bar_kg` (default 25–32 depending on model) |
| `fixed_weight_barbells` | Fixed-weight barbells | free_weights | pre-loaded bars, fixed EZ bars, Fixhanteln | ✓ | | ○ | | `max_kg` (default 40), `step_kg` (default 2.5 or 5) |
| `plates` | Olympic plates | free_weights | discs, bumper plates, Hantelscheiben | ✓ | ✓ | | | `smallest_plate_kg` (default 1.25), `has_microplates` (0.5 kg or less; default false) |
| `dumbbells` | Dumbbells | free_weights | DBs, hex dumbbells, Kurzhanteln, adjustable dumbbells | ✓ | ✓ | ✓ | | **`max_kg`** (base 50, home 32, hotel 20), `step_kg` (base 2, home 2, hotel 2), `min_kg` (default 1 or 2) |
| `kettlebells` | Kettlebells | free_weights | KB, Kugelhantel | ✓ | ✓ | ○ | | `available_kg` list (default 8,12,16,20,24,28,32) |
| `flat_bench` | Flat bench | racks_benches | bench, Flachbank | ✓ | ○ | ○ | | |
| `incline_bench` | Fixed incline bench | racks_benches | incline bench | ○ | | | | |
| `decline_bench` | Decline bench | racks_benches | decline bench, sit-up board | ○ | | | | |
| `adjustable_bench` | Adjustable bench | racks_benches | incline/decline bench, FID bench, verstellbare Bank | ✓ | ✓ | ✓ | | satisfies flat, incline and (usually) decline |
| `power_rack` | Power rack / cage | racks_benches | cage, full rack, Power-Rack | ✓ | ✓ | | | `has_safeties` (default true), `has_pullup_bar` (default true) |
| `half_rack` | Half rack with spotter arms | racks_benches | half cage | ○ | ○ | | | `has_safeties` (default true) |
| `squat_rack` | Squat stand / rack without safeties | racks_benches | squat stands, J-hooks only | ○ | | | | does **not** satisfy `rack_or_spotter` |
| `smith_machine` | Smith machine | racks_benches | Multipresse, guided bar | ✓ | | ○ | | `bar_kg` (counterbalanced 0–10; default 10), `is_angled` (default false) |
| `preacher_bench` | Preacher curl bench | racks_benches | Scott bench, Scott-Curl-Bank | ✓ | | | | |
| `cable_single` | Single adjustable cable column | cables | single pulley, Kabelzug | ○ | | ○ | | `stack_step_kg` (default 5), `half_step_kg` (default 2.5 if add-on present) |
| `cable_dual` | Dual adjustable pulleys / crossover | cables | cable crossover, dual pulley, Kabelzugstation | ✓ | | ○ | | same params |
| `functional_trainer` | Functional trainer (dual stack, multi-position) | cables | FT, multi-gym cable station | ○ | ○ | ○ | | same params; `has_pullup_bar` (default false) |
| `lat_pulldown_machine` | Lat pulldown | cables | pulldown, Latzug | ✓ | | ○ | | `stack_step_kg` 5 / `half_step_kg` 2.5 |
| `seated_cable_row_machine` | Seated cable row (low row) | cables | low pulley row, Ruderzug | ✓ | | ○ | | same |
| `cable_rope` | Rope attachment | cables | triceps rope, Seilzug-Griff | ✓ | | ○ | | assumed present wherever cables are; listed so hotel FT without rope can be modelled |
| `chest_press_machine` | Chest press machine | machines_upper | seated chest press, Brustpresse | ✓ | | ○ | | `stack_step_kg` 5 / `half_step_kg` 2.5; plate-loaded variants: `plate_loaded` true |
| `incline_chest_press_machine` | Incline chest press machine | machines_upper | incline press, Schrägbankpresse | ○ | | | | same |
| `pec_deck_machine` | Pec deck / rear delt fly | machines_upper | butterfly, reverse fly machine, Butterfly | ✓ | | | | `has_rear_delt_mode` (default true) |
| `shoulder_press_machine` | Shoulder press machine | machines_upper | seated overhead press machine, Schulterpresse | ✓ | | ○ | | |
| `lateral_raise_machine` | Lateral raise machine | machines_upper | side delt machine, Seitheben-Maschine | ○ | | | | |
| `preacher_curl_machine` | Preacher curl machine | machines_upper | biceps machine, Bizepsmaschine | ○ | | | | |
| `triceps_machine` | Triceps extension / seated dip machine | machines_upper | triceps press, Dip-Maschine, Trizepsmaschine | ○ | | | | |
| `chest_supported_row_machine` | Chest-supported row machine | machines_upper | seated row machine, iso-lateral row, Rudermaschine | ✓ | | | | `plate_loaded` |
| `high_row_machine` | High row machine | machines_upper | iso-lateral high row, Hammer high row | ○ | | | | |
| `t_bar_row_station` | T-bar row | machines_upper | T-bar, chest-supported T-bar | ○ | | | | |
| `assisted_pullup_dip_machine` | Assisted pull-up / dip | machines_upper | Gravitron, assisted chin/dip, Klimmzug-Hilfe | ✓ | | ○ | | `stack_step_kg` 5 (assist) |
| `ab_crunch_machine` | Ab crunch machine | machines_upper | abdominal machine, Bauchmaschine | ○ | | | | |
| `leg_press_45` | 45° leg press | machines_lower | plate-loaded leg press, sled leg press, Beinpresse | ✓ | | | | `plate_loaded` true; `sled_kg` (default 40–75, unknown → 0 and note) |
| `leg_press_horizontal` | Seated / horizontal leg press | machines_lower | selectorized leg press, seated leg press | ○ | | ○ | | `stack_step_kg` 5 |
| `hack_squat_machine` | Hack squat | machines_lower | hack squat sled, Hackenschmidt | ✓ | | | | `plate_loaded` |
| `pendulum_squat_machine` | Pendulum squat | machines_lower | pendulum | | | | | rare; user adds |
| `belt_squat_machine` | Belt squat | machines_lower | belt squat, hip belt squat | | | | | rare |
| `leg_extension_machine` | Leg extension | machines_lower | Beinstrecker | ✓ | | ○ | | |
| `seated_leg_curl_machine` | Seated leg curl | machines_lower | Beinbeuger sitzend | ✓ | | ○ | | |
| `lying_leg_curl_machine` | Lying leg curl | machines_lower | prone leg curl, Beinbeuger liegend | ○ | | | | |
| `hip_thrust_machine` | Hip thrust machine | machines_lower | glute drive, Hip-Thrust-Maschine | ○ | | | | |
| `glute_kickback_machine` | Glute kickback machine | machines_lower | standing glute machine, Glute-Kickback | ○ | | | | |
| `hip_abduction_machine` | Hip abduction machine | machines_lower | abductor, outer thigh, Abduktor | ✓ | | | | often a combined ab/adductor machine |
| `hip_adduction_machine` | Hip adduction machine | machines_lower | adductor, inner thigh, Adduktor | ✓ | | | | |
| `standing_calf_raise_machine` | Standing calf raise | machines_lower | calf machine, Wadenmaschine stehend | ✓ | | | | |
| `seated_calf_raise_machine` | Seated calf raise | machines_lower | Wadenmaschine sitzend | ✓ | | | | |
| `reverse_hyper_machine` | Reverse hyper | machines_lower | reverse hyperextension | | | | | rare |
| `pull_up_bar` | Pull-up bar | bodyweight_stations | chin-up bar, Klimmzugstange | ✓ | ✓ | ○ | | racks with a bar satisfy `pullup_bar_any` via param |
| `dip_station` | Dip station / parallel bars | bodyweight_stations | dip bars, Dipstation | ✓ | ○ | | | |
| `back_extension_45` | 45° back extension bench | bodyweight_stations | hyperextension bench, Roman chair, Rückenstrecker | ✓ | | | | |
| `ghd` | Glute-ham developer | bodyweight_stations | GHD, glute-ham bench | ○ | | | | |
| `plyo_box` | Plyo box / step | bodyweight_stations | box, step platform, Sprungbox | ✓ | ○ | ○ | | `heights_cm` list |
| `suspension_trainer` | Suspension trainer | bodyweight_stations | TRX, rings, Schlingentrainer | ○ | ○ | ○ | ✓ | packable |
| `landmine` | Landmine attachment | bodyweight_stations | landmine, T-bar pivot, corner | ✓ | ○ | | | a bar wedged in a corner counts; needs `barbell`+`plates` |
| `sled` | Prowler / sled | bodyweight_stations | prowler, push sled, turf | ○ | | | | needs `plates`; `sled_kg` |
| `battle_ropes` | Battle ropes | bodyweight_stations | ropes | ○ | | | | |
| `medicine_balls` | Medicine / slam balls | bodyweight_stations | med ball, slam ball, wall ball, Medizinball | ○ | | ○ | | `available_kg` list |
| `mat` | Exercise mat | bodyweight_stations | yoga mat, Matte | ✓ | ✓ | ✓ | | never a hard requirement — floor works |
| `treadmill` | Treadmill | cardio | Laufband | ✓ | | ✓ | | `max_incline_pct` |
| `stationary_bike` | Stationary / spin bike | cardio | upright bike, recumbent bike, spin bike, Ergometer | ✓ | | ✓ | | |
| `air_bike` | Air bike | cardio | fan bike, Assault bike, Echo bike | ○ | | | | |
| `rower` | Rowing ergometer | cardio | rower, Concept2, Rudergerät | ✓ | | ○ | | |
| `elliptical` | Elliptical / cross-trainer | cardio | Crosstrainer | ✓ | | ✓ | | |
| `stair_climber` | Stair climber | cardio | StairMaster, stepmill, Treppensteiger | ○ | | ○ | | |
| `ski_erg` | Ski erg | cardio | SkiErg | ○ | | | | |
| `jump_rope` | Jump rope | cardio | skipping rope, speed rope, Springseil | ○ | ✓ | ○ | ✓ | packable |
| `resistance_bands` | Resistance bands | accessories | loop bands, mini bands, long bands, Widerstandsbänder | ✓ | ✓ | ○ | ✓ | `levels` list (e.g. light/medium/heavy/x-heavy) |
| `band_door_anchor` | Band door anchor | accessories | door anchor | | ○ | | ✓ | packable; enables cable-like band work in hotel rooms |
| `foam_roller` | Foam roller | accessories | roller, Faszienrolle | ✓ | ✓ | ○ | | |
| `ab_wheel` | Ab wheel | accessories | ab roller | ○ | ○ | | | |
| `dip_belt` | Dip / weight belt | accessories | weight belt with chain | ○ | ○ | | | for loading pull-ups/dips with `plates` |
| `ankle_strap` | Cable ankle strap | accessories | ankle cuff | ○ | | | | for cable kickbacks / abduction |
| `lifting_straps` | Lifting straps | accessories | straps, Zughilfen | | | | ✓ | user's own; **flag** used by descriptions (grip-limited pulls) |
| `lifting_belt` | Lifting belt | accessories | belt, Gewichthebergürtel | | | | | user's own; **flag** used by heavy-lift descriptions |
| `spotter` | Spotter available (session flag) | accessories | training partner | | | | | `session_flag: true`; satisfies `rack_or_spotter` |

### 2.4 Groups (`any_of`) and bundles (`all_of`)

| id | kind | members | purpose |
|----|------|---------|---------|
| `bench_flat_any` | any_of | flat_bench, adjustable_bench | any bench usable flat |
| `bench_incline_any` | any_of | incline_bench, adjustable_bench | any bench usable at 30–45° |
| `bench_any` | any_of | flat_bench, incline_bench, decline_bench, adjustable_bench | any bench (support / step) |
| `rack_any` | any_of | power_rack, half_rack, squat_rack | somewhere to unrack a bar |
| `rack_or_spotter` | any_of | power_rack, half_rack, spotter | safeties **or** a human spotter for bar-over-body lifts |
| `cable_any` | any_of | cable_single, cable_dual, functional_trainer | at least one adjustable pulley |
| `cable_dual_any` | any_of | cable_dual, functional_trainer | two pulleys (flyes, crossovers) |
| `pullup_bar_any` | any_of | pull_up_bar, power_rack, assisted_pullup_dip_machine | something to hang from (resolver honours `power_rack.has_pullup_bar`) |
| `dip_any` | any_of | dip_station, assisted_pullup_dip_machine | parallel dip handles |
| `leg_press_any` | any_of | leg_press_45, leg_press_horizontal | for calf presses |
| `hyper_any` | any_of | back_extension_45, ghd | hip-hinge back extension |
| `step_any` | any_of | plyo_box, flat_bench, adjustable_bench | a knee-high platform |
| `band_anchor_any` | any_of | band_door_anchor, power_rack, half_rack, squat_rack, pull_up_bar | a fixed point to loop a band around |
| `bb_loaded` | all_of | barbell, plates | loaded straight bar |
| `ez_loaded` | all_of | ez_bar, plates | loaded EZ bar |
| `trap_loaded` | all_of | trap_bar, plates | loaded trap bar |
| `ssb_loaded` | all_of | safety_squat_bar, plates | loaded SSB |
| `landmine_loaded` | all_of | landmine, barbell, plates | landmine setup |
| `sled_loaded` | all_of | sled, plates | loadable sled |

### 2.5 Presets

Presets are seed data for the user's named gym sets ("Harry's gym", "Home gym",
"Hotel gym"); the user edits from there.

- **base_gym** (every `✓` in the `base` column): barbell, ez_bar, trap_bar,
  fixed_weight_barbells, plates (1.25 kg smallest), dumbbells (max 50, step 2),
  kettlebells, flat_bench, adjustable_bench, power_rack, smith_machine, preacher_bench,
  cable_dual, lat_pulldown_machine, seated_cable_row_machine, cable_rope,
  chest_press_machine, pec_deck_machine, shoulder_press_machine,
  chest_supported_row_machine, assisted_pullup_dip_machine, leg_press_45,
  hack_squat_machine, leg_extension_machine, seated_leg_curl_machine,
  hip_abduction_machine, hip_adduction_machine, standing_calf_raise_machine,
  seated_calf_raise_machine, pull_up_bar, dip_station, back_extension_45, plyo_box,
  landmine, mat, treadmill, stationary_bike, rower, elliptical, resistance_bands,
  foam_roller.
- **home_gym** (typical garage setup; placeholder until Johannes enters his real set):
  barbell, plates (1.25), dumbbells (adjustable, max 32, step 2), kettlebells (16, 24),
  adjustable_bench, power_rack (pull-up bar), pull_up_bar, mat, resistance_bands,
  foam_roller, jump_rope. Common additions: ez_bar, dip_station, suspension_trainer,
  landmine, plyo_box, band_door_anchor.
- **hotel_gym**: dumbbells (max 20, step 2), adjustable_bench, mat, treadmill,
  stationary_bike, elliptical. Common additions: functional_trainer or cable_single (with
  or without `cable_rope`), smith_machine, leg_press_horizontal, chest_press_machine,
  shoulder_press_machine, lat_pulldown_machine, seated_cable_row_machine,
  leg_extension_machine, seated_leg_curl_machine, assisted_pullup_dip_machine,
  kettlebells, medicine_balls, rower, stair_climber, suspension_trainer, plyo_box.
- **travel_kit** (packable, merged into any gym for the day): resistance_bands,
  band_door_anchor, jump_rope, suspension_trainer, lifting_straps.

### 2.6 Why parameters matter for the planner

- `dumbbells.max_kg` gates heavy dumbbell compounds: if the prescribed load exceeds
  `max_kg`, the planner shows the conflict and offers the top substitution (e.g. hotel
  20 kg dumbbells → `dumbbell_romanian_deadlift` becomes `single_leg_romanian_deadlift`
  or a slower tempo/rep-range shift, presented as a proposal, never applied silently).
- `dumbbells.step_kg`, `plates.smallest_plate_kg`, `cable_*.stack_step_kg` and
  `half_step_kg` override the exercise's default increment (§5.4).
- `power_rack.has_safeties` decides whether `rack_or_spotter` is satisfied; a rack
  without safeties is a `squat_rack`.

---

## 3. Movement-pattern taxonomy

| pattern id | definition | examples |
|------------|------------|----------|
| `squat` | bilateral knee-dominant, hips and knees flex together | back squat, leg press, hack squat |
| `hinge` | hip-dominant, knees near-fixed, trunk inclines | deadlift, RDL, good morning, swing |
| `lunge_split` | unilateral / split-stance knee-dominant | Bulgarian split squat, lunge, step-up |
| `horizontal_push` | press away from torso in the transverse/flat plane | bench press, push-up, dip, floor press |
| `incline_push` | press at 15–45° above horizontal | incline press, feet-elevated push-up |
| `vertical_push` | press overhead | OHP, machine press, landmine press, pike push-up |
| `chest_fly` | shoulder horizontal adduction with near-fixed elbow | cable fly, pec deck, DB fly |
| `horizontal_pull` | row toward the torso | barbell row, cable row, machine row, inverted row |
| `vertical_pull` | pull from overhead | pull-up, chin-up, pulldown, straight-arm pulldown |
| `elbow_flexion` | biceps/brachialis isolation | curls |
| `elbow_extension` | triceps isolation | pushdown, extensions, bench dip |
| `shoulder_abduction` | side delt raise | lateral raises |
| `shoulder_er_rear_delt` | rear delt / external rotation / scapular retraction | face pull, reverse fly, ER, pull-apart, Y-raise |
| `scapular_elevation` | shrugs | barbell / dumbbell shrug |
| `hip_extension_isolation` | glute-focused hip extension without heavy knee involvement | hip thrust, bridge, kickback, back extension, reverse hyper |
| `hip_abduction` | glute med / abductors | abduction machine, band walk |
| `hip_adduction` | adductors | adduction machine, Copenhagen plank |
| `knee_flexion_isolation` | hamstrings via knee | leg curls, Nordic, GHR, slider curl |
| `knee_extension_isolation` | quads via knee | leg extension, sissy squat, Spanish squat |
| `calf` | plantar flexion | all calf raises |
| `core_anti_extension` | resist lumbar extension | plank, dead bug, ab wheel |
| `core_anti_rotation` | resist rotation / lateral flexion | Pallof press, side plank, bird dog, suitcase carry family |
| `core_flexion` | trunk flexion | crunches, leg raises |
| `core_rotation` | controlled rotation | woodchop |
| `carry` | loaded locomotion or loaded hold | farmer's / suitcase carry, sled push |
| `grip` | grip / wrist isolation and hangs | dead hang, reverse curl (secondary) |
| `power` | explosive, velocity-focused | box jump, med-ball slam, push press |
| `cardio` | steady-state or interval conditioning | all cardio machines, jump rope, battle ropes |
| `mobility` | joint range-of-motion / warm-up drills | pass-through, 90/90, cat-cow, foam rolling |

Planner use: a "balanced week" template covers squat, hinge, lunge_split,
horizontal_push, vertical_push, horizontal_pull, vertical_pull at least once and adds
isolation patterns by goal; warm-ups pull from `mobility` plus one light set of the
session's first pattern (REQUIREMENTS §8).

---

## 4. Muscle-group taxonomy and fractional set counting

### 4.1 Muscle IDs

| muscle id | display | ExRx anatomical names covered |
|-----------|---------|-------------------------------|
| `chest` | Chest | Pectoralis Major, Sternal + Clavicular |
| `front_delts` | Front delts | Deltoid, Anterior (+ Coracobrachialis) |
| `side_delts` | Side delts | Deltoid, Lateral (+ Supraspinatus as synergist) |
| `rear_delts` | Rear delts | Deltoid, Posterior |
| `rotator_cuff` | Rotator cuff | Infraspinatus, Teres Minor, Supraspinatus, Subscapularis |
| `lats` | Lats | Latissimus Dorsi, Teres Major |
| `upper_back` | Upper back | Trapezius Middle/Lower, Rhomboids, Levator Scapulae; ExRx "Back, General" |
| `traps_upper` | Upper traps | Trapezius, Upper |
| `lower_back` | Lower back | Erector Spinae, Quadratus Lumborum (extension role) |
| `biceps` | Biceps | Biceps Brachii, Brachialis |
| `triceps` | Triceps | Triceps Brachii |
| `forearms` | Forearms / grip | Brachioradialis, Wrist Flexors, Wrist Extensors |
| `abs` | Abs | Rectus Abdominis |
| `obliques` | Obliques | Obliques (int/ext), QL (lateral-flexion role) |
| `hip_flexors` | Hip flexors | Iliopsoas, Rectus Femoris (hip-flexion role), TFL |
| `quads` | Quads | Quadriceps |
| `hamstrings` | Hamstrings | Hamstrings |
| `glutes` | Glutes | Gluteus Maximus |
| `glute_med` | Glute med / abductors | Gluteus Medius, Gluteus Minimus, TFL (abduction role) |
| `adductors` | Adductors | Adductor Magnus/Longus/Brevis, Gracilis, Pectineus |
| `calves` | Calves | Gastrocnemius, Soleus |

Aggregate views for landmark comparison (computed, not stored): `back` = lats ∪
upper_back (a set counts once, with the larger of the two weights); `delts` =
front + side + rear; `arms` = biceps + triceps; `legs` = quads + hamstrings + glutes.

### 4.2 Counting convention (state of the evidence)

**Convention adopted: fractional counting — primary mover 1.0, secondary 0.5, minor
0.25; stabilizers 0.** Weekly volume per muscle = Σ (working sets × weight). Warm-up
sets count 0.

Why this and not "direct only":

- Pelland et al. 2025 (S2) classified every set in 67 studies (2 058 participants) as
  direct or indirect and modelled indirect sets as 1 ("total"), 0.5 ("fractional") or 0
  ("direct"). Abstract, verbatim: "The relative evidence for the 'fractional'
  quantification method was strongest; therefore, this quantification method was used
  for the primary meta-regression models." and "Distinguishing between direct and
  indirect sets appears essential for predicting adaptations to a given resistance
  training protocol, such as using the 'fractional' quantification method."
- Renaissance Periodization deliberately counts **direct sets only** and bakes the
  indirect stimulus into lower landmarks: "we only count sets where the target muscle is
  the prime mover or isolation exercises specifically targeting that muscle" and "We've
  already factored in the indirect volume from pressing movements, reducing our
  estimates to account for this additional stimulus." (S3). Their guides say the same
  per muscle, e.g. front delts: "The front delts get most or even all of the stimulus
  they require from chest exercises." (S4 front-delt guide); biceps: "The more back
  training you perform, the less direct bicep work you may need." (S4 biceps guide).
- The two systems are consistent as long as the app never compares fractional totals
  against RP's direct-count landmarks. Therefore the volume screen shows **both**
  numbers per muscle: `direct` (sum of weight-1.0 sets, comparable to §4.5) and
  `fractional` (Pelland-style, used by the planner's balance heuristics). The
  0.25 tier is our own refinement (the meta-analysis only had 1/0.5/0); it is used
  sparingly for muscles that clearly work but are neither prime mover nor a
  conventional "secondary" (e.g. hamstrings in a squat, lower back in a row) so that
  they are not double-counted as full secondaries.

Worked examples (both views):

| exercise | fractional per set | direct per set |
|----------|--------------------|----------------|
| barbell_bench_press | chest 1.0, front_delts 0.5, triceps 0.5 | chest 1 |
| back_squat | quads 1.0, glutes 0.5, adductors 0.5, hamstrings 0.25, lower_back 0.25 | quads 1 |
| conventional_deadlift | glutes 1.0, lower_back 1.0, hamstrings 0.5, quads 0.5, upper_back 0.25, forearms 0.25 | glutes 1, lower_back 1 |
| seated_cable_row | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5, lower_back 0.25 | lats 1, upper_back 1 (back aggregate = 1) |
| lat_pulldown | lats 1.0, upper_back 0.5, biceps 0.5, rear_delts 0.25 | lats 1 |

### 4.3 Mapping rules (applied uniformly; the daggered rows in §5 are the anchors)

| pattern | primary 1.0 | secondary 0.5 | minor 0.25 |
|---------|-------------|---------------|------------|
| horizontal_push (flat) | chest | front_delts, triceps | abs (push-up family only) |
| horizontal_push (close grip / floor) | triceps | chest, front_delts (close-grip) | front_delts (floor press) |
| incline_push | chest | front_delts, triceps | — |
| vertical_push | front_delts | side_delts, triceps | upper_back (barbell OHP), chest (landmine) |
| chest_fly | chest | front_delts (low-to-high only) | front_delts |
| horizontal_pull (elbows in, neutral/under grip) | lats, upper_back | biceps, rear_delts | lower_back (unsupported versions) |
| horizontal_pull (elbows out / high row / inverted) | upper_back | lats, biceps, rear_delts | abs (inverted) |
| vertical_pull | lats | upper_back, biceps | rear_delts |
| elbow_flexion | biceps | forearms (hammer/reverse) | forearms (supinated) |
| elbow_extension | triceps | chest (close-grip push-up) | chest, front_delts (dips) |
| shoulder_abduction | side_delts | — | traps_upper |
| shoulder_er_rear_delt | rear_delts (or rotator_cuff for ER, upper_back for Y-raise) | upper_back, rotator_cuff (face pull) | rear_delts (ER) |
| squat (bilateral) | quads | glutes, adductors (barbell/SSB/belt/smith) | hamstrings, lower_back (free-bar), upper_back/abs (front squat) |
| squat (machine/goblet) | quads | glutes | adductors, abs (goblet) |
| lunge_split | quads | glutes | hamstrings, adductors |
| hinge (deadlift from floor) | glutes, lower_back | hamstrings, quads (conventional); + adductors (sumo) | upper_back, forearms |
| hinge (RDL / good morning) | hamstrings | glutes, lower_back | adductors, forearms |
| hip_extension_isolation | glutes | hamstrings (extension benches) | hamstrings, quads (hip thrust) |
| knee_flexion_isolation | hamstrings | glutes (GHR, slider) | calves, glutes (Nordic) |
| knee_extension_isolation | quads | — | glutes (Spanish squat) |
| calf | calves | — | — |
| core_* | abs or obliques | the other | hip_flexors (leg raises), lats (rollout) |
| carry | forearms (farmer's) / obliques (suitcase) / quads (sled) | traps_upper, abs, glute_med, calves | glute_med |
| power | movement-specific at 0.5 | — | calves, abs |
| cardio, mobility | none counted | — | — |

### 4.4 ExRx evidence transcribed (2026-09-04)

Target → Synergists → (Dynamic) Stabilizers as listed on ExRx.net (S1). Only what the
page says; our weights are derived from it by the rules above.

| ExRx page | Target | Synergists | Dynamic stabilizers / stabilizers |
|-----------|--------|------------|-----------------------------------|
| Barbell Bench Press | Pectoralis Major, Sternal | Pectoralis Major Clavicular; Deltoid Anterior; Triceps Brachii; Coracobrachialis | Biceps Brachii, Short Head |
| Barbell Incline Bench Press | Pectoralis Major, Clavicular | Pec Major Sternal; Deltoid Anterior; Triceps; Coracobrachialis | Biceps short head |
| Dumbbell Bench Press / Lever Chest Press | Pectoralis Major, Sternal | Pec clavicular; Deltoid Anterior; Triceps; Coracobrachialis | Biceps short head |
| Dumbbell Fly | Pectoralis Major, Sternal | Pec clavicular; Deltoid Anterior; Biceps short head; Coracobrachialis | Biceps, Brachialis, Triceps, Wrist Flexors (stabilizers) |
| Chest Dip | Pectoralis Major, Sternal | Deltoid Anterior; Triceps; Pec clavicular; Pec minor; Rhomboids; Levator Scapulae; Lats; Teres Major; Coracobrachialis | Trapezius Lower |
| Barbell Close Grip Bench Press | Triceps Brachii | Deltoid Anterior; Pec Major Sternal + Clavicular; Coracobrachialis | Biceps |
| Cable Pushdown | Triceps Brachii | none | Lats, Teres Major, Post Delt, Pec, Traps Lower, Abs, Obliques, Wrist Flexors (stabilizers) |
| Barbell Military Press | Deltoid, Anterior | Pec clavicular; Triceps; Deltoid Lateral; Coracobrachialis; Traps Middle + Lower; Serratus Anterior | Triceps long head; Biceps short head; Traps Upper; Levator Scapulae |
| Lever Shoulder Press | Deltoid, Anterior | Deltoid Lateral; Supraspinatus; Pec clavicular; Triceps; Coracobrachialis; Traps Middle/Lower; Serratus | Triceps long head; Biceps; Traps Upper; Levator |
| Dumbbell Lateral Raise | Deltoid, Lateral | Deltoid Anterior; Supraspinatus; Traps Middle + Lower; Serratus Anterior | Traps Upper; Levator; Wrist Extensors |
| Dumbbell Rear Lateral Raise | Deltoid, Posterior | Infraspinatus; Teres Minor; Deltoid Lateral; Traps Middle + Lower; Rhomboids | Triceps, Wrist Extensors, Erector Spinae, Hamstrings, Glute Max, Adductor Magnus, Quads |
| Pull-up / Cable Pulldown | Latissimus Dorsi | Brachialis; Brachioradialis; Biceps; Teres Major; Deltoid Posterior; Infraspinatus; Teres Minor; Coracobrachialis; Rhomboids; Levator; Traps Lower + Middle; Pec Minor | Triceps long head |
| Barbell Bent-over Row | Back, General | Traps Middle + Lower; Rhomboids; Lats; Teres Major; Deltoid Posterior; Infraspinatus; Teres Minor; Brachialis; Brachioradialis; Pec Major Sternal | Biceps; Triceps long head; Erector Spinae, Hamstrings, Glute Max, Adductor Magnus, Quads (stabilizers) |
| Cable Seated Row | Back, General | Erector Spinae; Traps Middle + Lower; Rhomboids; Lats; Teres Major; Deltoid Posterior; Infraspinatus; Teres Minor; Brachialis; Brachioradialis; Pec Sternal | Biceps; Triceps long head; Hamstrings, Glute Max, Adductor Magnus |
| Barbell Curl | Biceps Brachii | Brachialis; Brachioradialis | Deltoid Anterior, Traps, Levator, Wrist Flexors (stabilizers) |
| Barbell Squat | Quadriceps | Gluteus Maximus; Adductor Magnus; Soleus | Hamstrings, Gastrocnemius (dynamic); Erector Spinae; Rectus Abdominis, Obliques (antagonist stabilizers) |
| Barbell Front Squat | Quadriceps | Gluteus Maximus; Adductor Magnus; Soleus | Hamstrings, Gastrocnemius; Erector Spinae; Deltoids, Pec clavicular, Traps, Serratus (stabilizers) |
| Sled Hack Squat | Quadriceps | Gluteus Maximus; Adductor Magnus; Soleus | Hamstrings, Gastrocnemius |
| Dumbbell Lunge / Barbell Split Squat / Step-up | Quadriceps | Gluteus Maximus; Adductor Magnus; Soleus (+ Gastrocnemius, step-up) | Hamstrings, Gastrocnemius; Erector Spinae; Gluteus Medius/Minimus; QL; Obliques |
| Lever Leg Extension | Quadriceps | none | Traps, Levator, Brachioradialis, Brachialis, Biceps (handle grip) |
| Barbell Deadlift | Erector Spinae ("see comments") | Gluteus Maximus; Adductor Magnus; Quadriceps; Hamstrings (top half); Soleus | Hamstrings (bottom half), Gastrocnemius; Traps Middle/Upper, Levator, Rhomboids; Abs/Obliques |
| Barbell Straight-leg Deadlift | Hamstrings | Erector Spinae; Gluteus Maximus; Adductor Magnus | Traps, Rhomboids, Lats, Levator; Abs/Obliques |
| Barbell Hip Thrust | Gluteus Maximus | Quadriceps | Hamstrings (dynamic); Erector Spinae; Abs/Obliques |
| Weighted Hyperextension | Erector Spinae | Gluteus Maximus; Hamstrings; Adductor Magnus | see comments |
| Cable Glute Kickback | Gluteus Maximus | Adductor Magnus | Hamstrings (dynamic); Obliques, Erector Spinae, QL, Gluteus Medius/Minimus |
| Lever Seated / Lying Leg Curl | Hamstrings | Gastrocnemius; Gracilis; Sartorius; Popliteus | Tibialis Anterior (antagonist stabilizer) |
| Lever Seated Hip Abduction | Hip Abductors | Gluteus Medius; Gluteus Minimus; Gluteus Maximus; Piriformis; Obturator externus | none |
| Lever Seated Hip Adduction | Adductors, Hip | Pectineus; Gracilis | none |
| Barbell Standing Calf Raise | Gastrocnemius | Soleus | none |
| Lever Seated Calf Raise | Soleus | Gastrocnemius | none |
| Cable Kneeling Crunch | Rectus Abdominis | Obliques | Iliopsoas, TFL, Rectus Femoris, Sartorius; Lats, Teres Major, Post Delt, Triceps long head, Rhomboids, Traps Lower, Pecs, Serratus |
| Cable Twist | Obliques ("see comments") | TFL; Gluteus Medius; Gluteus Minimus; Hip Adductors; Psoas Major; QL; Iliocostalis | Rectus Abdominis, Erector Spinae, Pecs, Traps, Rhomboids, Deltoids, Triceps |

Pages that returned 404/403 on the URL guesses tried (face pull, preacher curl, lying
triceps extension, hammer curl, Bulgarian split squat, Romanian deadlift, T-bar row,
GHR, farmer's walk, plank, rear-delt machine, hyperextension body-weight): not verified;
mapped by rule.

### 4.5 RP volume landmarks (direct-count sets per week, intermediate lifters)

Parsed verbatim from the S4 pages on 2026-09-04. These are *direct-count* numbers: compare
them with the app's `direct` view only. RP itself states: "These are the landmarks for
serious, intermediate lifters... If you're a beginner, all your volume landmarks are
likely substantially lower." (side-delt guide).

| muscle (RP) | MV | MEV | MAV | MRV | MAV*P | MRV*P | sessions/week (RP) | app muscle ids |
|-------------|----|-----|-----|-----|-------|-------|--------------------|----------------|
| Chest | 2–4 | 4–6 | 6–16 | 16–24 | 16–24 | 24–32+ | 2–4 | chest |
| Back | 2–6 | 6–8 | 8–20 | 20–26 | 20–26 | 26–34+ | 2–4 | back aggregate (lats ∪ upper_back) — the back page carries its table as an image (`back1.png`); values read from the downloaded image, which contradicted an earlier WebFetch text summary (4–8 / 8–10 / 12–20 / 20–24) — the image is authoritative |
| Quads | 2–4 | 4–6 | 6–14 | 14–18 | 10–18 | 18–24+ | 2–5 | quads |
| Hamstrings | 0–2 | 2–4 | 2–8 | 8–14 | 8–14 | 14–20 | 2–3 | hamstrings |
| Glutes | 2–6 | 6–8 | 8–24 | 24–30 | 24–30 | 30–40+ | 2–5 | glutes |
| Biceps | 6–8 | 8–10 | 14–20 | 20–26 | 20–26 | 26–35 | 3–6 | biceps |
| Triceps | 0–4 | 4–6 | 6–16 | 16–20 | 16–20 | 20–26+ | 2–4 | triceps |
| Front delts | 0–2 | 0–2 | 4–8 | 8–12 | 12–16 | 16–20+ | 2–3 | front_delts |
| Side delts | 2–6 | 6–8 | 8–24 | 24–30 | 24–30 | 30–40+ | 3–6 | side_delts |
| Rear delts | 0–4 | 0–4 | 4–12 | 12–20 | 24–30 | 30–40+ | 3–6 | rear_delts |
| Calves | 2–4 | 4–6 | 6–16 | 16–24 | 16–24 | 24–32+ | 3–6 | calves |
| Abs | 0–4 | 0–4 | 4–12 | 12–20 | 16–24 | 24–32+ | 3–6 | abs (+ obliques) |
| Traps | 0–4 | 0–4 | 4–12 | 12–20 | 16–24 | 24–32+ | 2–4 | traps_upper |
| Forearms | 0–4 | 0–8 | 8–24 | 24–30 | 24–30 | 30–40+ | 3–6 | forearms |

No RP guide exists for lower_back, adductors, glute_med, rotator_cuff, hip_flexors: the
planner treats them as "covered by compounds" with an optional 2–6 direct sets/week
cap, and never alarms on them.

RP rep-range framing used across all guides (S4): heavy 5–10, moderate 10–20, light
20–30, roughly 50 % of weekly sets moderate and the rest split; exceptions quoted in
§5.2. SBS (S6) supports the underlying premise that per-set growth is similar from ~30 %
to ~85 % 1RM when sets are near failure.

---

## 5. Exercise library plan (156 exercises)

### 5.1 Fields per exercise

`id`, `name`, `aliases[]`, `pattern`, `muscles[{muscle, weight}]`,
`equipment_options[{all_of[], load_type?, note?}]`, `load_type`, `unilateral`,
`difficulty`, `rep_profile`, `rep_overrides?`, `increment{kind, default_kg, min_kg,
direction}`, `progression`, `substitutions[]` (ranked), `flags{compound, spinal_loading,
shoulder_sensitive, knee_sensitive, low_back_sensitive, requires_spotter_or_safeties}`,
`batch`, `tags[]`, `sources[]`, `content{...}` (authored later), `content_status`.

Difficulty semantics: `beginner` = coachable from text in the first session, low
technical failure cost; `intermediate` = needs a few sessions of practice or a base of
strength; `advanced` = technical or high-risk when failed (front squat, good morning,
Nordic, pistol, push press).

### 5.2 Rep / RIR profiles (by goal)

Goals come from the profile screen: `strength`, `hypertrophy`, `endurance`
(muscular endurance / conditioning bias), `general_fitness` (default; "look, feel and
move better"). Ranges are reps per set unless the profile says otherwise. RIR = reps in
reserve; `→` = drift across a 4–6-week mesocycle (RP chest guide: "letting your RIR
decline from a 3 or 4 RIR start until it gets down to 0 (for exercises that don't
threaten the bar falling on you) or 1 (for those that do) in the last week"). SBS (S5):
lifters under-predict RIR by about one rep on average, so an "RIR 1" instruction is in
practice ≈ RIR 2; the app may suggest taking the *last* set of a safe isolation exercise
to failure occasionally to recalibrate, and explicitly not on `SP`-flagged lifts without
safeties (S5: "not advising you to risk getting hurt by going for that extra repetition
on squats without a spotter").

| profile id | unit | strength | hypertrophy | endurance | general_fitness | notes |
|------------|------|----------|-------------|-----------|-----------------|-------|
| `barbell_compound` | reps | 3–6 @ RIR 2–3 (top set 1–2) | 5–10 @ RIR 3→1 | 10–15 @ RIR 2–3 | 6–10 @ RIR 2–3 | never RIR 0 if `SP` and no safeties/spotter; hinges (RDL, GM) stay 5–10 (RP hamstring guide: "Hip hinges should likely be done mostly in the 5-10 rep range") |
| `compound` | reps | 5–8 @ RIR 2 | 8–12 @ RIR 3→1 (machines →0) | 12–20 @ RIR 1–2 | 8–12 @ RIR 2 | DB/machine/cable compounds |
| `isolation` | reps | 6–10 @ RIR 1–2 | 10–15 @ RIR 2→0 | 15–25 @ RIR 1→0 | 10–15 @ RIR 1–2 | curls, extensions, leg curl/ext, flyes, calves (RP: leg curls best 10–20 / 20–30) |
| `isolation_light` | reps | 8–12 @ RIR 1–2 | 12–20 @ RIR 2→0 | 20–30 @ RIR 1→0 | 12–20 @ RIR 1–2 | side/rear delts, rotator cuff, band work (RP rear-delt guide: 20–30 "very productive"; "Very few people seem to respond very well to rear delt training in the 5-10 rep range") |
| `bodyweight_progression` | reps | 4–8 weighted @ RIR 2 | 6–12 (or AMRAP−2) @ RIR 2→1 | 12–25 @ RIR 1–2 | 6–15 @ RIR 2 | progress by leverage / added load; regress by band or assist |
| `core_reps` | reps | 6–10 weighted @ RIR 2 | 10–20 @ RIR 2→1 | 15–30 @ RIR 1 | 10–20 @ RIR 2 | crunch/leg-raise/Pallof/rollout |
| `core_time` | seconds | 20–40 s loaded | 20–60 s | 30–90 s | 20–60 s | stop before form breaks; progress by lever/load, not beyond ~60 s |
| `carry` | metres | 20–30 m heavy | 20–40 m | 40–60 m | 20–40 m | load such that last 5 m are hard (≈ RIR 2) |
| `power` | reps | 3–5 × 3–6 sets | 5–8 | 10–15 (swings) | 5–8 | stop the set when speed visibly drops (RIR ≥ 3 in effort terms) |
| `cardio` | minutes | 10–20 zone 2 (recovery) | 15–30 zone 2 | 20–45 zone 2 or 4×4 min intervals | 20–30 zone 2 | intensity by talk test / RPE 3–4 (zone 2) or RPE 8–9 (intervals); R-cardio task owns the protocols |
| `mobility` | reps or seconds | 6–12 slow reps or 30–60 s | same | same | same | no RIR; pain-free range only |

Per-exercise overrides (`rep_overrides`) are listed in the batch tables' notes where an
exercise deviates (e.g. Nordic curl 3–8, sissy squat 8–15, band walks 15–25 steps).

### 5.3 Difficulty × goal rules (for the planner, not per exercise)

- Beginners get `beginner` exercises only for the first two mesocycles; `intermediate`
  compounds appear after a technique check-in ("show me a video / rate your comfort").
- `advanced` exercises are opt-in via chat or profile ("I compete in powerlifting").

### 5.4 Load increments

| load_type | default increment | smallest sensible jump | gym parameter that overrides |
|-----------|-------------------|------------------------|------------------------------|
| barbell / smith | 2.5 kg (2 × 1.25) | 1.0 kg with 0.5 kg microplates, else 2.5 | `plates.smallest_plate_kg`, `plates.has_microplates` |
| barbell lower-body compounds (squat/deadlift), early | 5.0 kg | 2.5 kg | same |
| dumbbell (per hand) | 2.0 kg (one rack step) | 1.0 kg below 10 kg where 1-kg steps exist | `dumbbells.step_kg` |
| kettlebell | 4.0 kg (next bell) | 2.0 kg where available | `kettlebells.available_kg` |
| machine (selectorized) | 5.0 kg (one plate; 10 lb ≈ 4.5 kg on US stacks) | 2.5 kg add-on / half plate if present | `stack_step_kg`, `half_step_kg` |
| machine (plate-loaded) / leg press / hack | 5.0 kg (2 × 2.5) | 2.5 kg (2 × 1.25; single 2.5 on one side is acceptable on sleds) | `plates.smallest_plate_kg` |
| cable | 5.0 / 2.5 kg | 2.5 kg | `stack_step_kg`, `half_step_kg` |
| bodyweight | +1–2 reps, then +2.5 kg (dip belt / plate / vest) | 1.25 kg | `plates.smallest_plate_kg` |
| band | next band level (ordinal) | shorten the band | `resistance_bands.levels` |
| assisted machine | −5.0 kg assistance | −2.5 kg | `stack_step_kg` |
| sled | 10 kg | 5 kg | — |
| cardio | +2–5 min or +1 zone | — | — |

Progression rule of thumb the planner uses: **double progression** for everything with
a coarse jump (dumbbells, machines, cables): hit the top of the range on all sets at the
target RIR for two sessions → add one increment and drop to the bottom of the range.
Barbell compounds use linear/wave loading while jumps are <5 % of the load, then double
progression. Any automatic change is shown with its reason (REQUIREMENTS: sacred
content).

### 5.5 Flags (definitions)

- `compound`: multi-joint, counts toward the "big pattern" coverage check.
- `spinal_loading`: bar on the back/shoulders or heavy load through a flexed/braced
  spine (squats, deadlifts, OHP, good mornings, bent-over rows, T-bar). Users with a
  logged back limitation get substitutions offered.
- `shoulder_sensitive`: deep pressing at the stretched position, behind-the-neck
  positions, overhead with poor mobility, dips, straight-bar bench at wide grip, flyes
  at end range, upright-row family.
- `knee_sensitive`: high knee flexion under load, forward-knee-travel emphasis,
  impact (jumps, running), kneeling on hard pads.
- `low_back_sensitive`: unsupported hinges/rows, lumbar flexion under load (leg press
  bottom, leg raises with anterior tilt), loaded rotation.
- `requires_spotter_or_safeties`: bar can trap the lifter if the rep fails (bench,
  incline, close-grip, back/front/SSB squat, good morning). Satisfied by
  `rack_or_spotter`; unsatisfied ⇒ the exercise is still *available* but the planner
  caps RIR at 2 and prints the safety note.

### 5.6 Batch 1 — Chest & triceps (26)

| id | name | pattern | muscles | equipment options | load | uni | diff | profile | inc (kg) | flags | substitutions (ranked) |
|----|------|---------|---------|-------------------|------|-----|------|---------|----------|-------|------------------------|
| barbell_bench_press | Barbell Bench Press | horizontal_push | chest 1.0, front_delts 0.5, triceps 0.5 † | bb_loaded+bench_flat_any+rack_or_spotter | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 | C SH SP | dumbbell_bench_press, smith_bench_press, machine_chest_press, push_up |
| dumbbell_bench_press | Dumbbell Bench Press | horizontal_push | chest 1.0, front_delts 0.5, triceps 0.5 † | dumbbells+bench_flat_any | dumbbell | – | beginner | compound | 2.0 / 2.0 | C SH | barbell_bench_press, machine_chest_press, dumbbell_floor_press, push_up |
| smith_bench_press | Smith Machine Bench Press | horizontal_push | chest 1.0, front_delts 0.5, triceps 0.5 | smith_machine+bench_flat_any | smith | – | beginner | compound | 2.5 / 1.0 | C SH | barbell_bench_press, machine_chest_press, dumbbell_bench_press |
| machine_chest_press | Machine Chest Press | horizontal_push | chest 1.0, front_delts 0.5, triceps 0.5 † | chest_press_machine | machine | – | beginner | compound | 5.0 / 2.5 | C | dumbbell_bench_press, smith_bench_press, push_up |
| incline_barbell_bench_press | Incline Barbell Bench Press | incline_push | chest 1.0, front_delts 0.5, triceps 0.5 † | bb_loaded+bench_incline_any+rack_or_spotter | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 | C SH SP | incline_dumbbell_press, incline_machine_chest_press, smith_incline_press, low_to_high_cable_fly |
| incline_dumbbell_press | Incline Dumbbell Press | incline_push | chest 1.0, front_delts 0.5, triceps 0.5 | dumbbells+bench_incline_any | dumbbell | – | beginner | compound | 2.0 / 2.0 | C SH | incline_barbell_bench_press, incline_machine_chest_press, smith_incline_press, push_up_feet_elevated |
| incline_machine_chest_press | Incline Machine Chest Press | incline_push | chest 1.0, front_delts 0.5, triceps 0.5 | incline_chest_press_machine | machine | – | beginner | compound | 5.0 / 2.5 | C | incline_dumbbell_press, smith_incline_press, machine_chest_press |
| smith_incline_press | Smith Machine Incline Press | incline_push | chest 1.0, front_delts 0.5, triceps 0.5 | smith_machine+bench_incline_any | smith | – | beginner | compound | 2.5 / 1.0 | C SH | incline_dumbbell_press, incline_barbell_bench_press, incline_machine_chest_press |
| chest_dip | Chest Dip | horizontal_push | chest 1.0, triceps 0.5, front_delts 0.5 † | dip_any ; dip_any+dip_belt+plates | bodyweight | – | intermediate | bodyweight_progression | +2.5 / 1.25 | C SH | assisted_dip, machine_chest_press, dumbbell_bench_press, push_up |
| assisted_dip | Assisted Dip (machine) | horizontal_push | chest 1.0, triceps 0.5, front_delts 0.5 | assisted_pullup_dip_machine | machine | – | beginner | compound | −5.0 / −2.5 | C SH | chest_dip, bench_dip, push_up, machine_chest_press |
| push_up | Push-up | horizontal_push | chest 1.0, triceps 0.5, front_delts 0.5, abs 0.25 | — | bodyweight | – | beginner | bodyweight_progression | reps; +2.5 (plate/band) | C | machine_chest_press, dumbbell_bench_press, push_up_feet_elevated, diamond_push_up |
| push_up_feet_elevated | Feet-Elevated Push-up | incline_push | chest 1.0, front_delts 0.5, triceps 0.5, abs 0.25 | step_any | bodyweight | – | intermediate | bodyweight_progression | reps | C SH | incline_dumbbell_press, push_up, pike_push_up |
| dumbbell_floor_press | Dumbbell Floor Press | horizontal_push | chest 1.0, triceps 0.5, front_delts 0.25 | dumbbells | dumbbell | – | beginner | compound | 2.0 / 2.0 | C | dumbbell_bench_press, push_up, machine_chest_press, close_grip_bench_press |
| cable_fly | Cable Fly (crossover) | chest_fly | chest 1.0, front_delts 0.25 | cable_dual_any | cable | – | beginner | isolation | 2.5 / 2.5 (per side) | SH | pec_deck_fly, dumbbell_fly, low_to_high_cable_fly, machine_chest_press |
| low_to_high_cable_fly | Low-to-High Cable Fly | chest_fly | chest 1.0, front_delts 0.5 | cable_dual_any | cable | – | beginner | isolation | 2.5 / 2.5 | SH | incline_dumbbell_press, cable_fly, pec_deck_fly |
| pec_deck_fly | Pec Deck Fly | chest_fly | chest 1.0, front_delts 0.25 | pec_deck_machine | machine | – | beginner | isolation | 5.0 / 2.5 | SH | cable_fly, dumbbell_fly, machine_chest_press |
| dumbbell_fly | Dumbbell Fly | chest_fly | chest 1.0, front_delts 0.25 † | dumbbells+bench_flat_any | dumbbell | – | intermediate | isolation | 2.0 / 1.0 | SH | cable_fly, pec_deck_fly, dumbbell_bench_press |
| close_grip_bench_press | Close-Grip Bench Press | horizontal_push | triceps 1.0, chest 0.5, front_delts 0.5 † | bb_loaded+bench_flat_any+rack_or_spotter ; smith_machine+bench_flat_any (smith) | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 | C SH SP | dumbbell_floor_press, machine_triceps_extension, cable_triceps_pushdown, diamond_push_up |
| cable_triceps_pushdown | Cable Triceps Pushdown | elbow_extension | triceps 1.0 † | cable_any | cable | – | beginner | isolation | 2.5 / 2.5 | – | band_triceps_pushdown, machine_triceps_extension, overhead_cable_triceps_extension, dumbbell_overhead_triceps_extension |
| overhead_cable_triceps_extension | Overhead Cable Triceps Extension | elbow_extension | triceps 1.0 | cable_any+cable_rope | cable | – | beginner | isolation | 2.5 / 2.5 | SH | dumbbell_overhead_triceps_extension, lying_triceps_extension, cable_triceps_pushdown |
| lying_triceps_extension | Lying Triceps Extension (skull crusher) | elbow_extension | triceps 1.0 | ez_loaded+bench_flat_any ; bb_loaded+bench_flat_any ; dumbbells+bench_flat_any (dumbbell) | barbell | – | intermediate | isolation | 2.5 / 1.0 | – | overhead_cable_triceps_extension, dumbbell_overhead_triceps_extension, cable_triceps_pushdown, machine_triceps_extension |
| dumbbell_overhead_triceps_extension | Dumbbell Overhead Triceps Extension | elbow_extension | triceps 1.0 | dumbbells | dumbbell | – | beginner | isolation | 2.0 / 2.0 | SH | overhead_cable_triceps_extension, lying_triceps_extension, cable_triceps_pushdown |
| machine_triceps_extension | Machine Triceps Extension / Seated Dip | elbow_extension | triceps 1.0, chest 0.25 | triceps_machine | machine | – | beginner | isolation | 5.0 / 2.5 | – | cable_triceps_pushdown, lying_triceps_extension, bench_dip |
| bench_dip | Bench Dip | elbow_extension | triceps 1.0, chest 0.25, front_delts 0.25 | step_any | bodyweight | – | beginner | bodyweight_progression | reps; +plate | SH | diamond_push_up, machine_triceps_extension, cable_triceps_pushdown |
| diamond_push_up | Diamond Push-up | elbow_extension | triceps 1.0, chest 0.5, front_delts 0.25 | — | bodyweight | – | intermediate | bodyweight_progression | reps | C | push_up, bench_dip, cable_triceps_pushdown |
| band_triceps_pushdown | Band Triceps Pushdown | elbow_extension | triceps 1.0 | resistance_bands+band_anchor_any | band | – | beginner | isolation | band | – | cable_triceps_pushdown, dumbbell_overhead_triceps_extension, diamond_push_up |

### 5.7 Batch 2 — Shoulders, traps, rotator cuff, shoulder mobility (25)

| id | name | pattern | muscles | equipment options | load | uni | diff | profile | inc (kg) | flags | substitutions (ranked) |
|----|------|---------|---------|-------------------|------|-----|------|---------|----------|-------|------------------------|
| overhead_press_barbell | Barbell Overhead Press | vertical_push | front_delts 1.0, side_delts 0.5, triceps 0.5, upper_back 0.25 † | bb_loaded+rack_any ; bb_loaded (clean from floor; advanced) | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 | C SL SH | seated_dumbbell_shoulder_press, machine_shoulder_press, landmine_press, smith_shoulder_press |
| seated_dumbbell_shoulder_press | Seated Dumbbell Shoulder Press | vertical_push | front_delts 1.0, side_delts 0.5, triceps 0.5 † | dumbbells+bench_incline_any ; dumbbells (standing) | dumbbell | – | beginner | compound | 2.0 / 2.0 | C SH | machine_shoulder_press, overhead_press_barbell, arnold_press, landmine_press |
| machine_shoulder_press | Machine Shoulder Press | vertical_push | front_delts 1.0, side_delts 0.5, triceps 0.5 † | shoulder_press_machine | machine | – | beginner | compound | 5.0 / 2.5 | C SH | seated_dumbbell_shoulder_press, smith_shoulder_press, landmine_press |
| smith_shoulder_press | Smith Machine Shoulder Press | vertical_push | front_delts 1.0, side_delts 0.5, triceps 0.5 | smith_machine+bench_incline_any | smith | – | beginner | compound | 2.5 / 1.0 | C SH | machine_shoulder_press, seated_dumbbell_shoulder_press, overhead_press_barbell |
| arnold_press | Arnold Press | vertical_push | front_delts 1.0, side_delts 0.5, triceps 0.5 | dumbbells+bench_incline_any ; dumbbells | dumbbell | – | intermediate | compound | 2.0 / 2.0 | C SH | seated_dumbbell_shoulder_press, machine_shoulder_press |
| landmine_press | Landmine Press | vertical_push | front_delts 1.0, chest 0.5, triceps 0.5 | landmine_loaded | barbell | ✓ | beginner | compound | 2.5 / 1.25 | C | machine_shoulder_press, seated_dumbbell_shoulder_press, pike_push_up, incline_dumbbell_press |
| push_press | Push Press | power | front_delts 1.0, side_delts 0.5, triceps 0.5, quads 0.25, glutes 0.25 | bb_loaded+rack_any | barbell | – | advanced | power | 2.5 / 1.0 | C SL SH | overhead_press_barbell, seated_dumbbell_shoulder_press, landmine_press |
| pike_push_up | Pike Push-up | vertical_push | front_delts 1.0, side_delts 0.5, triceps 0.5 | — ; step_any (feet elevated) | bodyweight | – | intermediate | bodyweight_progression | reps; elevate feet | C SH | seated_dumbbell_shoulder_press, machine_shoulder_press, landmine_press |
| dumbbell_lateral_raise | Dumbbell Lateral Raise | shoulder_abduction | side_delts 1.0, traps_upper 0.25 † | dumbbells | dumbbell | – | beginner | isolation_light | 2.0 / 1.0 | SH | cable_lateral_raise, machine_lateral_raise, band_lateral_raise |
| cable_lateral_raise | Cable Lateral Raise | shoulder_abduction | side_delts 1.0 | cable_any | cable | ✓ | beginner | isolation_light | 2.5 / 2.5 | SH | dumbbell_lateral_raise, machine_lateral_raise, band_lateral_raise |
| machine_lateral_raise | Machine Lateral Raise | shoulder_abduction | side_delts 1.0 | lateral_raise_machine | machine | – | beginner | isolation_light | 5.0 / 2.5 | – | dumbbell_lateral_raise, cable_lateral_raise |
| band_lateral_raise | Band Lateral Raise | shoulder_abduction | side_delts 1.0 | resistance_bands | band | – | beginner | isolation_light | band | – | dumbbell_lateral_raise, cable_lateral_raise |
| face_pull | Face Pull | shoulder_er_rear_delt | rear_delts 1.0, upper_back 0.5, rotator_cuff 0.5 | cable_any+cable_rope ; resistance_bands+band_anchor_any (band) | cable | – | beginner | isolation_light | 2.5 / 2.5 | – | band_pull_apart, reverse_pec_deck, cable_rear_delt_fly, dumbbell_rear_delt_fly |
| reverse_pec_deck | Reverse Pec Deck | shoulder_er_rear_delt | rear_delts 1.0, upper_back 0.5 | pec_deck_machine | machine | – | beginner | isolation_light | 5.0 / 2.5 | – | face_pull, dumbbell_rear_delt_fly, cable_rear_delt_fly, band_pull_apart |
| dumbbell_rear_delt_fly | Dumbbell Rear Delt Fly | shoulder_er_rear_delt | rear_delts 1.0, upper_back 0.5 † | dumbbells+bench_incline_any (chest-supported) ; dumbbells (bent-over) | dumbbell | – | beginner | isolation_light | 2.0 / 1.0 | LB (bent-over option) | reverse_pec_deck, face_pull, cable_rear_delt_fly, band_pull_apart |
| cable_rear_delt_fly | Cable Rear Delt Fly | shoulder_er_rear_delt | rear_delts 1.0, upper_back 0.5 | cable_dual_any ; cable_any (single-arm) | cable | – | beginner | isolation_light | 2.5 / 2.5 | – | face_pull, reverse_pec_deck, dumbbell_rear_delt_fly |
| band_pull_apart | Band Pull-Apart | shoulder_er_rear_delt | rear_delts 1.0, upper_back 0.5, rotator_cuff 0.25 | resistance_bands | band | – | beginner | isolation_light | band | – | face_pull, reverse_pec_deck, dumbbell_rear_delt_fly |
| barbell_shrug | Barbell / Trap-Bar Shrug | scapular_elevation | traps_upper 1.0, forearms 0.25 | bb_loaded+rack_any ; bb_loaded ; trap_loaded | barbell | – | beginner | isolation | 5.0 / 2.5 | – | dumbbell_shrug, trap_bar_deadlift, farmers_carry |
| dumbbell_shrug | Dumbbell Shrug | scapular_elevation | traps_upper 1.0, forearms 0.25 | dumbbells | dumbbell | – | beginner | isolation | 2.0 / 2.0 | – | barbell_shrug, farmers_carry |
| cable_external_rotation | Cable External Rotation | shoulder_er_rear_delt | rotator_cuff 1.0, rear_delts 0.25 | cable_any | cable | ✓ | beginner | isolation_light | 2.5 / 2.5 (often too coarse → band) | – | band_external_rotation, side_lying_dumbbell_external_rotation, face_pull |
| side_lying_dumbbell_external_rotation | Side-Lying Dumbbell External Rotation | shoulder_er_rear_delt | rotator_cuff 1.0 | dumbbells | dumbbell | ✓ | beginner | isolation_light | 1.0 / 0.5 (needs 1–5 kg DBs) | – | band_external_rotation, cable_external_rotation |
| band_external_rotation | Band External Rotation | shoulder_er_rear_delt | rotator_cuff 1.0, rear_delts 0.25 | resistance_bands | band | ✓ | beginner | isolation_light | band | – | cable_external_rotation, side_lying_dumbbell_external_rotation, band_pull_apart |
| prone_y_raise | Prone Y-Raise (incline bench) | shoulder_er_rear_delt | upper_back 1.0, rear_delts 0.5 | bench_incline_any ; bench_incline_any+dumbbells (dumbbell) | bodyweight | – | beginner | isolation_light | 1.0 / 0.5 | – | face_pull, band_pull_apart, wall_slide |
| shoulder_pass_through | Band / Stick Shoulder Pass-Through | mobility | — | resistance_bands ; — (towel or stick) | band | – | beginner | mobility | n/a | – | wall_slide, thoracic_rotation |
| wall_slide | Wall Slide (scapular) | mobility | upper_back 0.25 | — | bodyweight | – | beginner | mobility | n/a | – | shoulder_pass_through, prone_y_raise |

### 5.8 Batch 3 — Back, biceps, forearms, thoracic mobility (25)

| id | name | pattern | muscles | equipment options | load | uni | diff | profile | inc (kg) | flags | substitutions (ranked) |
|----|------|---------|---------|-------------------|------|-----|------|---------|----------|-------|------------------------|
| pull_up | Pull-up | vertical_pull | lats 1.0, upper_back 0.5, biceps 0.5, rear_delts 0.25 † | pullup_bar_any ; pullup_bar_any+dip_belt+plates | bodyweight | – | intermediate | bodyweight_progression | +2.5 / 1.25 | C | chin_up, lat_pulldown, assisted_pull_up, band_assisted_pull_up |
| chin_up | Chin-up | vertical_pull | lats 1.0, biceps 0.5, upper_back 0.5 | pullup_bar_any ; pullup_bar_any+dip_belt+plates | bodyweight | – | intermediate | bodyweight_progression | +2.5 / 1.25 | C | pull_up, lat_pulldown, assisted_pull_up, band_assisted_pull_up |
| assisted_pull_up | Assisted Pull-up (machine) | vertical_pull | lats 1.0, upper_back 0.5, biceps 0.5, rear_delts 0.25 | assisted_pullup_dip_machine | machine | – | beginner | compound | −5.0 / −2.5 | C | lat_pulldown, band_assisted_pull_up, pull_up |
| band_assisted_pull_up | Band-Assisted Pull-up | vertical_pull | lats 1.0, upper_back 0.5, biceps 0.5, rear_delts 0.25 | pullup_bar_any+resistance_bands | band | – | beginner | bodyweight_progression | band (lighter band = harder) | C | assisted_pull_up, lat_pulldown, pull_up |
| lat_pulldown | Lat Pulldown | vertical_pull | lats 1.0, upper_back 0.5, biceps 0.5, rear_delts 0.25 † | lat_pulldown_machine ; cable_any (kneeling / half-kneeling) | machine | – | beginner | compound | 5.0 / 2.5 | C | pull_up, assisted_pull_up, band_lat_pulldown, straight_arm_pulldown |
| band_lat_pulldown | Band Lat Pulldown | vertical_pull | lats 1.0, upper_back 0.5, biceps 0.5 | resistance_bands+band_anchor_any | band | – | beginner | isolation (12–20) | band | C | lat_pulldown, band_assisted_pull_up, straight_arm_pulldown |
| straight_arm_pulldown | Straight-Arm Pulldown | vertical_pull | lats 1.0, rear_delts 0.25, triceps 0.25 | cable_any | cable | – | beginner | isolation | 2.5 / 2.5 | – | lat_pulldown, band_lat_pulldown, pull_up |
| barbell_row | Barbell Bent-Over Row | horizontal_pull | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5, lower_back 0.25 † | bb_loaded | barbell | – | intermediate | compound | 2.5 / 1.0 | C SL LB | chest_supported_dumbbell_row, seated_cable_row, machine_row, t_bar_row, dumbbell_row |
| dumbbell_row | Single-Arm Dumbbell Row | horizontal_pull | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5 | dumbbells+bench_flat_any ; dumbbells | dumbbell | ✓ | beginner | compound | 2.0 / 2.0 | C | chest_supported_dumbbell_row, seated_cable_row, machine_row, inverted_row |
| chest_supported_dumbbell_row | Chest-Supported Dumbbell Row | horizontal_pull | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5 | dumbbells+bench_incline_any | dumbbell | – | beginner | compound | 2.0 / 2.0 | C | machine_row, seated_cable_row, dumbbell_row |
| seated_cable_row | Seated Cable Row | horizontal_pull | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5, lower_back 0.25 † | seated_cable_row_machine ; cable_any | cable | – | beginner | compound | 5.0 / 2.5 | C LB | machine_row, chest_supported_dumbbell_row, dumbbell_row, inverted_row |
| machine_row | Chest-Supported Machine Row | horizontal_pull | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5 | chest_supported_row_machine | machine | – | beginner | compound | 5.0 / 2.5 | C | seated_cable_row, chest_supported_dumbbell_row, t_bar_row |
| t_bar_row | T-Bar Row | horizontal_pull | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5, lower_back 0.25 | t_bar_row_station ; landmine_loaded | barbell | – | intermediate | compound | 5.0 / 2.5 | C SL LB | machine_row, barbell_row, seated_cable_row, landmine_row |
| high_row_machine | High Row (machine) | horizontal_pull | upper_back 1.0, lats 0.5, rear_delts 0.5, biceps 0.5 | high_row_machine | machine | – | beginner | compound | 5.0 / 2.5 | C | machine_row, lat_pulldown, face_pull, seated_cable_row |
| inverted_row | Inverted Row | horizontal_pull | upper_back 1.0, lats 0.5, biceps 0.5, rear_delts 0.5, abs 0.25 | rack_any+barbell ; smith_machine ; suspension_trainer | bodyweight | – | beginner | bodyweight_progression | reps; lower bar / elevate feet | C | seated_cable_row, chest_supported_dumbbell_row, dumbbell_row |
| landmine_row | Landmine (Meadows) Row | horizontal_pull | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5, lower_back 0.25 | landmine_loaded | barbell | ✓ | intermediate | compound | 2.5 / 1.25 | C LB | dumbbell_row, t_bar_row, chest_supported_dumbbell_row |
| barbell_curl | Barbell / EZ-Bar Curl | elbow_flexion | biceps 1.0, forearms 0.25 † | bb_loaded ; ez_loaded ; fixed_weight_barbells | barbell | – | beginner | isolation | 2.5 / 1.0 | – | dumbbell_curl, cable_curl, preacher_curl, hammer_curl |
| dumbbell_curl | Dumbbell Curl | elbow_flexion | biceps 1.0, forearms 0.25 | dumbbells | dumbbell | – | beginner | isolation | 2.0 / 1.0 | – | barbell_curl, cable_curl, hammer_curl, incline_dumbbell_curl |
| incline_dumbbell_curl | Incline Dumbbell Curl | elbow_flexion | biceps 1.0 | dumbbells+bench_incline_any | dumbbell | – | beginner | isolation | 2.0 / 1.0 | – | dumbbell_curl, preacher_curl, cable_curl |
| hammer_curl | Hammer Curl | elbow_flexion | biceps 1.0, forearms 0.5 | dumbbells ; cable_any+cable_rope (cable) | dumbbell | – | beginner | isolation | 2.0 / 1.0 | – | dumbbell_curl, reverse_curl, cable_curl |
| preacher_curl | Preacher Curl | elbow_flexion | biceps 1.0 | preacher_bench+ez_loaded ; preacher_bench+dumbbells (dumbbell) ; preacher_curl_machine (machine) | barbell | – | beginner | isolation | 2.5 / 1.0 | – | incline_dumbbell_curl, cable_curl, barbell_curl |
| cable_curl | Cable Curl | elbow_flexion | biceps 1.0, forearms 0.25 | cable_any | cable | – | beginner | isolation | 2.5 / 2.5 | – | dumbbell_curl, barbell_curl, preacher_curl |
| reverse_curl | Reverse Curl | elbow_flexion | forearms 1.0, biceps 0.5 | ez_loaded ; bb_loaded ; dumbbells (dumbbell) ; cable_any (cable) | barbell | – | beginner | isolation | 2.5 / 1.0 | – | hammer_curl, dead_hang, farmers_carry |
| dead_hang | Dead Hang | grip | forearms 1.0, lats 0.25 | pullup_bar_any | bodyweight | – | beginner | core_time | time | – | farmers_carry, reverse_curl |
| thoracic_rotation | Thoracic Rotation (open book / quadruped) | mobility | — | — | bodyweight | ✓ | beginner | mobility | n/a | – | cat_cow, shoulder_pass_through |

### 5.9 Batch 4 — Quads, squat & lunge patterns, knee isolation, calves, hip mobility (26)

| id | name | pattern | muscles | equipment options | load | uni | diff | profile | inc (kg) | flags | substitutions (ranked) |
|----|------|---------|---------|-------------------|------|-----|------|---------|----------|-------|------------------------|
| back_squat | Barbell Back Squat | squat | quads 1.0, glutes 0.5, adductors 0.5, hamstrings 0.25, lower_back 0.25 † | bb_loaded+rack_any | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 (5.0 early) | C SL KN LB SP | hack_squat, leg_press_45, smith_machine_squat, safety_bar_squat, goblet_squat, belt_squat |
| front_squat | Barbell Front Squat | squat | quads 1.0, glutes 0.5, adductors 0.5, upper_back 0.25, abs 0.25 † | bb_loaded+rack_any | barbell | – | advanced | barbell_compound | 2.5 / 1.0 | C SL KN SP | hack_squat, safety_bar_squat, goblet_squat, back_squat |
| safety_bar_squat | Safety-Bar Squat | squat | quads 1.0, glutes 0.5, adductors 0.5, upper_back 0.25, lower_back 0.25 | ssb_loaded+rack_any | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 | C SL KN LB SP | back_squat, hack_squat, front_squat |
| goblet_squat | Goblet Squat | squat | quads 1.0, glutes 0.5, adductors 0.25, abs 0.25 | dumbbells ; kettlebells (kettlebell) | dumbbell | – | beginner | compound | 2.0 / 2.0 | C KN | leg_press_45, hack_squat, bodyweight_squat, split_squat |
| smith_machine_squat | Smith Machine Squat | squat | quads 1.0, glutes 0.5, adductors 0.5 | smith_machine | smith | – | beginner | compound | 2.5 / 1.0 | C SL KN | hack_squat, leg_press_45, back_squat |
| hack_squat | Hack Squat (machine) | squat | quads 1.0, glutes 0.5, adductors 0.25 † | hack_squat_machine | machine | – | beginner | compound | 5.0 / 2.5 | C KN | leg_press_45, pendulum_squat, smith_machine_squat, back_squat |
| pendulum_squat | Pendulum Squat | squat | quads 1.0, glutes 0.5, adductors 0.25 | pendulum_squat_machine | machine | – | beginner | compound | 5.0 / 2.5 | C KN | hack_squat, leg_press_45, belt_squat |
| belt_squat | Belt Squat | squat | quads 1.0, glutes 0.5, adductors 0.5 | belt_squat_machine | machine | – | beginner | compound | 5.0 / 2.5 | C KN | hack_squat, leg_press_45, goblet_squat |
| leg_press_45 | 45° Leg Press | squat | quads 1.0, glutes 0.5, adductors 0.25 | leg_press_45 | machine | – | beginner | compound | 5.0 / 2.5 | C KN LB | hack_squat, leg_press_horizontal, smith_machine_squat, goblet_squat |
| leg_press_horizontal | Seated / Horizontal Leg Press | squat | quads 1.0, glutes 0.5 | leg_press_horizontal | machine | – | beginner | compound | 5.0 / 2.5 | C KN | leg_press_45, hack_squat, goblet_squat |
| bulgarian_split_squat | Bulgarian Split Squat | lunge_split | quads 1.0, glutes 0.5, adductors 0.25, hamstrings 0.25 | step_any+dumbbells ; step_any (bodyweight) ; step_any+smith_machine (smith) | dumbbell | ✓ | intermediate | compound | 2.0 / 2.0 | C KN | split_squat, reverse_lunge, walking_lunge, step_up, leg_press_45 |
| split_squat | Split Squat | lunge_split | quads 1.0, glutes 0.5, adductors 0.25, hamstrings 0.25 † | dumbbells ; — (bodyweight) | dumbbell | ✓ | beginner | compound | 2.0 / 2.0 | C KN | bulgarian_split_squat, reverse_lunge, step_up |
| walking_lunge | Walking Lunge | lunge_split | quads 1.0, glutes 0.5, hamstrings 0.25, adductors 0.25 † | dumbbells ; — (bodyweight) ; bb_loaded (barbell) | dumbbell | ✓ | intermediate | compound | 2.0 / 2.0 | C KN | reverse_lunge, bulgarian_split_squat, step_up |
| reverse_lunge | Reverse Lunge | lunge_split | quads 1.0, glutes 0.5, hamstrings 0.25, adductors 0.25 | dumbbells ; — (bodyweight) ; kettlebells (kettlebell) ; bb_loaded+rack_any (barbell) | dumbbell | ✓ | beginner | compound | 2.0 / 2.0 | C KN | split_squat, bulgarian_split_squat, step_up, walking_lunge |
| step_up | Step-up | lunge_split | quads 1.0, glutes 0.5, hamstrings 0.25 † | step_any+dumbbells ; step_any (bodyweight) | dumbbell | ✓ | beginner | compound | 2.0 / 2.0 | C KN | reverse_lunge, bulgarian_split_squat, split_squat |
| leg_extension | Leg Extension | knee_extension_isolation | quads 1.0 † | leg_extension_machine | machine | – | beginner | isolation | 5.0 / 2.5 | KN | spanish_squat, sissy_squat, hack_squat, step_up |
| sissy_squat | Sissy Squat | knee_extension_isolation | quads 1.0 | — (hold a support) | bodyweight | – | advanced | isolation (8–15) | reps | KN | leg_extension, spanish_squat, hack_squat |
| spanish_squat | Spanish Squat (band) | knee_extension_isolation | quads 1.0, glutes 0.25 | resistance_bands+band_anchor_any | band | – | beginner | isolation | band / reps | – | leg_extension, goblet_squat, split_squat |
| bodyweight_squat | Bodyweight Squat | squat | quads 1.0, glutes 0.5 | — | bodyweight | – | beginner | bodyweight_progression | reps | C | goblet_squat, split_squat, step_up |
| pistol_squat | Pistol Squat | squat | quads 1.0, glutes 0.5, abs 0.25 | — ; dumbbells (counterweight) | bodyweight | ✓ | advanced | bodyweight_progression | reps; +DB | C KN | bulgarian_split_squat, step_up, split_squat |
| standing_calf_raise | Standing Calf Raise | calf | calves 1.0 † | standing_calf_raise_machine ; smith_machine+step_any (smith) ; dumbbells+step_any (dumbbell, single-leg) | machine | – | beginner | isolation | 5.0 / 2.5 | – | leg_press_calf_raise, single_leg_calf_raise, seated_calf_raise |
| seated_calf_raise | Seated Calf Raise | calf | calves 1.0 † | seated_calf_raise_machine | machine | – | beginner | isolation | 5.0 / 2.5 | – | standing_calf_raise, leg_press_calf_raise, single_leg_calf_raise |
| leg_press_calf_raise | Leg Press Calf Raise | calf | calves 1.0 | leg_press_any | machine | – | beginner | isolation | 5.0 / 2.5 | – | standing_calf_raise, seated_calf_raise, single_leg_calf_raise |
| single_leg_calf_raise | Single-Leg Calf Raise | calf | calves 1.0 | — ; dumbbells (dumbbell) ; step_any (deficit) | bodyweight | ✓ | beginner | bodyweight_progression (15–25) | reps; +2.0 DB | – | standing_calf_raise, leg_press_calf_raise |
| hip_90_90 | 90/90 Hip Switch | mobility | — | — | bodyweight | – | beginner | mobility | n/a | – | couch_stretch, worlds_greatest_stretch |
| couch_stretch | Couch Stretch (hip flexor / quad) | mobility | — | — (wall or bench) | bodyweight | ✓ | beginner | mobility | n/a | KN (kneeling; use a pad) | hip_90_90, worlds_greatest_stretch |

### 5.10 Batch 5 — Hinge, glutes, hamstrings, adductors/abductors, hip & ankle mobility (26)

| id | name | pattern | muscles | equipment options | load | uni | diff | profile | inc (kg) | flags | substitutions (ranked) |
|----|------|---------|---------|-------------------|------|-----|------|---------|----------|-------|------------------------|
| conventional_deadlift | Conventional Deadlift | hinge | glutes 1.0, lower_back 1.0, hamstrings 0.5, quads 0.5, upper_back 0.25, forearms 0.25 † | bb_loaded | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 (5.0 early) | C SL LB | trap_bar_deadlift, romanian_deadlift, sumo_deadlift, hip_thrust, back_extension_45 |
| sumo_deadlift | Sumo Deadlift | hinge | glutes 1.0, quads 0.5, adductors 0.5, hamstrings 0.5, lower_back 0.5, upper_back 0.25 | bb_loaded | barbell | – | advanced | barbell_compound | 2.5 / 1.0 | C SL LB KN | conventional_deadlift, trap_bar_deadlift, hip_thrust |
| trap_bar_deadlift | Trap-Bar Deadlift | hinge | glutes 1.0, quads 0.5, hamstrings 0.5, lower_back 0.5, upper_back 0.25, forearms 0.25 | trap_loaded | barbell | – | beginner | barbell_compound | 2.5 / 1.0 (5.0 early) | C SL LB | conventional_deadlift, romanian_deadlift, hip_thrust, leg_press_45 |
| romanian_deadlift | Romanian Deadlift | hinge | hamstrings 1.0, glutes 0.5, lower_back 0.5, adductors 0.25, forearms 0.25 † | bb_loaded ; bb_loaded+rack_any (start from pins) | barbell | – | intermediate | barbell_compound | 2.5 / 1.0 | C SL LB | dumbbell_romanian_deadlift, back_extension_45, seated_leg_curl, good_morning, cable_pull_through |
| dumbbell_romanian_deadlift | Dumbbell Romanian Deadlift | hinge | hamstrings 1.0, glutes 0.5, lower_back 0.5, adductors 0.25, forearms 0.25 | dumbbells ; kettlebells (kettlebell) | dumbbell | – | beginner | compound | 2.0 / 2.0 | C LB | romanian_deadlift, single_leg_romanian_deadlift, back_extension_45, slider_leg_curl |
| single_leg_romanian_deadlift | Single-Leg Romanian Deadlift | hinge | hamstrings 1.0, glutes 0.5, glute_med 0.25 | dumbbells ; kettlebells (kettlebell) ; — (bodyweight) | dumbbell | ✓ | intermediate | compound | 2.0 / 2.0 | C LB | dumbbell_romanian_deadlift, back_extension_45, slider_leg_curl |
| good_morning | Good Morning | hinge | hamstrings 1.0, glutes 0.5, lower_back 0.5 | bb_loaded+rack_any (safeties high) | barbell | – | advanced | barbell_compound | 2.5 / 1.0 | C SL LB SP | romanian_deadlift, back_extension_45, cable_pull_through |
| hip_thrust | Barbell Hip Thrust | hip_extension_isolation | glutes 1.0, hamstrings 0.25, quads 0.25 † | bb_loaded+bench_flat_any ; smith_machine+bench_flat_any (smith) | barbell | – | intermediate | compound | 5.0 / 2.5 | C | machine_hip_thrust, glute_bridge, cable_pull_through, back_extension_45, kettlebell_swing |
| machine_hip_thrust | Machine Hip Thrust | hip_extension_isolation | glutes 1.0, hamstrings 0.25 | hip_thrust_machine | machine | – | beginner | compound | 5.0 / 2.5 | C | hip_thrust, glute_bridge, cable_glute_kickback |
| glute_bridge | Glute Bridge | hip_extension_isolation | glutes 1.0, hamstrings 0.25 | — ; dumbbells (dumbbell) | bodyweight | – | beginner | bodyweight_progression | reps; +DB; single-leg | – | hip_thrust, machine_hip_thrust, cable_pull_through |
| back_extension_45 | 45° Back Extension (hip-hinge style) | hip_extension_isolation | glutes 1.0, hamstrings 0.5, lower_back 0.5 † | hyper_any ; hyper_any+plates | bodyweight | – | beginner | isolation | +2.5 / 1.25 | LB | romanian_deadlift, reverse_hyperextension, cable_pull_through, glute_bridge |
| reverse_hyperextension | Reverse Hyperextension | hip_extension_isolation | glutes 1.0, hamstrings 0.5, lower_back 0.5 | reverse_hyper_machine | machine | – | beginner | isolation | 5.0 / 2.5 | – | back_extension_45, cable_glute_kickback, hip_thrust |
| glute_ham_raise | Glute-Ham Raise | knee_flexion_isolation | hamstrings 1.0, glutes 0.5, calves 0.25 | ghd | bodyweight | – | advanced | bodyweight_progression | reps; +plate 2.5 | – | nordic_hamstring_curl, lying_leg_curl, seated_leg_curl, slider_leg_curl |
| nordic_hamstring_curl | Nordic Hamstring Curl | knee_flexion_isolation | hamstrings 1.0, glutes 0.25 | ghd ; lat_pulldown_machine (knee pads) ; bb_loaded (bar as ankle anchor) | bodyweight | – | advanced | bodyweight_progression (3–8, eccentric) | reps / ROM | KN | lying_leg_curl, seated_leg_curl, slider_leg_curl, glute_ham_raise |
| slider_leg_curl | Slider / Towel Leg Curl | knee_flexion_isolation | hamstrings 1.0, glutes 0.5 | — (towel on smooth floor) ; suspension_trainer | bodyweight | – | beginner | bodyweight_progression | reps; single-leg | – | lying_leg_curl, seated_leg_curl, nordic_hamstring_curl, dumbbell_romanian_deadlift |
| seated_leg_curl | Seated Leg Curl | knee_flexion_isolation | hamstrings 1.0, calves 0.25 † | seated_leg_curl_machine | machine | – | beginner | isolation | 5.0 / 2.5 | – | lying_leg_curl, nordic_hamstring_curl, slider_leg_curl, romanian_deadlift |
| lying_leg_curl | Lying Leg Curl | knee_flexion_isolation | hamstrings 1.0, calves 0.25 † | lying_leg_curl_machine | machine | – | beginner | isolation | 5.0 / 2.5 | – | seated_leg_curl, nordic_hamstring_curl, slider_leg_curl |
| kettlebell_swing | Kettlebell Swing | hinge | glutes 1.0, hamstrings 0.5, lower_back 0.25, abs 0.25 | kettlebells ; dumbbells (dumbbell) | kettlebell | – | intermediate | power | 4.0 / 2.0 | C LB | cable_pull_through, hip_thrust, romanian_deadlift, box_jump |
| cable_pull_through | Cable Pull-Through | hinge | glutes 1.0, hamstrings 0.5 | cable_any+cable_rope | cable | – | beginner | isolation | 5.0 / 2.5 | – | hip_thrust, romanian_deadlift, kettlebell_swing, glute_bridge |
| cable_glute_kickback | Cable / Machine Glute Kickback | hip_extension_isolation | glutes 1.0, hamstrings 0.25 † | cable_any+ankle_strap ; glute_kickback_machine (machine) | cable | ✓ | beginner | isolation | 2.5 / 2.5 | – | hip_thrust, glute_bridge, machine_hip_thrust, reverse_hyperextension |
| hip_abduction_machine | Hip Abduction (machine) | hip_abduction | glute_med 1.0, glutes 0.25 † | hip_abduction_machine ; cable_any+ankle_strap (cable) | machine | – | beginner | isolation | 5.0 / 2.5 | – | band_lateral_walk, cable_glute_kickback |
| hip_adduction_machine | Hip Adduction (machine) | hip_adduction | adductors 1.0 † | hip_adduction_machine ; cable_any+ankle_strap (cable) | machine | – | beginner | isolation | 5.0 / 2.5 | – | copenhagen_plank, sumo_deadlift, goblet_squat |
| copenhagen_plank | Copenhagen Plank | hip_adduction | adductors 1.0, obliques 0.5 | step_any | bodyweight | ✓ | intermediate | core_time | time / lever | – | hip_adduction_machine, side_plank |
| band_lateral_walk | Band Lateral Walk | hip_abduction | glute_med 1.0, glutes 0.25 | resistance_bands | band | – | beginner | isolation (15–25 steps) | band | – | hip_abduction_machine, cable_glute_kickback |
| worlds_greatest_stretch | World's Greatest Stretch | mobility | — | — | bodyweight | ✓ | beginner | mobility | n/a | – | hip_90_90, couch_stretch, cat_cow |
| ankle_dorsiflexion_rock | Ankle Dorsiflexion Rock (knee-to-wall) | mobility | — | — | bodyweight | ✓ | beginner | mobility | n/a | – | couch_stretch, single_leg_calf_raise |

### 5.11 Batch 6 — Core, carries, conditioning, cardio, general warm-up (28)

| id | name | pattern | muscles | equipment options | load | uni | diff | profile | inc (kg) | flags | substitutions (ranked) |
|----|------|---------|---------|-------------------|------|-----|------|---------|----------|-------|------------------------|
| plank | Front Plank | core_anti_extension | abs 1.0, obliques 0.5 | — | bodyweight | – | beginner | core_time | time; +plate | – | dead_bug, ab_wheel_rollout, bird_dog |
| side_plank | Side Plank | core_anti_rotation | obliques 1.0, glute_med 0.25 | — | bodyweight | ✓ | beginner | core_time | time | – | pallof_press, suitcase_carry, copenhagen_plank |
| dead_bug | Dead Bug | core_anti_extension | abs 1.0, hip_flexors 0.25 | — | bodyweight | – | beginner | core_reps | reps; slower tempo | – | plank, bird_dog, lying_leg_raise |
| bird_dog | Bird Dog | core_anti_rotation | abs 0.5, lower_back 0.5, glutes 0.25 | — | bodyweight | ✓ | beginner | core_reps | reps | – | dead_bug, plank, pallof_press |
| ab_wheel_rollout | Ab Wheel Rollout | core_anti_extension | abs 1.0, lats 0.25, obliques 0.25 | ab_wheel ; bb_loaded (bar with round plates) | bodyweight | – | intermediate | core_reps (6–15) | ROM; kneeling → standing | LB | plank, cable_crunch, dead_bug |
| cable_crunch | Cable Crunch | core_flexion | abs 1.0, obliques 0.25 † | cable_any+cable_rope | cable | – | beginner | core_reps | 5.0 / 2.5 | – | machine_crunch, ab_wheel_rollout, hanging_leg_raise, lying_leg_raise |
| machine_crunch | Ab Crunch Machine | core_flexion | abs 1.0 | ab_crunch_machine | machine | – | beginner | core_reps | 5.0 / 2.5 | – | cable_crunch, lying_leg_raise |
| hanging_leg_raise | Hanging Leg / Knee Raise | core_flexion | abs 1.0, hip_flexors 0.5, forearms 0.25 | pullup_bar_any | bodyweight | – | intermediate | core_reps | reps; knee → straight leg | – | lying_leg_raise, cable_crunch, dead_bug |
| lying_leg_raise | Lying Leg Raise / Reverse Crunch | core_flexion | abs 1.0, hip_flexors 0.5 | — | bodyweight | – | beginner | core_reps | reps | LB | hanging_leg_raise, dead_bug, cable_crunch |
| pallof_press | Pallof Press | core_anti_rotation | obliques 1.0, abs 0.5 | cable_any ; resistance_bands+band_anchor_any (band) | cable | ✓ | beginner | core_reps (8–15) | 2.5 / 2.5 | – | side_plank, suitcase_carry, cable_woodchop, bird_dog |
| cable_woodchop | Cable Woodchop (half-kneeling / standing) | core_rotation | obliques 1.0, abs 0.5 | cable_any ; resistance_bands+band_anchor_any (band) | cable | ✓ | intermediate | core_reps | 2.5 / 2.5 | LB | pallof_press, medicine_ball_slam, side_plank |
| farmers_carry | Farmer's Carry | carry | forearms 1.0, traps_upper 0.5, abs 0.5, glute_med 0.25 | dumbbells ; kettlebells (kettlebell) ; trap_loaded (barbell) | dumbbell | – | beginner | carry | 2.0 / 2.0 | – | suitcase_carry, dead_hang, dumbbell_shrug |
| suitcase_carry | Suitcase Carry | carry | obliques 1.0, forearms 0.5, glute_med 0.5 | dumbbells ; kettlebells (kettlebell) | dumbbell | ✓ | beginner | carry | 2.0 / 2.0 | – | farmers_carry, side_plank, pallof_press |
| battle_ropes | Battle Ropes | cardio | front_delts 0.25, upper_back 0.25, abs 0.25 | battle_ropes | cardio | – | beginner | cardio (intervals) | time | – | air_bike, ski_erg, medicine_ball_slam |
| medicine_ball_slam | Medicine Ball Slam | power | abs 0.5, lats 0.25 | medicine_balls | medicine_ball | – | beginner | power | next ball | – | battle_ropes, kettlebell_swing, cable_woodchop |
| box_jump | Box Jump | power | quads 0.5, glutes 0.5, calves 0.25 | plyo_box | bodyweight | – | intermediate | power | box height | KN | kettlebell_swing, medicine_ball_slam, sled_push |
| sled_push | Sled Push / Drag | carry | quads 1.0, glutes 0.5, calves 0.5 | sled_loaded | sled | – | beginner | carry | 10 / 5 | – | walking_lunge, air_bike, stair_climber, leg_press_45 |
| treadmill | Treadmill (walk / incline walk / run) | cardio | — | treadmill | cardio | – | beginner | cardio | n/a | KN (running) | outdoor_run_walk, elliptical, stationary_bike |
| stationary_bike | Stationary / Spin Bike | cardio | — | stationary_bike | cardio | – | beginner | cardio | n/a | – | air_bike, elliptical, treadmill |
| air_bike | Air Bike | cardio | — | air_bike | cardio | – | beginner | cardio (HIIT-friendly) | n/a | – | stationary_bike, rowing_erg, ski_erg |
| rowing_erg | Rowing Ergometer | cardio | — | rower | cardio | – | beginner | cardio | n/a | LB (technique) | ski_erg, air_bike, elliptical |
| elliptical | Elliptical / Cross-Trainer | cardio | — | elliptical | cardio | – | beginner | cardio | n/a | – | stationary_bike, treadmill, stair_climber |
| stair_climber | Stair Climber | cardio | — | stair_climber | cardio | – | beginner | cardio | n/a | KN (mild) | treadmill, elliptical, sled_push |
| ski_erg | Ski Erg | cardio | — | ski_erg | cardio | – | beginner | cardio | n/a | – | rowing_erg, air_bike, battle_ropes |
| jump_rope | Jump Rope | cardio | calves 0.25 | jump_rope | cardio | – | beginner | cardio (intervals) | n/a | KN (impact) | treadmill, air_bike, box_jump |
| outdoor_run_walk | Outdoor Run / Walk | cardio | — | — | cardio | – | beginner | cardio | n/a | KN (running) | treadmill, stationary_bike, elliptical |
| cat_cow | Cat-Cow | mobility | — | — | bodyweight | – | beginner | mobility | n/a | – | thoracic_rotation, worlds_greatest_stretch |
| foam_rolling | Foam Rolling | mobility | — | foam_roller | none | – | beginner | mobility | n/a | – | cat_cow, couch_stretch |

### 5.12 Batch summary (IDs per authoring batch)

Every batch owner writes, per exercise: `description` (2–4 sentences), `setup[]`,
`cues[]` (3–6), `mistakes[]` (3–5), `safety[]` (1–4, must reflect the flags), plus
`sources[]` (author/year or position stand) and sets `content_status: "draft"`. Muscle
weights, equipment options and flags in the tables above are **not** to be changed by
batch authors; disagreements go into a `review_notes` field for the integration pass.

- **Batch 1 — Chest & triceps (26):** barbell_bench_press, dumbbell_bench_press,
  smith_bench_press, machine_chest_press, incline_barbell_bench_press,
  incline_dumbbell_press, incline_machine_chest_press, smith_incline_press, chest_dip,
  assisted_dip, push_up, push_up_feet_elevated, dumbbell_floor_press, cable_fly,
  low_to_high_cable_fly, pec_deck_fly, dumbbell_fly, close_grip_bench_press,
  cable_triceps_pushdown, overhead_cable_triceps_extension, lying_triceps_extension,
  dumbbell_overhead_triceps_extension, machine_triceps_extension, bench_dip,
  diamond_push_up, band_triceps_pushdown.
- **Batch 2 — Shoulders, traps, rotator cuff, shoulder mobility (25):**
  overhead_press_barbell, seated_dumbbell_shoulder_press, machine_shoulder_press,
  smith_shoulder_press, arnold_press, landmine_press, push_press, pike_push_up,
  dumbbell_lateral_raise, cable_lateral_raise, machine_lateral_raise,
  band_lateral_raise, face_pull, reverse_pec_deck, dumbbell_rear_delt_fly,
  cable_rear_delt_fly, band_pull_apart, barbell_shrug, dumbbell_shrug,
  cable_external_rotation, side_lying_dumbbell_external_rotation,
  band_external_rotation, prone_y_raise, shoulder_pass_through, wall_slide.
- **Batch 3 — Back, biceps, forearms, thoracic mobility (25):** pull_up, chin_up,
  assisted_pull_up, band_assisted_pull_up, lat_pulldown, band_lat_pulldown,
  straight_arm_pulldown, barbell_row, dumbbell_row, chest_supported_dumbbell_row,
  seated_cable_row, machine_row, t_bar_row, high_row_machine, inverted_row,
  landmine_row, barbell_curl, dumbbell_curl, incline_dumbbell_curl, hammer_curl,
  preacher_curl, cable_curl, reverse_curl, dead_hang, thoracic_rotation.
- **Batch 4 — Quads, squat/lunge, knee isolation, calves, hip mobility (26):**
  back_squat, front_squat, safety_bar_squat, goblet_squat, smith_machine_squat,
  hack_squat, pendulum_squat, belt_squat, leg_press_45, leg_press_horizontal,
  bulgarian_split_squat, split_squat, walking_lunge, reverse_lunge, step_up,
  leg_extension, sissy_squat, spanish_squat, bodyweight_squat, pistol_squat,
  standing_calf_raise, seated_calf_raise, leg_press_calf_raise,
  single_leg_calf_raise, hip_90_90, couch_stretch.
- **Batch 5 — Hinge, glutes, hamstrings, adductors/abductors, hip & ankle mobility
  (26):** conventional_deadlift, sumo_deadlift, trap_bar_deadlift, romanian_deadlift,
  dumbbell_romanian_deadlift, single_leg_romanian_deadlift, good_morning, hip_thrust,
  machine_hip_thrust, glute_bridge, back_extension_45, reverse_hyperextension,
  glute_ham_raise, nordic_hamstring_curl, slider_leg_curl, seated_leg_curl,
  lying_leg_curl, kettlebell_swing, cable_pull_through, cable_glute_kickback,
  hip_abduction_machine, hip_adduction_machine, copenhagen_plank, band_lateral_walk,
  worlds_greatest_stretch, ankle_dorsiflexion_rock.
- **Batch 6 — Core, carries, conditioning, cardio, general warm-up (28):** plank,
  side_plank, dead_bug, bird_dog, ab_wheel_rollout, cable_crunch, machine_crunch,
  hanging_leg_raise, lying_leg_raise, pallof_press, cable_woodchop, farmers_carry,
  suitcase_carry, battle_ropes, medicine_ball_slam, box_jump, sled_push, treadmill,
  stationary_bike, air_bike, rowing_erg, elliptical, stair_climber, ski_erg,
  jump_rope, outdoor_run_walk, cat_cow, foam_rolling.

Total 156. Tags to set at authoring time: `warmup` (glute_bridge, band_lateral_walk,
bodyweight_squat, band_pull_apart, all `mobility`), `travel` (every exercise with a
`—`, band or dumbbell-only option), `hotel_core` (the subset available in the
`hotel_gym` preset — computed, not hand-tagged).

### 5.13 Consistency rules for the six authors

1. Cite at least one source per exercise family (ExRx page, NSCA Essentials chapter,
   an RP guide, or a peer-reviewed technique paper). Author/year or position-stand name
   is enough (REQUIREMENTS: "cite sources in the knowledge base").
2. Safety notes must mention the concrete mechanism behind each flag (e.g. `SP`: "set
   the safeties at chest height; without safeties or a spotter stop at RIR 2").
3. Never change numbers in the tables above (weights, increments, rep ranges); log
   objections in `review_notes`.
4. Cues describe *what to do*, mistakes describe *what it looks like when wrong*; keep
   both device-agnostic (no brand names).
5. Substitution rationale: one line per listed substitution ("same pattern, no spinal
   loading") so the coach chat can explain a swap.

---

## 6. JSON schemas (draft-07)

Both files are shipped in `:app/src/main/assets/` and parsed in `:core` with
kotlinx-serialization into plain data classes. Validation is done by unit tests in
`:core` (see 6.3), not at runtime — the app trusts its own assets after CI.

### 6.1 `equipment.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://gymcoach.local/schemas/equipment.schema.json",
  "title": "GymCoach equipment catalogue",
  "type": "object",
  "required": ["schema_version", "equipment", "groups", "presets"],
  "additionalProperties": false,
  "properties": {
    "schema_version": { "type": "integer", "minimum": 1 },
    "equipment": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/definitions/equipment" }
    },
    "groups": {
      "type": "array",
      "items": { "$ref": "#/definitions/group" }
    },
    "presets": {
      "type": "array",
      "items": { "$ref": "#/definitions/preset" }
    }
  },
  "definitions": {
    "id": { "type": "string", "pattern": "^[a-z][a-z0-9_]{1,63}$" },
    "category": {
      "type": "string",
      "enum": ["free_weights", "racks_benches", "cables", "machines_upper",
               "machines_lower", "bodyweight_stations", "cardio", "accessories"]
    },
    "presetId": {
      "type": "string",
      "enum": ["base_gym", "home_gym", "hotel_gym", "travel_kit"]
    },
    "paramSpec": {
      "type": "object",
      "required": ["key", "type"],
      "additionalProperties": false,
      "properties": {
        "key": { "type": "string", "pattern": "^[a-z][a-z0-9_]{1,63}$" },
        "type": { "type": "string", "enum": ["number", "integer", "boolean", "number_list"] },
        "unit": { "type": "string", "enum": ["kg", "cm", "pct", "count", "none"] },
        "default": { "type": ["number", "boolean", "array"],
                     "items": { "type": "number" } },
        "min": { "type": "number" },
        "max": { "type": "number" },
        "description": { "type": "string" }
      }
    },
    "equipment": {
      "type": "object",
      "required": ["id", "name", "category"],
      "additionalProperties": false,
      "properties": {
        "id": { "$ref": "#/definitions/id" },
        "name": { "type": "string", "minLength": 1 },
        "category": { "$ref": "#/definitions/category" },
        "aliases": { "type": "array", "items": { "type": "string" }, "default": [] },
        "description": { "type": "string" },
        "params": { "type": "array", "items": { "$ref": "#/definitions/paramSpec" }, "default": [] },
        "session_flag": { "type": "boolean", "default": false },
        "packable": { "type": "boolean", "default": false },
        "default_in": {
          "type": "array",
          "items": { "$ref": "#/definitions/presetId" },
          "uniqueItems": true,
          "default": []
        },
        "notes": { "type": "string" }
      }
    },
    "group": {
      "type": "object",
      "required": ["id", "kind", "members"],
      "additionalProperties": false,
      "properties": {
        "id": { "$ref": "#/definitions/id" },
        "kind": { "type": "string", "enum": ["any_of", "all_of"] },
        "name": { "type": "string" },
        "members": {
          "type": "array",
          "minItems": 1,
          "uniqueItems": true,
          "items": { "$ref": "#/definitions/id" }
        },
        "notes": { "type": "string" }
      }
    },
    "presetItem": {
      "type": "object",
      "required": ["equipment_id"],
      "additionalProperties": false,
      "properties": {
        "equipment_id": { "$ref": "#/definitions/id" },
        "params": {
          "type": "object",
          "additionalProperties": {
            "type": ["number", "boolean", "array"],
            "items": { "type": "number" }
          }
        }
      }
    },
    "preset": {
      "type": "object",
      "required": ["id", "name", "items"],
      "additionalProperties": false,
      "properties": {
        "id": { "$ref": "#/definitions/presetId" },
        "name": { "type": "string" },
        "description": { "type": "string" },
        "items": { "type": "array", "items": { "$ref": "#/definitions/presetItem" } }
      }
    }
  }
}
```

Complete example object (one `equipment` entry, one `group`, one `preset` fragment):

```json
{
  "schema_version": 1,
  "equipment": [
    {
      "id": "dumbbells",
      "name": "Dumbbells",
      "category": "free_weights",
      "aliases": ["DBs", "hex dumbbells", "adjustable dumbbells", "Kurzhanteln"],
      "description": "Fixed or adjustable dumbbells. max_kg is the heaviest pair available; step_kg the rack increment.",
      "params": [
        { "key": "max_kg", "type": "number", "unit": "kg", "default": 50, "min": 1, "max": 100,
          "description": "Heaviest dumbbell available (per hand)." },
        { "key": "min_kg", "type": "number", "unit": "kg", "default": 2, "min": 0.5, "max": 20 },
        { "key": "step_kg", "type": "number", "unit": "kg", "default": 2, "min": 0.5, "max": 5,
          "description": "Increment between consecutive dumbbells." }
      ],
      "session_flag": false,
      "packable": false,
      "default_in": ["base_gym", "home_gym", "hotel_gym"],
      "notes": "Hotel racks commonly stop at 20-25 kg; the planner warns when a prescription exceeds max_kg."
    }
  ],
  "groups": [
    { "id": "rack_or_spotter", "kind": "any_of", "name": "Safeties or spotter",
      "members": ["power_rack", "half_rack", "spotter"],
      "notes": "A squat_rack without safeties does not qualify." },
    { "id": "bb_loaded", "kind": "all_of", "name": "Loaded barbell",
      "members": ["barbell", "plates"] }
  ],
  "presets": [
    { "id": "hotel_gym", "name": "Hotel gym",
      "description": "Typical hotel fitness room.",
      "items": [
        { "equipment_id": "dumbbells", "params": { "max_kg": 20, "min_kg": 2, "step_kg": 2 } },
        { "equipment_id": "adjustable_bench" },
        { "equipment_id": "mat" },
        { "equipment_id": "treadmill" },
        { "equipment_id": "stationary_bike" },
        { "equipment_id": "elliptical" }
      ] }
  ]
}
```

### 6.2 `exercises.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://gymcoach.local/schemas/exercises.schema.json",
  "title": "GymCoach exercise library",
  "type": "object",
  "required": ["schema_version", "rep_profiles", "exercises"],
  "additionalProperties": false,
  "properties": {
    "schema_version": { "type": "integer", "minimum": 1 },
    "rep_profiles": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/definitions/repProfile" }
    },
    "exercises": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/definitions/exercise" }
    }
  },
  "definitions": {
    "id": { "type": "string", "pattern": "^[a-z][a-z0-9_]{1,63}$" },
    "pattern": {
      "type": "string",
      "enum": ["squat", "hinge", "lunge_split", "horizontal_push", "incline_push",
               "vertical_push", "chest_fly", "horizontal_pull", "vertical_pull",
               "elbow_flexion", "elbow_extension", "shoulder_abduction",
               "shoulder_er_rear_delt", "scapular_elevation", "hip_extension_isolation",
               "hip_abduction", "hip_adduction", "knee_flexion_isolation",
               "knee_extension_isolation", "calf", "core_anti_extension",
               "core_anti_rotation", "core_flexion", "core_rotation", "carry", "grip",
               "power", "cardio", "mobility"]
    },
    "muscle": {
      "type": "string",
      "enum": ["chest", "front_delts", "side_delts", "rear_delts", "rotator_cuff", "lats",
               "upper_back", "traps_upper", "lower_back", "biceps", "triceps", "forearms",
               "abs", "obliques", "hip_flexors", "quads", "hamstrings", "glutes",
               "glute_med", "adductors", "calves"]
    },
    "loadType": {
      "type": "string",
      "enum": ["barbell", "dumbbell", "machine", "cable", "bodyweight", "band",
               "kettlebell", "smith", "sled", "medicine_ball", "cardio", "none"]
    },
    "goal": {
      "type": "string",
      "enum": ["strength", "hypertrophy", "endurance", "general_fitness"]
    },
    "difficulty": { "type": "string", "enum": ["beginner", "intermediate", "advanced"] },
    "batch": { "type": "integer", "minimum": 1, "maximum": 6 },
    "muscleWeight": {
      "type": "object",
      "required": ["muscle", "weight"],
      "additionalProperties": false,
      "properties": {
        "muscle": { "$ref": "#/definitions/muscle" },
        "weight": { "type": "number", "enum": [1.0, 0.5, 0.25] }
      }
    },
    "equipmentOption": {
      "type": "object",
      "required": ["all_of"],
      "additionalProperties": false,
      "properties": {
        "all_of": {
          "type": "array",
          "items": { "$ref": "#/definitions/id" },
          "uniqueItems": true,
          "description": "Equipment ids, any_of group ids or all_of bundle ids. Empty = no equipment."
        },
        "load_type": { "$ref": "#/definitions/loadType",
                       "description": "Override of the exercise-level load_type for this option." },
        "note": { "type": "string" },
        "preferred": { "type": "boolean", "default": false }
      }
    },
    "intRange": {
      "type": "array",
      "items": { "type": "integer", "minimum": 0 },
      "minItems": 2,
      "maxItems": 2
    },
    "goalPrescription": {
      "type": "object",
      "required": ["range", "rir"],
      "additionalProperties": false,
      "properties": {
        "range": { "$ref": "#/definitions/intRange" },
        "rir": { "$ref": "#/definitions/intRange" },
        "rir_last_week": { "$ref": "#/definitions/intRange" },
        "sets": { "$ref": "#/definitions/intRange" },
        "note": { "type": "string" }
      }
    },
    "repProfile": {
      "type": "object",
      "required": ["id", "unit", "by_goal"],
      "additionalProperties": false,
      "properties": {
        "id": { "$ref": "#/definitions/id" },
        "unit": { "type": "string", "enum": ["reps", "seconds", "metres", "minutes"] },
        "by_goal": {
          "type": "object",
          "required": ["strength", "hypertrophy", "endurance", "general_fitness"],
          "additionalProperties": false,
          "properties": {
            "strength": { "$ref": "#/definitions/goalPrescription" },
            "hypertrophy": { "$ref": "#/definitions/goalPrescription" },
            "endurance": { "$ref": "#/definitions/goalPrescription" },
            "general_fitness": { "$ref": "#/definitions/goalPrescription" }
          }
        },
        "notes": { "type": "string" },
        "sources": { "type": "array", "items": { "type": "string" } }
      }
    },
    "increment": {
      "type": "object",
      "required": ["kind"],
      "additionalProperties": false,
      "properties": {
        "kind": { "type": "string",
                  "enum": ["kg", "band_level", "reps", "time", "distance", "box_height", "none"] },
        "default_kg": { "type": "number", "exclusiveMinimum": 0 },
        "min_kg": { "type": "number", "exclusiveMinimum": 0 },
        "per_hand": { "type": "boolean", "default": false },
        "direction": { "type": "string", "enum": ["load", "assist"], "default": "load" },
        "note": { "type": "string" }
      }
    },
    "flags": {
      "type": "object",
      "required": ["compound", "spinal_loading", "shoulder_sensitive", "knee_sensitive",
                   "low_back_sensitive", "requires_spotter_or_safeties"],
      "additionalProperties": false,
      "properties": {
        "compound": { "type": "boolean" },
        "spinal_loading": { "type": "boolean" },
        "shoulder_sensitive": { "type": "boolean" },
        "knee_sensitive": { "type": "boolean" },
        "low_back_sensitive": { "type": "boolean" },
        "requires_spotter_or_safeties": { "type": "boolean" }
      }
    },
    "content": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "description": { "type": "string" },
        "setup": { "type": "array", "items": { "type": "string" } },
        "cues": { "type": "array", "items": { "type": "string" } },
        "mistakes": { "type": "array", "items": { "type": "string" } },
        "safety": { "type": "array", "items": { "type": "string" } },
        "substitution_rationale": {
          "type": "object",
          "additionalProperties": { "type": "string" }
        },
        "review_notes": { "type": "string" }
      }
    },
    "exercise": {
      "type": "object",
      "required": ["id", "name", "pattern", "muscles", "equipment_options", "load_type",
                   "unilateral", "difficulty", "rep_profile", "increment", "progression",
                   "substitutions", "flags", "batch"],
      "additionalProperties": false,
      "properties": {
        "id": { "$ref": "#/definitions/id" },
        "name": { "type": "string", "minLength": 1 },
        "aliases": { "type": "array", "items": { "type": "string" }, "default": [] },
        "pattern": { "$ref": "#/definitions/pattern" },
        "muscles": {
          "type": "array",
          "items": { "$ref": "#/definitions/muscleWeight" },
          "description": "Empty for cardio and mobility."
        },
        "equipment_options": {
          "type": "array",
          "minItems": 1,
          "items": { "$ref": "#/definitions/equipmentOption" }
        },
        "load_type": { "$ref": "#/definitions/loadType" },
        "unilateral": { "type": "boolean" },
        "difficulty": { "$ref": "#/definitions/difficulty" },
        "rep_profile": { "$ref": "#/definitions/id" },
        "rep_overrides": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "strength": { "$ref": "#/definitions/goalPrescription" },
            "hypertrophy": { "$ref": "#/definitions/goalPrescription" },
            "endurance": { "$ref": "#/definitions/goalPrescription" },
            "general_fitness": { "$ref": "#/definitions/goalPrescription" }
          }
        },
        "increment": { "$ref": "#/definitions/increment" },
        "progression": {
          "type": "string",
          "enum": ["linear_load", "double_progression", "rep_progression",
                   "time_progression", "band_progression", "distance_or_time", "none"]
        },
        "substitutions": {
          "type": "array",
          "items": { "$ref": "#/definitions/id" },
          "uniqueItems": true,
          "description": "Ranked best-first."
        },
        "flags": { "$ref": "#/definitions/flags" },
        "batch": { "$ref": "#/definitions/batch" },
        "tags": { "type": "array", "items": { "type": "string" }, "default": [] },
        "sources": { "type": "array", "items": { "type": "string" }, "default": [] },
        "content": { "$ref": "#/definitions/content" },
        "content_status": {
          "type": "string",
          "enum": ["todo", "draft", "reviewed"],
          "default": "todo"
        }
      }
    }
  }
}
```

Complete example object (one `rep_profiles` entry and one `exercises` entry):

```json
{
  "schema_version": 1,
  "rep_profiles": [
    {
      "id": "barbell_compound",
      "unit": "reps",
      "by_goal": {
        "strength":        { "range": [3, 6],   "rir": [2, 3], "rir_last_week": [1, 2], "sets": [3, 5] },
        "hypertrophy":     { "range": [5, 10],  "rir": [2, 3], "rir_last_week": [1, 1], "sets": [3, 4] },
        "endurance":       { "range": [10, 15], "rir": [2, 3], "rir_last_week": [1, 2], "sets": [2, 3] },
        "general_fitness": { "range": [6, 10],  "rir": [2, 3], "rir_last_week": [1, 2], "sets": [3, 4] }
      },
      "notes": "Heavy free-weight compounds. rir_last_week floor is 1 for bar-over-body lifts (RP chest guide) and never 0 without safeties or a spotter.",
      "sources": ["RP chest hypertrophy guide (rpstrength.com, fetched 2026-09-04)",
                  "Stronger by Science, reps-in-reserve article (fetched 2026-09-04)"]
    }
  ],
  "exercises": [
    {
      "id": "barbell_bench_press",
      "name": "Barbell Bench Press",
      "aliases": ["bench press", "flat bench", "Bankdrücken"],
      "pattern": "horizontal_push",
      "muscles": [
        { "muscle": "chest", "weight": 1.0 },
        { "muscle": "front_delts", "weight": 0.5 },
        { "muscle": "triceps", "weight": 0.5 }
      ],
      "equipment_options": [
        { "all_of": ["bb_loaded", "bench_flat_any", "rack_or_spotter"], "preferred": true,
          "note": "Safeties set just below chest height, or a spotter." }
      ],
      "load_type": "barbell",
      "unilateral": false,
      "difficulty": "intermediate",
      "rep_profile": "barbell_compound",
      "increment": { "kind": "kg", "default_kg": 2.5, "min_kg": 1.0, "per_hand": false,
                     "direction": "load",
                     "note": "1.0 kg only with 0.5 kg microplates; else 2.5 kg." },
      "progression": "linear_load",
      "substitutions": ["dumbbell_bench_press", "smith_bench_press", "machine_chest_press", "push_up"],
      "flags": {
        "compound": true,
        "spinal_loading": false,
        "shoulder_sensitive": true,
        "knee_sensitive": false,
        "low_back_sensitive": false,
        "requires_spotter_or_safeties": true
      },
      "batch": 1,
      "tags": ["big_lift"],
      "sources": ["ExRx.net Barbell Bench Press (target pec major sternal; synergists pec clavicular, anterior deltoid, triceps)",
                  "NSCA Essentials of Strength Training and Conditioning, exercise technique chapter (spotting rules)"],
      "content": {
        "description": "TODO — batch 1 author",
        "setup": [], "cues": [], "mistakes": [], "safety": []
      },
      "content_status": "todo"
    }
  ]
}
```

### 6.3 Validation tests to write in `:core` (JVM, no Android)

1. Every `id` matches the pattern and is unique across the file; every `substitutions`
   entry, every `rep_profile` and every `equipment_options[].all_of[]` item resolves
   (equipment id, group id or bundle id).
2. `equipment.json`: group members resolve to equipment ids (groups may not nest);
   `default_in` presets exist; preset item params only use declared `params[].key`.
3. Every exercise resolves to *available* in `base_gym` **or** carries a tag
   `optional_equipment` (pendulum_squat, belt_squat, reverse_hyperextension,
   safety_bar_squat, glute_ham_raise, high_row_machine, t_bar_row, air_bike, ski_erg,
   stair_climber, battle_ropes, medicine_ball_slam, sled_push, incline_machine_chest_press,
   lateral_raise_machine, machine_triceps_extension, machine_hip_thrust,
   lying_leg_curl, cable_glute_kickback-machine option, ab_wheel_rollout-wheel option,
   machine_crunch, suspension-trainer-only options).
4. `hotel_gym` preset must make ≥ 40 exercises available (travel viability check) and
   `travel_kit` + no gym must make ≥ 25 available.
5. Muscle weights ∈ {1.0, 0.5, 0.25}; each non-cardio/mobility exercise has exactly one
   or two weight-1.0 muscles.
6. Flags: `requires_spotter_or_safeties` ⇒ some option contains `rack_or_spotter` or a
   Smith machine; `compound` ⇒ ≥ 2 muscles listed.
7. Round-trip: kotlinx-serialization decode → encode → decode equals original (guards
   against silently dropped fields; sacred-content discipline applied to assets).
8. Batch coverage: the union of `batch` values 1..6 equals the 156 ids in §5.12.

---

## 7. Open questions and unverified items

1. **RP back landmarks** (§4.5) were read from the page's embedded image
   (MV 2–6, MEV 6–8, MAV 8–20, MRV 20–26); a WebFetch text summary had returned
   different numbers, so a second human glance at the image before hardcoding is cheap
   insurance.
2. **NSCA Essentials**: only the 5th-edition product page was fetched (©2027 listing);
   chapter numbers for the 4th edition are from memory. Batch authors should cite the
   edition they actually have.
3. **Increment defaults** (dumbbell 2 kg, kettlebell 4 kg, stack 5 kg + 2.5 kg add-on)
   are field conventions, not verified against manufacturer specs; all are per-gym
   parameters so a wrong default is one edit away.
4. **Johannes' real home gym** is unknown; `home_gym` is a generic garage preset.
5. **Cardio protocols** (zones, HIIT formats) are only sketched in the `cardio` profile;
   the cardio/nutrition research task owns them.
6. The 0.25 tier is our refinement of the fractional method (Pelland et al. only tested
   1 / 0.5 / 0). If the planner ever compares fractional totals to published
   dose-response curves, use the `direct` + 0.5·`secondary` view and ignore 0.25s.
7. Exercises deliberately left out (add later if the coach chat asks for them):
   decline bench press, upright row, dumbbell front raise, pullover, concentration /
   spider curl, wrist curl, neutral-grip pulldown (grip variant of `lat_pulldown`),
   Zercher / box / pause squat variants, deficit / snatch-grip deadlift, hip thrust
   single-leg, seated good morning, Turkish get-up, burpee, mountain climber,
   handstand push-up, ring dips, muscle-up.
