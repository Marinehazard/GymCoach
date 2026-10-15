# R3 — Evidence-based training, cardio, nutrition, supplement and recovery rule book

Status: research deliverable for the GymCoach `:core` planner (2026-09-04).
Audience: implementers of the offline planner and the coach-chat system prompt.
Scope: every rule is written as a numeric or decision statement with an ID, a confidence tag, and a citation. Section 10 collects all IDs in one table. Section 12 lists what could not be verified live.

## 0. Conventions, definitions, policy

### 0.1 Confidence tags

| Tag | Meaning |
|---|---|
| **strong** | Supported by meta-analysis/meta-regression or a major position stand, replicated, direction and rough magnitude not seriously disputed. |
| **moderate** | Supported by ≥1 good meta-analysis or several RCTs, or a position stand, but magnitude/threshold is uncertain or population-specific. |
| **weak** | Mechanistic, extrapolated, single-study, or practitioner convention. Numeric values are planner defaults, not evidence-derived thresholds. Must be user-adjustable. |
| **contested** | Credible evidence on both sides, or major bodies disagree. Planner should expose the choice. |

Citation style: author year, journal or position stand; URL where fetched live for this document. Full reference list in Section 11.

### 0.2 Sacred-content and no-diagnosis policy (POL)

- **POL-01** Numbers the user logs (load, reps, RIR, body weight, sleep hours) are never altered by the planner. Any derived value (e1RM, TDEE, moving average) is shown as derived with its formula ID. Any automatic plan adjustment shows the rule ID and the input that triggered it. *(Project requirement; REQUIREMENTS.md.)*
- **POL-02** The app does not diagnose. All health-related outputs are phrased as "this pattern is a reason to see a professional", never as a diagnosis. Red flags in RED-01 stop training advice for that body part/session and hand off. *(Policy; consistent with ACSM pre-participation screening logic, Riebe 2015.)*
- **POL-03** Supplement advice is generic education; it always carries the legal/medical caveat in SUP-12 and never overrides a physician's instruction.

### 0.3 Global definitions (DEF)

- **DEF-01 Training age classes.** `novice` = < 6 months of consistent (≥ 2 sessions/week) resistance training, or any person with ≥ 12 months of complete layoff; `intermediate` = 6–24 months consistent; `advanced` = > 24 months consistent. **moderate** — class boundaries follow ACSM's novice/intermediate/advanced usage (novice = untrained or "not trained for several years", intermediate ≈ 6 months of consistent experience, advanced = years) (ACSM 2009 position stand, Progression models; https://pubmed.ncbi.nlm.nih.gov/19204579/). The 24-month cut is a planner convention.
- **DEF-02 RIR ↔ RPE mapping.** RPE 10 = 0 RIR; 9.5 = could add load but not a rep; 9 = 1 RIR; 8 = 2; 7 = 3; 6 = 4; ≤ 5 = ≥ 5 RIR. **strong** (Zourdos 2016, J Strength Cond Res, https://pubmed.ncbi.nlm.nih.gov/26049792/; Helms 2016, Strength Cond J, https://pmc.ncbi.nlm.nih.gov/articles/PMC4961270/).
- **DEF-03 Load zones.** `heavy` = ≤ 8 RM (≈ ≥ 80 % 1RM); `moderate` = 9–15 RM (≈ 65–79 % 1RM); `light` = 16–30 RM (≈ 50–64 % 1RM). **moderate** — class cut-points follow Lopez 2021 (high ≤ 8 RM, moderate 9–15 RM, low > 15 RM; Med Sci Sports Exerc; https://pubmed.ncbi.nlm.nih.gov/33433148/). %1RM mapping uses E1RM-03.
- **DEF-04 Working set.** A set performed at ≤ 4 RIR (RPE ≥ 6) with the intent to progress. Warm-up/ramp sets never count (WU-03). **moderate** — the volume literature counts sets taken to or near failure; sets far from failure contribute little (Robinson 2024; Refalo 2023).
- **DEF-05 Direct vs fractional set credit.** For weekly volume accounting per muscle: prime-mover muscle receives 1.0 set credit; synergists receive 0.5. **moderate** — the "fractional" weighting (indirect sets = 0.5) was the best-supported quantification in the 2025 dose–response meta-regression (Pelland 2025, Sports Med; abstract via Europe PMC, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:10.1007/s40279-025-02344-w&resultType=core&format=json). The muscle-to-exercise mapping below is a planner convention (**weak**):

| Exercise pattern | 1.0 credit | 0.5 credit |
|---|---|---|
| Squat / leg press / hack squat | quads | glutes, adductors |
| Hip hinge (RDL, deadlift, good morning) | hamstrings | glutes, spinal erectors |
| Hip thrust / glute bridge | glutes | hamstrings |
| Lunge / split squat / step-up | quads, glutes | hamstrings |
| Horizontal press (bench, push-up, chest press) | chest | triceps, front delts |
| Vertical press (OHP, DB press) | shoulders (front/side) | triceps, upper chest |
| Dip | chest, triceps | front delts |
| Vertical pull (pull-up, pulldown) | lats | biceps, rear delts |
| Horizontal pull (rows) | upper back/lats | biceps, rear delts |
| Curl variants | biceps | forearms |
| Extension/pressdown variants | triceps | — |
| Lateral raise | side delts | — |
| Face pull / reverse fly | rear delts | upper back |
| Leg curl | hamstrings | — |
| Leg extension | quads | — |
| Calf raise | calves | — |
| Ab / anti-extension / carry | abs/core | — |

- **DEF-06 Goals enum.** `hypertrophy`, `strength`, `general_fitness` (health + some of each), `fat_loss` (hypertrophy-style training in deficit), `endurance_support` (RT while cardio is the priority). Rules below reference these.
- **DEF-07 Exercise classes for increments/RIR.** `lower_barbell` (squat, deadlift, RDL, hip thrust bar), `upper_barbell` (bench, OHP, row), `dumbbell`, `machine` (pin/plate loaded), `cable`, `bodyweight` (pull-up, dip, push-up), `isolation` (any single-joint regardless of implement; if a movement is single-joint, `isolation` rules override implement rules for RIR, increments use the implement rule).

---

## 1. Resistance training

### 1.1 Weekly volume (VOL)

- **VOL-01 Minimum effective dose (hypertrophy).** ≥ 4 working sets per muscle group per week, 6–15 RM loads (15–40 reps acceptable if taken to failure), produces measurable hypertrophy. **strong** (Iversen 2021, Sports Med narrative review, abstract via Europe PMC: "minimum of 4 weekly sets per muscle group using a 6–15 RM loading range"; https://pubmed.ncbi.nlm.nih.gov/34125411/).
- **VOL-02 Minimum effective dose (1RM strength, trained).** 1 set × 6–12 reps at 70–85 % 1RM per lift, 2–3 sessions/week, taken to or near failure, 8–12 weeks → statistically significant (sub-optimal) 1RM gains in squat/bench. **moderate** (Androulakis-Korakakis 2020, Sports Med SR/MA, https://pubmed.ncbi.nlm.nih.gov/31797219/).
- **VOL-03 Dose–response shape.** Hypertrophy and strength both increase with weekly sets with diminishing returns; diminishing returns are "considerably more pronounced" for strength. There is no evidence-verified hard ceiling for hypertrophy inside practical ranges. **strong** (Pelland 2025, Sports Med, 67 studies / 2,058 participants; Europe PMC abstract as above; Schoenfeld 2017 J Sports Sci: each additional weekly set ≈ +0.37 % muscle gain, ≥ 10 sets/week threshold for near-maximal hypertrophy in that data set; https://pubmed.ncbi.nlm.nih.gov/27433992/).
- **VOL-04 Default weekly hypertrophy ranges by training age (fractional sets per muscle).** novice 8–12; intermediate 12–18; advanced 15–25. Start each mesocycle at the lower bound; never exceed the upper bound without the user overriding. **moderate** for 10–20 being the productive band (Schoenfeld 2017; Pelland 2025); **weak** for the exact class boundaries. Trained males adding sets progressively (22 → 52 quad sets/week over 12 weeks) gained more quadriceps size than a constant 22 sets, so advanced users tolerate the top of the range and beyond (Enes 2024, J Strength Cond Res, reported in secondary sources; **not fetched live**).
- **VOL-05 Default weekly strength volume per main lift.** 4–10 fractional sets/week of the lift and its close variants, over ≥ 2 sessions. Beyond ~10 sets/lift/week returns are small. **moderate** (Pelland 2025 diminishing returns for strength; Androulakis-Korakakis 2020 for the floor).
- **VOL-06 Per-session cap.** Default 6–10 working sets per muscle per session; hard cap 12. If weekly target ÷ sessions > 12, add a session for that muscle (FRQ-01) instead of stacking. **weak** — secondary summaries of the Pelland/Remmert meta-regressions place the per-session point of diminishing returns at ≈ 11 fractional sets, and the data favour ≥ 2 weekly sessions when exceeding ≈ 11 weekly sets; the primary paper's per-session numbers could not be verified live (see Section 12).
- **VOL-07 Distribution.** Distribute weekly sets for a muscle as evenly as possible across its sessions; minimum 2 sets for a muscle in any session in which it appears. **weak** (convention derived from VOL-06 and FRQ-01).
- **VOL-08 Progression of volume within a mesocycle.** Start at the lower bound of VOL-04; add +1 set per muscle per week (max +2) for muscles where RIR reports are at or above target and soreness < 3/5; stop adding when the upper bound or VOL-06 cap is reached; then deload (DLD-01). **weak** (Enes 2024 progressive-set design; convention).
- **VOL-09 Deficit adjustment.** In `fat_loss`, keep weekly sets at 60–100 % of the user's accustomed volume, never below VOL-01; prefer trimming sets over trimming load. **weak** (maintenance-volume principle; Iversen 2021 floor).

### 1.2 Frequency (FRQ)

- **FRQ-01 Hypertrophy frequency.** Train each muscle ≥ 2×/week when weekly sets ≥ 10; frequency itself does not add growth when weekly volume is equated. **strong** (Schoenfeld 2016 Sports Med: 2×/wk > 1×/wk; https://pubmed.ncbi.nlm.nih.gov/27102172/. Schoenfeld 2019 J Sports Sci: no difference when volume equated; https://pubmed.ncbi.nlm.nih.gov/30558493/. Pelland 2025: frequency effect on hypertrophy "compatible with negligible effects").
- **FRQ-02 Strength frequency.** Practise each main lift ≥ 2×/week; strength gains increase with frequency with diminishing returns. **moderate** (Pelland 2025: strength gains increase with frequency; Grgic 2018 Sports Med frequency-strength meta, https://pubmed.ncbi.nlm.nih.gov/29470825/).
- **FRQ-03 Frequency chooser.** Given `days_per_week` D and `session_minutes` M, choose the split in SPLIT-01 that (a) hits every muscle ≥ 2×/week, (b) keeps per-session sets ≤ SESS-01 budget for M, (c) keeps per-muscle sets ≤ VOL-06. If (a) is impossible (D = 1), fall back to full-body 1× with VOL-01 floor and inform the user that ≥ 2 days is recommended. **moderate** (derived from FRQ-01, VOL-06).

### 1.3 Load and rep ranges (LOAD)

- **LOAD-01 Hypertrophy is load-agnostic between ~30 % and ~85 % 1RM (≈ 5–30 reps) when sets are taken close to failure.** **strong** (Schoenfeld 2017 low- vs high-load meta, J Strength Cond Res, https://pubmed.ncbi.nlm.nih.gov/28834797/; Lopez 2021 network meta, https://pubmed.ncbi.nlm.nih.gov/33433148/; Currier 2023 BJSM Bayesian network meta: "all prescriptions comparably promoted muscle hypertrophy", https://research-information.bris.ac.uk/en/publications/resistance-training-prescription-for-muscle-strength-and-hypertro/).
- **LOAD-02 Maximal strength needs heavy loads.** ≥ 80 % 1RM (≤ ~6–8 reps) maximizes 1RM strength gains. **strong** (Currier 2023: "higher-load (> 80 % 1RM) prescriptions maximized strength gains"; Schoenfeld 2017).
- **LOAD-03 Rep-range defaults by goal and class.**

| Goal | `lower_barbell` / `upper_barbell` main lifts | `machine` / `cable` compounds | `dumbbell` compounds | `isolation` | `bodyweight` |
|---|---|---|---|---|---|
| strength | 3–6 (top sets), 4–8 (back-off) | 6–10 | 6–10 | 10–15 | 5–10 (+load) |
| hypertrophy | 5–10 | 8–15 | 8–15 | 10–20 | 8–20 |
| general_fitness / fat_loss | 6–10 | 8–15 | 8–15 | 12–20 | 8–20 |
| endurance_support | 6–10 (2 sets) | 10–15 | 10–15 | 12–20 | 10–20 |

**moderate** for the strength/hypertrophy split (LOAD-01/02); **weak** for exact numbers. Rationale for keeping barbell compounds ≤ 10 reps: RIR estimation is more accurate at ≤ 12 reps (Halperin 2022, Sports Med, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Accuracy%20in%20Predicting%20Repetitions%20to%20Task%20Failure%20in%20Resistance%20Exercise%22&resultType=core&format=json) and technical breakdown under fatigue is a shoulder/back injury risk factor (Kolber 2010, J Strength Cond Res, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Shoulder%20injuries%20attributed%20to%20resistance%20training%3A%20a%20brief%20review%22&resultType=core&format=json).
- **LOAD-04 Load zone table (planner reference).**

| Zone | %1RM | Reps | Primary use |
|---|---|---|---|
| max strength | 85–95 | 1–5 | strength top sets, advanced only |
| heavy | 78–85 | 5–8 | strength/hypertrophy compounds |
| moderate | 65–78 | 8–15 | hypertrophy default |
| light | 50–65 | 15–30 | isolation, joint-friendly, deload alt |
| power (masters) | 40–60 | 6–10, max concentric velocity | AGE-02 |

**moderate** (Lopez 2021 classes; ACSM 2009 novice 8–12 RM, advanced 1–12 RM periodized; power load for older adults per Fragala 2019 — see Section 12).

### 1.4 Proximity to failure (RIR)

- **RIR-01 Hypertrophy target: 0–3 RIR; extra benefit tapers past ~2 RIR.** Failure adds only a trivial advantage over non-failure (ES ≈ 0.19). **strong** (Robinson 2024 Sports Med meta-regressions: hypertrophy improves as sets end closer to failure, strength unrelated to RIR, https://pubmed.ncbi.nlm.nih.gov/38970765/; Refalo 2023 Sports Med SR/MA: set failure vs non-failure ES 0.19 [0.00, 0.37], momentary failure ES 0.12, https://pubmed.ncbi.nlm.nih.gov/36334240/; Refalo 2024 J Sports Sci RCT: 1–2 RIR = failure for quadriceps growth in trained people, https://www.tandfonline.com/doi/full/10.1080/02640414.2024.2321021).
- **RIR-02 Strength target: 2–5 RIR is sufficient; training to failure is not required.** **strong** (Robinson 2024: RIR slopes for strength contained null).
- **RIR-03 RIR defaults by exercise class (hypertrophy phases).** `lower_barbell` 2–3 (never 0 without safeties/spotter); `upper_barbell` 1–3 (bench never 0 without safeties); `dumbbell` compounds 1–2; `machine`/`cable` compounds 1–2; `isolation` 0–2; `bodyweight` 0–2. Strength phases: main lifts 2–4, back-off/accessories 1–3. **moderate** (RIR-01/02 plus fatigue cost of failure: failure sets increase neuromuscular fatigue without extra growth, Refalo 2023/2024).
- **RIR-04 RIR across a mesocycle.** Week 1: target RIR + 1; middle weeks: target; final week: target − 1 (floor 0 on isolation, 1 on compounds); deload week: 4–5 RIR (DLD-02). **weak** (convention consistent with RIR-01 and DLD-02).
- **RIR-05 RIR reporting bias correction.** Users under-predict reps in reserve by ≈ 1 rep on average; accuracy improves at ≤ 12 reps, closer to failure, and on later sets. Planner: for sets > 12 reps treat a reported "2 RIR" as effectively 3 RIR when auditing progression; run an AMRAP calibration set on one machine/isolation exercise every 2–3 weeks. **moderate** (Halperin 2022; Zourdos 2016).

### 1.5 Rest intervals (REST)

- **REST-01 Hypertrophy: ≥ 60–90 s minimum; default 90–120 s; longer gives no further measurable hypertrophy advantage.** **moderate** (Singer 2024 Frontiers Sports Act Living Bayesian meta, 9 studies: "no appreciable differences in hypertrophy when resting > 90 s", short ≤ 60 s slightly worse; https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1429789/full). In trained men 3 min beat 1 min for both strength and hypertrophy (Schoenfeld 2016 J Strength Cond Res, https://journals.lww.com/nsca-jscr/fulltext/2016/07000/longer_interset_rest_periods_enhance_muscle.3.aspx).
- **REST-02 Strength / heavy compounds: 2–5 min.** Use 3–5 min for ≤ 6 RM top sets. **strong** (ACSM 2009: 3–5 min for heavy loading; Grgic 2018 rest-interval review, https://www.semanticscholar.org/paper/953a5adf43cc59b5793958ea6a1c26d0031d1587).
- **REST-03 Isolation / machines: 60–90 s.** Antagonist or non-competing supersets allowed with ≤ 30 s transition to halve session time at equal volume. **moderate** (Iversen 2021: supersets/drop sets/rest-pause "roughly halves training time... while maintaining volume").
- **REST-04 Rest is a time-budget variable.** When session minutes are short, cut rest on `isolation`/`machine` to 60 s before cutting sets; never cut main-lift rest below 2 min. **weak** (derived from REST-01/02 and TIME-01).

### 1.6 Exercise order (ORD)

- **ORD-01 The priority exercise goes first; strength gains are largest in the exercise performed first.** **strong** (Nunes 2021, Eur J Sport Sci SR/MA, https://pubmed.ncbi.nlm.nih.gov/32077380/).
- **ORD-02 Order does not change hypertrophy when volume is equal (ES 0.03).** Default: multi-joint before single-joint; heaviest/most technical first. **strong** (Nunes 2021).
- **ORD-03 Same-session cardio goes after lifting.** Lifting first gives +6.9 % better lower-body dynamic strength outcome; hypertrophy and VO2max unaffected by order. **moderate** (Eddens 2018, Sports Med SR/MA, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22The%20Role%20of%20Intra-Session%20Exercise%20Sequence%20in%20the%20Interference%20Effect%22&resultType=core&format=json).

### 1.7 Compound / isolation mix, unilateral work (MIX, UNI)

- **MIX-01 Adding single-joint exercises to a multi-joint program does not add hypertrophy or strength for the muscles the compounds already train.** Isolation is for muscles under-covered by the compounds (side/rear delts, hamstrings if no hinge, calves, biceps/triceps when pull/push volume is low, abs). **strong** (Gentil 2015, Appl Physiol Nutr Metab, https://cdnsciencepub.com/doi/abs/10.1139/apnm-2015-0109; Rosa 2022 SR/MA: trivial differences multi-joint vs multi-joint + single-joint, https://www.researchgate.net/publication/359785878).
- **MIX-02 Minimum weekly pattern coverage.** Each week must contain ≥ 1 knee-dominant leg exercise, ≥ 1 hip hinge, ≥ 1 horizontal push, ≥ 1 vertical or incline push, ≥ 1 horizontal pull, ≥ 1 vertical pull (or second horizontal pull if no vertical option), optional carry/core. **moderate** (Iversen 2021 minimum: one leg press, one upper pull, one upper push; hinge added for hamstrings/glutes by convention).
- **MIX-03 Compound share.** 60–75 % of weekly working sets from multi-joint exercises; time-constrained sessions → 100 % compounds permitted (TIME-01). **weak** (Iversen 2021 time-efficiency rationale).
- **UNI-01 Unilateral and bilateral training produce the same hypertrophy; strength is specific to the tested mode (bilateral ES 0.56 favouring bilateral training; unilateral ES −0.65 favouring unilateral training).** Planner: use unilateral when equipment forces it (dumbbell-only gyms), for side-to-side asymmetry > 10 %, or for knee/back tolerance (INJ rules). **strong** (Kassiano 2025 Sports Med SR/MA, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:10.1007/s40279-024-02169-z&resultType=core&format=json; Liao 2022 Biol Sport, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9331349/).
- **UNI-02 Counting.** One unilateral set (both sides completed) = 1 working set for the muscle. Session time cost ≈ 1.7 × a bilateral set. **weak** (convention).

### 1.8 Tempo (TEMPO)

- **TEMPO-01 Repetition duration from 0.5 s to 8 s produces similar hypertrophy; deliberately very slow reps (> 10 s) are inferior.** Default cue: controlled 1–3 s eccentric, concentric with maximal intent, no pause except for technique. **strong** (Schoenfeld 2015, Sports Med SR/MA, https://pubmed.ncbi.nlm.nih.gov/25601394/; Wilk 2021, Sports Med review, https://pubmed.ncbi.nlm.nih.gov/34043184/).
- **TEMPO-02 Do not prescribe tempo as a progression variable; prescribe load/reps/sets.** Tempo is a form cue only. **moderate** (same sources).

### 1.9 Range of motion (ROM)

- **ROM-01 Full ROM is the default.** Full vs partial ROM favours full for strength (ES 0.56) and lower-limb hypertrophy (ES 0.88). **strong** (Pallarés 2021, Scand J Med Sci Sports SR/MA, https://onlinelibrary.wiley.com/doi/full/10.1111/sms.14006).
- **ROM-02 Lengthened partials are an acceptable (possibly superior) substitute for hypertrophy when the stretched position is included.** Partial (all types pooled) vs full = trivial (SMD 0.12 [−0.02, 0.26]); partials at long muscle length ≥ full ROM in calves, quads, hamstrings; shortened partials are inferior. **moderate** (Wolf 2023, Int J Strength Cond SR/MA, https://journal.iusca.org/index.php/Journal/article/download/182/250/; Kassiano 2023 J Strength Cond Res calf study, https://pubmed.ncbi.nlm.nih.gov/37015016/; Pedrosa 2022 Eur J Sport Sci knee extension, https://onlinelibrary.wiley.com/doi/10.1080/17461391.2021.1927199; Wolf 2025 PeerJ, https://peerj.com/articles/18904/).
- **ROM-03 Planner use.** Use lengthened partials (a) as a pain-free workaround (INJ rules), (b) as an intensity technique on isolation exercises (partials past failure), (c) when equipment blocks full ROM. Never prescribe shortened-range partials as a substitute. **moderate** (ROM-02 sources).

### 1.10 Session budget and splits (SESS, SPLIT)

- **SESS-01 Time cost model.** Minutes per working set including rest: `lower_barbell` heavy 4.0; `upper_barbell` heavy 3.5; compound moderate (any implement) 2.5; `isolation`/`machine` 1.75; superset pair 2.5 for two sets. Warm-up (WU-01) 8–12 min (5 min if TIME-01 trims). Session budgets → working sets: 30 min ≈ 8–10; 45 min ≈ 14–18; 60 min ≈ 20–26; 90 min ≈ 30–36. **weak** (derived from REST rules; convention).
- **SESS-02 Per-session total cap.** 25 working sets (novice 20; advanced 32) regardless of time available. **weak** (convention; fatigue accumulation reduces later-set quality, ORD-01 logic).
- **SPLIT-01 Split selection table.**

| Days/week | Default split | Muscle frequency | Session length that fits | Notes |
|---|---|---|---|---|
| 1 | Full body | 1× | 60–90 | below FRQ-01; VOL-01 floor only |
| 2 | Full body ×2 | 2× | 45–90 | best for 2 days (FRQ-01) |
| 3 | Full body ×3 (or Upper/Lower/Full) | 2–3× | 45–75 | 3× full body preferred for novices |
| 4 | Upper/Lower ×2 | 2× | 45–75 | first choice for intermediates |
| 5 | Upper/Lower/Push/Pull/Legs, or U/L/U/L + Full | 2× | 45–75 | choose by priority muscle |
| 6 | Push/Pull/Legs ×2 or Upper/Lower ×3 | 2× (PPL) / 3× (U/L) | 45–60 | advanced; per-session sets fall, frequency rises |

**moderate** for the principle (FRQ-01/02, VOL-06), **weak** for the exact mapping. Rule: a split is valid only if it satisfies FRQ-03.
- **SPLIT-02 Consecutive-day rule.** Do not schedule two heavy sessions for the same muscle on consecutive days; minimum 48 h between heavy sessions of the same muscle group; 24 h is acceptable for light/moderate work. **weak** (recovery convention; supported indirectly by 96-h recovery trajectories in Stults-Kolehmainen 2014).

### 1.11 Progression (PROG)

- **PROG-01 Double progression (default for all classes).** Each exercise has a rep range [lo, hi] (LOAD-03) and a target RIR (RIR-03). When every working set reaches `hi` reps at ≤ target RIR in a session → next session load += increment (PROG-02) and reps reset toward `lo`. If reps fall below `lo` on ≥ 2 sets, keep load. **strong** (ACSM 2009: increase load 2–10 % when the current load can be performed for 1–2 reps over target on two consecutive sessions; https://pubmed.ncbi.nlm.nih.gov/19204579/).
- **PROG-02 Load increments by lift class.**

| Class | Increment | Approx. % | Notes |
|---|---|---|---|
| `lower_barbell` | +2.5 kg (women/novice) to +5 kg | 2.5–5 % | +2.5 kg once load > 100 kg |
| `upper_barbell` | +1.25 to +2.5 kg | 2–3 % | microplates if available |
| `dumbbell` | next available pair (typically +1 to +2 kg per hand) | ≤ 10 % | if jump > 10 %, add 1–2 reps first |
| `machine` | +1 plate (pin) | 5–10 % | if jump > 10 %, extend `hi` by 2 reps before load jump |
| `cable` | +1 plate or half-plate | 5–10 % | as machine |
| `bodyweight` | +1–2 reps → then +2.5–5 kg external (belt/vest) or harder variant | — | AMRAP with RIR target |
| `isolation` | smallest implement step | 2–5 % | prefer +reps |

**moderate** — the 2–10 % band (lower for small muscle mass exercises, higher for large) is ACSM 2009; the per-implement steps are hardware conventions.
- **PROG-03 Progression cadence by training age.** `novice`: attempt load increase on main lifts every session that hits PROG-01 (linear); `intermediate`: weekly (per exercise); `advanced`: per mesocycle with undulating rep ranges across the week (e.g., heavy/moderate days). **moderate** (ACSM 2009 novice 8–12 RM linear; intermediate/advanced periodized 1–12 RM).
- **PROG-04 Autoregulated load.** If reported RIR ≥ target + 2 on all sets of an exercise, apply PROG-02 next session even if `hi` reps were not reached; if RIR ≤ target − 2 on the first set, reduce load 5 % for the remaining sets (do not silently change logged numbers; show the rule). Autoregulated RIR loading beat fixed loading for 12-week strength gains. **moderate** (Graham & Cleather 2021, J Strength Cond Res, https://research.stmarys.ac.uk/id/eprint/3067/3/Graham-Cleather-Autoregulation-Repetitions-in-Reserve.pdf; Helms 2018 Front Physiol RPE vs %1RM, https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2018.00247/full).
- **PROG-05 Novice conservative start.** First 2 weeks: loads that leave 3–4 RIR; increments per PROG-02 begin week 3. **weak** (convention; reduces early soreness/injury risk).

### 1.12 Plateau detection and response (PLAT)

- **PLAT-01 Plateau definition.** For a given exercise, no improvement in best-set e1RM (E1RM-01, ≤ 10 reps sets) or in reps-at-load across ≥ 3 consecutive sessions (≈ 2–3 weeks) while readiness (RDY-01) was ≥ Amber on those days. **weak** (convention; ACSM 2009 two-session rule used for progression, extended to three for plateau).
- **PLAT-02 Response ladder (apply one step per week, in order).** (1) audit sleep/protein/calories (NUT/SLP rules) and fix the largest gap; (2) change rep range one zone (LOAD-04) for that exercise; (3) swap to a variation (same pattern); (4) add +1–2 sets/week if under VOL-04 upper bound; (5) if fatigue markers present (DLD-03) → deload. **weak** (convention).
- **PLAT-03 Regression trigger.** e1RM drop ≥ 5 % on two consecutive sessions of a main lift without an external cause (illness, travel, sleep < 6 h) → reactive deload (DLD-02). **weak** (Bell 2025 describes reactive/autoregulatory deloads; threshold is convention).

### 1.13 Deloads (DLD)

- **DLD-01 Proactive deload cadence.** Deloads are commonly pre-planned every 4–8 weeks; surveyed strength/physique athletes deload every 5.6 ± 2.3 weeks for 6.4 ± 1.7 days. Planner defaults: `novice` — no scheduled deload in the first 12 weeks, reactive only; `intermediate` — every 6–8 weeks; `advanced` or volumes at VOL-04 upper bound — every 4–6 weeks. **moderate** (Bell 2025, Strength Cond J, DOI 10.1519/SSC.0000000000000910, https://shura.shu.ac.uk/35313/; Rogerson 2024, Sports Med Open survey, https://link.springer.com/article/10.1186/s40798-024-00691-y). Note the only RCT: a 1-week complete break at the midpoint of a 9-week program did not change hypertrophy but slightly reduced lower-body strength gains versus continuous training (Coleman 2024, PeerJ, https://peerj.com/articles/16777/) — deloads are for fatigue management and long-term sustainability, not a growth stimulus (**contested** for benefit on outcomes).
- **DLD-02 Deload prescription (1 week).** Sets −50 %; load −10 to −20 % on compounds (or keep load and halve sets on machines/isolation); RIR 4–5; frequency unchanged; no failure sets; keep warm-up and mobility. **moderate** (Rogerson 2024: volume ↓ via sets and reps, load ↓, RIR ↑, frequency unchanged; Bell 2025: reduce intensity of effort, volume, duration or frequency).
- **DLD-03 Reactive deload triggers (≥ 2 present for ≥ 5 days).** e1RM or reps down ≥ 5 % on two sessions; readiness score Red (RDY-02) ≥ 3 days in 7; soreness ≥ 4/5 persisting > 72 h; sleep quality ≤ 2/5 for ≥ 4 nights; motivation ≤ 2/5 for ≥ 4 days; new joint aches ≥ 3/10. **weak** (Bell 2025 practical framework; thresholds are conventions).
- **DLD-04 Post-deload re-entry.** Week after deload: resume at 95 % of pre-deload loads with sets at VOL-04 lower bound, RIR target + 1; return to normal in week 2. **weak** (convention; Coleman 2024 strength dip after a full week off).

### 1.14 Estimated 1RM (E1RM)

- **E1RM-01 Formulas.** Epley: `1RM = w × (1 + r/30)`; Brzycki: `1RM = w × 36 / (37 − r)`. Use the mean of both for r ≤ 10; the two coincide at r = 10. **strong** for formulas (Epley 1985; Brzycki; summarized https://en.wikipedia.org/wiki/One-repetition_maximum; validation LeSuer 1997 J Strength Cond Res 11(4):211–213).
- **E1RM-02 Valid rep range.** Compute e1RM only from sets with 1 ≤ r ≤ 10 (preferred 2–6); prediction error is within ≈ 3 % for 2–10 reps and degrades above 10 reps (Brzycki is linear and modelled on ≤ 10 reps). Sets with r > 10 produce a "low-confidence" e1RM that is displayed but not used for progression decisions. **strong** (LeSuer 1997; Mayhew 2008 — accuracy better at < 10 RTF; secondary summary https://opensiuc.lib.siu.edu/cgi/viewcontent.cgi?article=1744&context=gs_rp).
- **E1RM-03 RIR-adjusted e1RM.** `r_eff = r + RIR` (cap r_eff at 10 for E1RM-02), then apply E1RM-01. %1RM table from Epley: 1 rep 100 %, 2 94 %, 3 91 %, 4 88 %, 5 86 %, 6 83 %, 8 79 %, 10 75 %, 12 71 %, 15 67 %. **moderate** (derived from E1RM-01; RIR-RPE validity from Zourdos 2016).
- **E1RM-04 Exercise caveats.** Deadlift 1RM is systematically under-estimated by all equations; machines/cables have no meaningful 1RM — track load × reps instead. Use a 14-day rolling best e1RM for trend, never a single set. **moderate** (LeSuer 1997 finding on deadlift; convention for rolling window).

---

## 2. Autoregulation and readiness

### 2.1 Daily readiness (RDY)

- **RDY-01 Inputs and score (0–100).** Collected in ≤ 30 s before a session:

| Input | Scale | Points |
|---|---|---|
| Sleep hours last night | hours | ≥ 7.5 → 20; 7.0–7.4 → 16; 6.0–6.9 → 10; 5.0–5.9 → 4; < 5 → 0 |
| Sleep quality | 1–5 | (q − 1) × 4 (max 16) |
| Muscle soreness (today, worst region) | 1–5 (5 = severe) | (5 − s) × 4 (max 16) |
| Stress | 1–5 (5 = severe) | (5 − st) × 4 (max 16) |
| Energy/fatigue | 1–5 (5 = full energy) | (e − 1) × 4 (max 16) |
| Motivation | 1–5 | (m − 1) × 4 (max 16) |
| Resting HR delta vs 7-day baseline (optional) | bpm | ≤ +3 → 0 adjustment; +4 to +7 → −8; ≥ +8 → −16 |

Missing optional inputs are ignored (score renormalized). **moderate** — the item set (sleep, fatigue, stress, soreness) predicted 76 % of staleness variance in athletes (Hooper 1995, Med Sci Sports Exerc, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Markers%20for%20monitoring%20overtraining%20and%20recovery%22%20AND%20AUTH:Hooper&resultType=core&format=json); resting HR indices are the most useful HR monitoring tool when compared to a rolling baseline and interpreted against the smallest important change (Buchheit 2014, Front Physiol, https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full; Plews 2013 Sports Med on averaging). The point weights are conventions (**weak**).
- **RDY-02 Readiness → concrete modifiers.**

| Band | Score | Modifier (applied to that session, shown with rule ID) |
|---|---|---|
| Green | ≥ 70 | as planned; if PROG-01 is due, apply it |
| Amber | 45–69 | +1 RIR on all sets; −1 set on every exercise after the first two; loads unchanged; no new load increases |
| Red | < 45 | −40 % sets (round up), +2 RIR; replace `heavy` (≤ 8 RM) barbell sets with `moderate` (8–15) machine/dumbbell variants; or offer a 25–30 min zone-2 + mobility alternative |
| Sleep < 6 h (any score) | — | at least Amber; if session is afternoon/evening, prefer moving it to morning next day (see SLP-02) |

**moderate** for direction (sleep loss ≤ 6 h → mean −7.6 % exercise performance with afternoon/evening tasks most affected, Craven 2022 Sports Med, https://pubmed.ncbi.nlm.nih.gov/35708888/; consecutive nights of restriction reduce multi-joint force output, Knowles 2018 J Sci Med Sport, https://pubmed.ncbi.nlm.nih.gov/29422383/); **weak** for the exact bands.
- **RDY-03 Resting HR rule.** Morning resting HR ≥ +5 bpm above the 7-day rolling mean on ≥ 2 consecutive days → treat as Amber; ≥ +10 bpm or any illness symptom → Red, and skip if febrile. **weak** (Buchheit 2014 rolling baselines and smallest-worthwhile-change logic; numeric thresholds are conventions).
- **RDY-04 Localized soreness ≥ 4/5.** Postpone that muscle's heavy work 24–48 h (swap the session order within the week) or train it at Amber modifiers. **weak** (Stults-Kolehmainen 2014 96-h recovery curves).
- **RDY-05 Chronic stress modifier.** If weekly stress ≥ 4/5 for ≥ 2 consecutive check-ins: weekly sets −20 %, main-lift RIR +1, hold VOL-08 progression, prioritize sleep rules. High life stress slows recovery of force and soreness over 96 h and halves strength gains over 12 weeks. **moderate** (Stults-Kolehmainen 2014, J Strength Cond Res, https://journals.lww.com/nsca-jscr/fulltext/2014/07000/chronic_psychological_stress_impairs_recovery_of.26.aspx; Bartholomew 2008, J Strength Cond Res, https://journals.lww.com/nsca-jscr/fulltext/2008/07000/strength_gains_after_resistance_training__the.26.aspx).
- **RDY-06 Weekly training-load index (optional).** Session load = session RPE (0–10) × minutes; weekly sum shown as a trend; a week > 150 % of the previous 4-week mean flags "spike" (advice only). **weak** for the spike threshold (**contested** in the literature); **moderate** for session-RPE as a valid load measure (Foster 2001, J Strength Cond Res, https://pubmed.ncbi.nlm.nih.gov/11708692/).

### 2.2 Weekly check-in question bank (CHK)

- **CHK-01 Question bank (ask ≤ 8 per week, rotate; each scored 1–5 unless noted).**

| ID | Question | Scoring / trigger |
|---|---|---|
| Q1 | Average sleep hours this week? (number) | < 7 → SLP-03 |
| Q2 | Sleep quality on most nights? | ≤ 2 → SLP-03 |
| Q3 | How many days did you hit your protein target? (0–7) | ≤ 4 → NUT-01 coaching |
| Q4 | 7-day average body weight vs last week (auto) | outside NUT-16 band → NUT-16 |
| Q5 | Average daily steps (auto or estimate) | < STP-01 target → STP-02 |
| Q6 | Stress level this week? | ≥ 4 → RDY-05 |
| Q7 | Any joint pain > 3/10 during training? (which) | yes → INJ rules; > 10 days → RED-02 |
| Q8 | Sessions completed vs planned (auto) | < 70 % → TIME-01 / SPLIT re-plan |
| Q9 | Alcohol: standard drinks this week (number) | > 7 or any day > 3 → NUT-13 |
| Q10 | Days travelling / hotel-gym days next week (number) | ≥ 3 → TRV-01 plan |
| Q11 | Motivation to train next week? | ≤ 2 → DLD-03 check |
| Q12 | Soreness lasting > 48 h after any session? | yes ≥ 2× → VOL-08 pause |
| Q13 | Caffeine after 15:00 on ≥ 3 days? (yes/no) | yes → SUP-02 timing |
| Q14 | Did you finish planned work sets at target RIR most days? | ≤ 2 → PROG-04 audit |
| Q15 | Appetite/hunger (1 = none, 5 = constant) | ≥ 4 in deficit → NUT-17 |

**weak** for the specific items (convention), **moderate** for the underlying constructs (Hooper 1995; Section 6–8 rules).

### 2.3 Escalation: red flags (RED)

- **RED-01 Stop-and-refer symptoms (any one → stop the session, no training advice for that region, recommend medical evaluation; call emergency services for the first four).** Chest pain/pressure or discomfort radiating to arm, jaw, neck during or after exercise; unusual shortness of breath at low effort; dizziness, fainting or near-fainting; palpitations/irregular heartbeat with symptoms; new numbness, tingling or weakness in a limb; loss of bladder/bowel control or saddle numbness with back pain; a joint that is hot, swollen, locking or giving way; a sudden "pop" with immediate loss of function; severe headache that starts during lifting; calf swelling/pain with warmth (possible clot); dark/cola-coloured urine or extreme muscle pain/swelling after training (possible rhabdomyolysis); fever or systemic illness. **strong** as policy — the ACSM pre-participation model refers on "signs or symptoms and/or known cardiovascular, metabolic, or renal disease" and notes cardiovascular events "are often preceded by warning signs/symptoms" (Riebe 2015, Med Sci Sports Exerc, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Updating%20ACSM%27s%20Recommendations%20for%20Exercise%20Preparticipation%20Health%20Screening%22&resultType=core&format=json). The itemized list follows ACSM's signs/symptoms table (not re-verified live).
- **RED-02 Persistent pain.** Musculoskeletal pain > 3/10 that persists > 7–10 days, worsens across sessions, or wakes the user at night → recommend physiotherapist/physician; planner may continue INJ substitutions for other regions only. **weak** (convention).
- **RED-03 Medical-history gate.** Known heart, kidney, liver disease, pregnancy, diabetes on medication, uncontrolled hypertension, or a doctor's restriction → the app asks for clearance before high-intensity cardio (CAR-05), maximal lifts (LOAD-04 max strength zone), or any supplement (SUP-12). **strong** (Riebe 2015 model: known disease + desired intensity determine clearance).

---

## 3. Warm-up (WU)

- **WU-01 Structure (RAMP).** Raise 3–5 min light cardio or calisthenics; Activate + Mobilise 3–5 min dynamic drills for the session's patterns (MOB-01, drill library); Potentiate = ramp sets of the first exercise (WU-02). Total 8–12 min. **strong** that warming up improves performance (79 % of criteria improved; Fradkin 2010, J Strength Cond Res SR/MA, https://journals.lww.com/nsca-jscr/fulltext/2010/01000/effects_of_warming_up_on_physical_performance__a.21.aspx); general + specific warm-up gave +8.4 % leg-press 1RM vs specific only (Abad 2011, J Strength Cond Res, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Combination%20of%20general%20and%20specific%20warm-ups%20improves%20leg-press%20one%20repetition%20maximum%22&resultType=core&format=json). **moderate** for RAMP as the organizing model (Jeffreys 2007, Prof Strength Cond 6:12–18; https://www.researchgate.net/publication/280945961).
- **WU-02 Ramp-set formula (percent of the day's first working load; rest 45–90 s between ramp sets).**

| First exercise type | Ramp sets |
|---|---|
| `heavy` compound, working ≥ 80 % 1RM | empty bar/≈ 40 % × 8 → 55 % × 5 → 70 % × 3 → 80–85 % × 1–2 → work |
| `moderate` compound (8–15 RM) | 50 % × 8 → 70 % × 4–5 → work |
| `light` compound / `machine` compound | 60 % × 8 → work |
| `isolation` | 1 × 8–10 at 50–60 % (skip if muscle already warm from a prior exercise) |
| Subsequent exercises for an already-warm pattern | 0–1 ramp set at 60–70 % |

**moderate** (Abad 2011 specific warm-up 8 × 50 % then 3 × 70 %; Ribeiro 2020 J Hum Kinet specific warm-up for bench/squat, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7558980/; exact percentages are convention).
- **WU-03 Ramp sets never count as working volume** (DEF-04). **strong** (volume literature counts sets to/near failure; Pelland 2025; Schoenfeld 2017).
- **WU-04 No static stretching ≥ 60 s per muscle before lifting; ≤ 30 s per muscle within a dynamic warm-up is harmless.** Static stretching ≥ 60 s → −4.6 % performance vs −1.1 % for < 60 s; dynamic stretching +1.3 %. **strong** (Behm 2016, Appl Physiol Nutr Metab SR, https://cdnsciencepub.com/doi/10.1139/apnm-2015-0235; Kay & Blazevich 2012, Med Sci Sports Exerc: < 30 s −1.1 %, 30–45 s −1.9 %, ≥ 60 s significant reduction, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Effect%20of%20acute%20static%20stretch%20on%20maximal%20muscle%20performance%3A%20a%20systematic%20review%22&resultType=core&format=json).
- **WU-05 Time-constrained warm-up.** When session ≤ 45 min: skip the general Raise phase, keep 2–3 min dynamic drills for the first pattern, and do only the ramp sets. **moderate** (Iversen 2021: "restrict the warm-up to exercise-specific warm-ups").
- **WU-06 Masters / cold / early-morning.** Age ≥ 40, ambient < 15 °C, or session within 60 min of waking → Raise phase 8–10 min and one extra ramp set on the first compound. **weak** (convention; older muscle recovery/damage susceptibility reviewed in Fell & Williams 2008, J Aging Phys Act, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22The%20effect%20of%20aging%20on%20skeletal-muscle%20recovery%20from%20exercise%22&resultType=core&format=json).

---

## 4. Mobility (MOB)

- **MOB-01 Pre-session: dynamic drills only.** 2 sets × 8–10 controlled reps per drill, 3–5 drills matched to the session's patterns. **moderate** (Behm 2016: dynamic stretching small positive effect; WU-04).
- **MOB-02 Long-term ROM dose.** Static stretching improves ROM when weekly time per muscle ≥ 5 min, spread over ≥ 5 days/week; gains plateau at ≈ 4 min per session and ≈ 10 min per week per muscle. Planner: 5–10 min/week per limited joint, 30–60 s holds, 2–4 holds per day, 5+ days. **strong** (Thomas 2018, Int J Sports Med SR, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22The%20Relation%20Between%20Stretching%20Typology%20and%20Stretching%20Duration%22&resultType=core&format=json; Ingram 2025, Sports Med meta-regression, 189 studies, https://pubmed.ncbi.nlm.nih.gov/39614059/).
- **MOB-03 Full-ROM strength training counts as mobility work.** Strength training is as effective as stretching for ROM gains; count deep squats, RDLs, overhead pressing, pull-ups, split squats at full depth toward the joint's weekly mobility dose (1 full-ROM working set ≈ 30 s of stretch credit for that joint). **strong** for equivalence (Afonso 2021, Healthcare SR/MA, https://pubmed.ncbi.nlm.nih.gov/33917036/); **weak** for the credit conversion.
- **MOB-04 Static stretching placement.** After the session or in a separate slot; never ≥ 60 s per muscle immediately before heavy sets (WU-04). **strong**.
- **MOB-05 Foam rolling.** Optional; effects on performance and recovery are minor; use 30–60 s per muscle pre-session only if it helps the user reach position (ROM), or for perceived soreness. **weak** (Wiewelhove 2019, Front Physiol meta, https://www.frontiersin.org/articles/10.3389/fphys.2019.00376).
- **MOB-06 Cool-down.** Optional 5–10 min easy movement; an active cool-down does not improve next-day performance, prevent injury, or blunt adaptation; prescribe only if the user likes it or for HR normalization after HIIT. **strong** that it is optional (Van Hooren & Peake 2018, Sports Med review, https://pubmed.ncbi.nlm.nih.gov/29663142/).
- **MOB-07 Drill library by joint / limitation** (doses per MOB-01 pre-session, MOB-02 chronic). Evidence for specific drills is **weak**; the dose rules are what carry the evidence.

| Joint / limitation | Pre-session dynamic (2 × 8–10) | Chronic (30–60 s holds, 5–10 min/week) | Strength-through-ROM substitute (MOB-03) |
|---|---|---|---|
| Hips: limited squat depth, tight hip flexors | leg swings (front/side), deep squat with heel support and hands assist (hold 5 s at bottom), 90/90 transitions, Cossack squats | half-kneeling hip-flexor stretch (rear glute squeezed), 90/90 hold, pigeon/figure-4, adductor rock-backs | goblet squat to depth, heel-elevated squat, split squat full depth, RDL |
| Thoracic spine: rounded upper back, limited overhead position | open-book rotations, thread-the-needle, cat-camel, wall slides | foam-roller thoracic extension (hands behind head), child's pose with side reach | overhead press full lockout, pull-up dead hang, front squat |
| Shoulders: limited overhead/behind-back, impingement-type pain-free stiffness | band pull-aparts, band dislocates (slow, wide grip), arm circles, scapular push-ups | lat stretch on rack, doorway pec stretch (≤ 90° abduction), sleeper stretch if internal rotation limited | landmine press, neutral-grip DB press, face pulls, external rotation 2 × 15 |
| Ankles: dorsiflexion < 10 cm knee-to-wall | knee-to-wall pulses, ankle circles, calf raises with pause at bottom | wall calf stretch (knee straight and bent), knee-to-wall hold, heel-drop on step | heel-elevated squats (workaround), full-ROM standing/seated calf raises (ROM-02) |
| Hamstrings/posterior chain | leg swings, inchworms, RDL with empty bar | supine strap hamstring stretch, seated straddle | RDL / good morning full ROM, Nordic curl partials |
| Wrists/elbows (front rack, push-ups) | wrist circles, palm/back-of-hand rocks | forearm flexor/extensor stretch 30 s | reverse curls, farmer carries |

---

## 5. Cardio (CAR)

- **CAR-01 Health floor (WHO/ACSM).** 150–300 min/week moderate, or 75–150 min/week vigorous aerobic activity, or an equivalent combination, plus muscle-strengthening ≥ 2 days/week; more than 300 min moderate gives additional benefit. **strong** (WHO 2020 guidelines, Bull 2020 BJSM 54:1451–1462, https://pubmed.ncbi.nlm.nih.gov/33239350/; ACSM Garber 2011 position stand ≥ 150 min moderate or ≥ 75 min vigorous, https://pubmed.ncbi.nlm.nih.gov/21694556/).
- **CAR-02 Zone-2 definition (planner).** Any two of: talk test positive (full sentences possible, singing not), heart rate 60–70 % of HRmax (CAR-03) or 40–59 % of heart-rate reserve, RPE 11–13 on Borg 6–20 (≈ 3–4/10). **moderate** — ACSM moderate intensity = 64–76 % HRmax / 40–59 % HRR / RPE 12–13 (Garber 2011 Table 1; verified via secondary quotations only, see Section 12); talk test correlates with ventilatory threshold (Persinger 2004, Med Sci Sports Exerc, https://pubmed.ncbi.nlm.nih.gov/15354048/).
- **CAR-03 HRmax and HRR.** `HRmax = 208 − 0.7 × age` (SD ≈ ±10 bpm; prefer a measured max if the user has one). Karvonen target HR = HRrest + fraction × (HRmax − HRrest). **strong** for the Tanaka equation (Tanaka 2001, J Am Coll Cardiol 37:153–156, https://www.jacc.org/doi/10.1016/S0735-1097(00)01054-8); **moderate** for using HRR bands (Garber 2011).
- **CAR-04 Vigorous / HIIT zone.** 77–95 % HRmax, 60–89 % HRR, RPE 14–17. **moderate** (Garber 2011 Table 1, secondary verification).
- **CAR-05 HIIT protocol menu (1–2 sessions/week max alongside lifting; requires ≥ 4 weeks of zone-2 base and RED-03 clearance if applicable).**

| Protocol | Prescription | Evidence |
|---|---|---|
| Norwegian 4×4 | 4 × 4 min at 90–95 % HRmax, 3 min active recovery at ~70 % HRmax; 10 min warm-up/cool-down; 3×/week for 8 weeks gave +7.2 % VO2max | **strong** (Helgerud 2007, Med Sci Sports Exerc, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Aerobic%20high-intensity%20intervals%20improve%20VO2max%20more%20than%20moderate%20training%22&resultType=core&format=json) |
| 10 × 1 min | 10 × 60 s at ~90 % HRmax, 60–75 s easy between; 3×/week | **moderate** (Little 2010, J Physiol; secondary summary https://www.gssiweb.org/sports-science-exchange/article/sse-139-physiological-adaptations-to-low-volume-high-intensity-interval-training) |
| Sprint intervals (SIT) | 4–6 × 30 s all-out, 4 min recovery; advanced/healthy only | **moderate** (Gibala 2006, J Physiol, https://pubmed.ncbi.nlm.nih.gov/16825308/) |
| HIIT vs continuous | HIIT +5.5 vs +4.9 mL/kg/min VO2max; HIIT ≈ +1.2 mL/kg/min more | **strong** (Milanović 2015, Sports Med meta, https://pubmed.ncbi.nlm.nih.gov/26243014/) |

- **CAR-06 Interference effect and scheduling.**
  - Concurrent training does not compromise hypertrophy (SMD −0.01) or maximal strength (SMD −0.06); explosive strength is attenuated (SMD −0.28), more so in the same session. **strong** (Schumann 2022, Sports Med SR/MA, 43 studies, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Compatibility%20of%20Concurrent%20Aerobic%20and%20Strength%20Training%20for%20Skeletal%20Muscle%20Size%20and%20Function%22&resultType=core&format=json).
  - In trained lifters, lower-body 1RM gains are reduced when cardio is in the same session (SMD −0.66) but not when separated (SMD −0.10). **moderate** (Petré 2021, Sports Med Open, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8053170/).
  - Running interferes more than cycling with hypertrophy/strength; interference grows with cardio frequency and duration. **moderate** (Wilson 2012, J Strength Cond Res meta, https://pubmed.ncbi.nlm.nih.gov/22002517/).
  - Scheduling rule: separate lifting and hard cardio by ≥ 6 h (24 h better for both adaptations); if same session, lift first (ORD-03); avoid hard running within 24 h before a heavy leg day; prefer cycling/rowing/elliptical adjacent to leg days; for `hypertrophy`/`strength` goals cap cardio at ≤ 3 sessions and ≤ 150 min/week beyond the health floor unless the user asks. **moderate** (Robineau 2016, J Strength Cond Res: 0 h vs 6 h vs 24 h recovery, "avoid ... less than 6-hour recovery", https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Specific%20Training%20Effects%20of%20Concurrent%20Aerobic%20and%20Strength%20Exercises%20Depend%20on%20Recovery%20Duration%22&resultType=core&format=json; Eddens 2018; Wilson 2012).
- **CAR-07 Prescriptions by goal.**

| Goal | Weekly cardio prescription |
|---|---|
| general_fitness | 2 × 30–45 min zone 2 + 1 HIIT (CAR-05) → meets CAR-01 |
| hypertrophy / strength | 2 × 20–30 min zone 2 (health floor), placed per CAR-06; HIIT optional 1×, not on/before leg day |
| fat_loss | 150–250 min/week moderate (1,200–2,000 kcal/week) for modest loss; > 250 min/week for clinically significant loss; add STP-02 steps; HIIT not superior to MICT for weight management | **strong** (Donnelly 2009 ACSM position stand, Med Sci Sports Exerc 41:459–471, https://pubmed.ncbi.nlm.nih.gov/19127177/; Jakicic 2024 ACSM consensus: ≥ 150 min/week moderate, HIIT not superior, https://pubmed.ncbi.nlm.nih.gov/39277776/) |
| endurance_support | user's endurance plan is primary; RT 2×/week full body at VOL-01/02 floors, lifting ≥ 6 h from key cardio sessions |

- **CAR-08 Modality menu by equipment (intensity control via CAR-02/04).**

| Modality | Zone-2 control | HIIT suitability | Leg-day adjacency (CAR-06) |
|---|---|---|---|
| Treadmill walk (incline) | HR / talk test | low | good (walking) |
| Treadmill run | HR / pace | high (4×4, 10×1) | poor — most interference (running) |
| Bike / spin | HR / watts | high (all protocols) | good |
| Rower | HR / split time | high (10×1, SIT) | moderate (hip/back fatigue) |
| Elliptical | HR | moderate | good |
| Stair climber | HR | moderate | poor before leg day |
| Ski erg | HR / watts | high (upper-body dominant) | good for leg-day adjacency |
| Jump rope | RPE / HR | high (interval), impact | poor for calves/knee tolerance |
| Outdoor walk / run | HR / talk test | run: high | walk good; run poor |
| Swimming | RPE / talk test not usable | moderate | good |

**weak** for the adjacency ratings (derived from Wilson 2012 running > cycling; otherwise convention).
- **CAR-09 Cardio progression.** Novice base: 2 × 20 min zone 2; +10 % weekly duration up to CAR-07 targets; introduce HIIT after ≥ 4 weeks of base; progress HIIT by adding one interval (up to 6 for 4×4; 12 for 10×1) before raising intensity. **weak** (ACSM progression principle in Garber 2011; percentages are convention).

---

## 6. Nutrition (NUT)

- **NUT-01 Protein targets.**

| Case | g/kg body weight/day |
|---|---|
| Hypertrophy / strength, energy balance or surplus | 1.6–2.2 (floor 1.6; no further lean-mass benefit above ≈ 1.6 in pooled data, CI to ≈ 2.2) |
| Fat loss (deficit), lean and trained | 2.3–3.1 g/kg fat-free mass, scaled up with deficit severity and leanness (≈ 2.0–2.6 g/kg body weight for lean users; use 1.8–2.2 g/kg BW if BF% is high) |
| General fitness | 1.4–2.0 |
| Age ≥ 65 | ≥ 1.2 (never below), training targets above apply |
| Recomp (NUT-07) | ≥ 2.2, up to 3.0 |

**strong** (Morton 2018 BJSM meta-regression breakpoint 1.62 g/kg/d [CI to ≈ 2.2], https://pubmed.ncbi.nlm.nih.gov/28698222/; ISSN protein position stand 1.4–2.0 and 2.3–3.1 g/kg/d during restriction, Jäger 2017, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5477153/; Helms 2014 IJSNEM 2.3–3.1 g/kg FFM, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22A%20systematic%20review%20of%20dietary%20protein%20during%20caloric%20restriction%20in%20resistance%20trained%20lean%20athletes%22&resultType=core&format=json; Bauer 2013 PROT-AGE 1.0–1.2, ≥ 1.2 if exercising, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Evidence-based%20recommendations%20for%20optimal%20dietary%20protein%20intake%20in%20older%20people%22&resultType=core&format=json).
- **NUT-02 Distribution.** 0.4 g/kg (range 0.4–0.55) per meal across ≥ 4 meals, i.e., 20–40 g every 3–4 h; optional 30–40 g slow protein (casein/dairy) 30 min before sleep. Distribution is a second-order lever after total intake. **moderate** (Schoenfeld & Aragon 2018, JISSN, https://pubmed.ncbi.nlm.nih.gov/29497353/; Jäger 2017: 0.25 g/kg or 20–40 g per serving, every 3–4 h, pre-sleep casein 30–40 g; Snijders 2019 Front Nutr, https://www.frontiersin.org/journals/nutrition/articles/10.3389/fnut.2019.00017/pdf; Schoenfeld 2013 timing meta: effect explained by total intake, https://link.springer.com/article/10.1186/1550-2783-10-53).
- **NUT-03 Meal frequency.** No independent effect on body composition once calories and protein are fixed; the apparent benefit came from a single study. Let the user choose 3–5 meals. **moderate** (Schoenfeld 2015, Nutr Rev meta, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Effects%20of%20meal%20frequency%20on%20weight%20loss%20and%20body%20composition%3A%20a%20meta-analysis%22&resultType=core&format=json).
- **NUT-04 Energy estimate and adaptive correction.**
  - REE (Mifflin–St Jeor): men `10W + 6.25H − 5A + 5`; women `10W + 6.25H − 5A − 161` (W kg, H cm, A years). **strong** — most accurate common equation, within ±10 % for ≈ 82 % of non-obese adults (Mifflin 1990 Am J Clin Nutr 51:241–247; Frankenfield 2005 J Am Diet Assoc SR, https://www.jandonline.org/article/S0002-8223(05)00149-5/abstract).
  - TDEE = REE × activity factor: 1.2 sedentary desk/driving job and < 5,000 steps; 1.375 light (1–3 sessions/week or 5–8k steps); 1.55 moderate (3–5 sessions/week or 8–12k steps or physical job); 1.725 very active (6–7 sessions/week or physical job + training); 1.9 extremely active. **weak** — the multipliers are a long-standing convention, not derived from the Mifflin data; treat as a starting guess only.
  - Adaptive correction (the authoritative estimate after 14 days): every 14 days, `observed_rate = Δ(7-day avg weight) per week`; `expected_rate = (intake − TDEE_est) × 7 / 7700` kg/week; set `TDEE_est += (expected_rate − observed_rate) × 7700 / 7`, clamped to ±150 kcal per adjustment; 7,700 kcal/kg is a heuristic and the true energy content of weight change is lower early in a diet and falls as mass falls. **moderate** (Hall 2011 dynamic model and critique of the 3,500 kcal/lb rule, https://journals.plos.org/ploscompbiol/article?id=10.1371%2Fjournal.pcbi.1000045; https://pubmed.ncbi.nlm.nih.gov/22257646/).
- **NUT-05 Fat-loss targets.** Deficit 300–500 kcal/day (≈ 10–20 % below TDEE); rate 0.5–1.0 % body weight/week; use 0.5–0.7 % when lean (men ≤ 15 %, women ≤ 25 % body fat) or when strength must be preserved; up to 1.0 % at higher body fat; never > 1.5 %/week without professional supervision. **strong** (Helms 2014 JISSN contest-prep review: 0.5–1 %/week, https://pmc.ncbi.nlm.nih.gov/articles/PMC4033492/; Garthe 2011 IJSNEM: 0.7 %/week preserved/increased LBM and strength vs 1.4 %/week, https://pubmed.ncbi.nlm.nih.gov/21558571/; Aragon 2017 ISSN diets position stand: slower loss better preserves lean mass in leaner subjects, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5470183/; Ashtary-Larky 2020 BJN meta: gradual loss preserves RMR and reduces fat more, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=AUTH:%22Ashtary-Larky%22%20AND%20TITLE:%22rapid%20weight%20loss%22&resultType=core&format=json).
- **NUT-06 Lean-gain targets.** Surplus 10–20 % above TDEE (≈ 200–400 kcal/day; 200–300 for advanced); rate 0.25–0.5 % body weight/week for novice/intermediate, 0.1–0.25 % for advanced; if 4-week rate > 0.5 %/week reduce surplus by 150 kcal. **moderate** (Iraki 2019, Sports, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Nutrition%20Recommendations%20for%20Bodybuilders%20in%20the%20Off-Season%22&resultType=core&format=json; Slater 2019 Front Nutr: the optimal surplus "is unknown", https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Is%20an%20Energy%20Surplus%20Required%20to%20Maximize%20Skeletal%20Muscle%20Hypertrophy%22&resultType=core&format=json).
- **NUT-07 Recomposition cases.** Offer maintenance to −200 kcal/day with protein ≥ 2.2 g/kg (up to 3.0) plus progressive training when the user is: novice, returning from ≥ 3 months layoff, or above ≈ 20 % (men) / 30 % (women) body fat with a hypertrophy goal. Expect slower scale change; use strength trend and waist as progress metrics. **moderate** (Barakat 2020, Strength Cond J, https://journals.lww.com/nsca-scj/Fulltext/2020/10000/Body_Recomposition__Can_Trained_Individuals_Build.3.aspx; Jäger 2017 > 3 g/kg for fat loss in trained individuals).
- **NUT-08 Carbohydrate.** ≥ 3 g/kg/day minimum during hypertrophy or strength blocks; 3–5 g/kg for ~1 h/day training; 5–7 g/kg for 1–2 h/day or when cardio ≥ 4 sessions/week; 6–10 g/kg only for endurance-support users. In a deficit, cut fat to the NUT-09 floor before cutting carbs below 3 g/kg. **moderate** (Thomas 2016 AND/DC/ACSM position: 3–5 g/kg light up to 8–12 g/kg very high, https://www.jandonline.org/article/S2212-2672(15)01802-X/abstract — only the low and high ends verified live; Iraki 2019 ≥ 3–5 g/kg).
- **NUT-09 Fat.** 20–35 % of energy, or 0.5–1.5 g/kg/day; floor 0.5 g/kg/day (hormonal/absorption reasons). **moderate** (Iraki 2019 0.5–1.5 g/kg; AMDR 20–35 % from IOM 2005 — not fetched live; see Section 12).
- **NUT-10 Fibre.** 14 g per 1,000 kcal; adequate intake 38 g/day men, 25 g/day women (19–50 y). Increase gradually (+5 g/week) to avoid GI issues, and reduce to ~20 g on the day before very heavy leg sessions if the user reports bloating. **strong** for targets (IOM DRI summary table, https://www.ncbi.nlm.nih.gov/books/NBK56068/table/summarytables.t4/); **weak** for the pre-session tweak.
- **NUT-11 Hydration.** Total water adequate intake 3.7 L/day men, 2.7 L/day women (all beverages + food water) (IOM DRI table, verified); training rule: avoid > 2 % body-mass loss in a session; estimate sweat rate = (pre − post weight in kg + fluid drunk in L) ÷ hours; drink to match for sessions > 60 min; add sodium when > 1–2 h, hot, or salty sweater. Urine colour pale-yellow is an acceptable field check. **strong** for the 2 % threshold and individualized plans (Sawka 2007 ACSM position stand, https://pubmed.ncbi.nlm.nih.gov/17277604/); **moderate** for the rest.
- **NUT-12 Peri-workout.** Total daily protein dominates; pre- vs post-workout protein (25 g) produced identical adaptations. Rule: a protein-containing meal (0.25–0.4 g/kg) within ~2 h before or ~2 h after the session; if training fasted (> 3–4 h since protein), eat 20–40 g protein + carbohydrate within 1–2 h after. Fast carb replenishment (1–1.2 g/kg/h) only when two hard sessions fall within 8 h. **moderate** (Schoenfeld 2017 PeerJ pre vs post, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Pre-%20versus%20post-exercise%20protein%20intake%20has%20similar%20effects%20on%20muscular%20adaptations%22&resultType=core&format=json; Kerksick 2017 ISSN nutrient timing, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5596471/; Aragon & Schoenfeld 2013).
- **NUT-13 Alcohol.** 1.5 g/kg (≈ 12 standard drinks) after training cut myofibrillar protein synthesis by 24 % (with protein) to 37 % (with carbs). Rules: ≤ 1–2 standard drinks on a training day, none within ~4 h post-session, ≤ 7/week; ≥ 3 drinks in an evening → next-day readiness Amber (RDY-02) and sleep flag. **moderate** for the mechanism (Parr 2014, PLoS One, https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0088384); **weak** for the numeric limits (convention).
- **NUT-14 Travel-eating heuristics (service technician on the road).** (1) Protein anchor first: ≥ 30–40 g per meal from eggs/quark/skyr/yogurt at hotel breakfast, grilled meat/fish/tofu at lunch and dinner; carry whey (25–30 g per scoop) for days with < 3 protein meals. (2) Plate rule at restaurants/buffets: ½ vegetables, ¼ protein, ¼ starch; sauces on the side. (3) Petrol-station kit: skyr, jerky, nuts (30 g portion), fruit, protein bar ≥ 20 g protein. (4) Expect +0.5–1.5 kg scale weight after high-sodium restaurant days and after weekends (0.35 % within-week swing, highest Monday, lowest Friday) — do not adjust calories on those days (NUT-15). (5) Track "protein hit? yes/no" and "meals: light/normal/heavy" when calorie logging is impossible; the planner uses these as ordinal inputs. (6) 500 mL water per 2 h driving; coffee counted toward SUP-02 cut-off. (7) Alcohol per NUT-13. **weak** for the heuristics (convention); **moderate** for the weekend/sodium weight pattern (Turicchi 2020, PLoS One, https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0232152).
- **NUT-15 Weight-trend smoothing and expected noise.** Weigh every morning after voiding, before eating, same scale; compute a 7-day simple moving average (or exponentially weighted, α = 0.1); compare week-over-week averages, never single days. Expected noise: day-to-day typical error ≈ 0.4–0.5 kg (Monday–Friday 0.6 kg) in young men; within-week cyclic swing 0.35 % of body weight; holiday periods +1.35 %. A single-day change ≥ 1 % is treated as noise. Require ≥ 14 days of data before any calorie adjustment (NUT-16). **moderate** (Kutáč 2015, J Physiol Anthropol, https://pmc.ncbi.nlm.nih.gov/articles/PMC4580369/; Turicchi 2020).
- **NUT-16 Rate-based adjustment.** Every 14 days compare the 14-day change in the 7-day average with the goal band (NUT-05/06). Outside the band → adjust daily intake by ±100–200 kcal (never > 200 per step), re-evaluate in 14 days; show the trigger values. **moderate** (derived from NUT-04 adaptive method).
- **NUT-17 Diet breaks / refeeds.** In deficits lasting > 8 weeks, or when hunger ≥ 4/5 (CHK Q15) for ≥ 2 weeks, insert 1 week at estimated maintenance every 6–8 weeks; refeed days (carbs to maintenance, 1–2 days/week) are optional and psychological. **weak** (Helms 2014 discussion; RCT evidence on intermittent diet breaks is mixed and not fetched here).
- **NUT-18 Weight-class or event cuts.** Out of scope for the planner; refer to a sports dietitian (POL-02).

---

## 7. Supplements (SUP)

Tiers follow the ISSN "strong / limited-mixed / little-to-no evidence" framework (Kerksick 2018, JISSN review update, https://link.springer.com/article/10.1186/s12970-018-0242-y; the framework was verified live, the full category table was not — Section 12) and the IOC consensus that only "a few (including caffeine, creatine, specific buffering agents and nitrate) have good evidence of benefits" (Maughan 2018, BJSM, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22IOC%20consensus%20statement%3A%20dietary%20supplements%20and%20the%20high-performance%20athlete%22&resultType=core&format=json).

### Tier A — recommend by default (with SUP-12 caveats)

- **SUP-01 Creatine monohydrate.** 3–5 g/day (≈ 0.1 g/kg/day) every day, with any meal; optional loading 20 g/day (4 × 5 g) for 5–7 days then 3–5 g/day, otherwise saturation takes ~3–4 weeks; expect +1–2 kg water weight in the first weeks (flag in NUT-15). Safe short- and long-term in healthy people (studies up to 30 g/day for 5 years); no kidney harm in healthy adults; relatively well tolerated at recommended doses; benefits also for women and older adults. Do not recommend if kidney disease or a single kidney → refer. **strong** (Kreider 2017 ISSN position stand, https://pubmed.ncbi.nlm.nih.gov/28615996/; Antonio 2021 JISSN, https://pubmed.ncbi.nlm.nih.gov/33557850/ — DOI 10.1186/s12970-021-00412-w).
- **SUP-02 Caffeine.** 3–6 mg/kg (start 2–3 mg/kg; ≈ 150–300 mg for 80 kg) 60 min before the session; 9 mg/kg gives side effects without extra benefit; response varies with genetics and habitual intake. Sleep rule: no caffeine within 8.8 h of bedtime for a ≈ 100 mg dose (one coffee) and within 13.2 h for a ≈ 200 mg pre-workout dose; caffeine reduced total sleep time by 45 min and efficiency by 7 % in the pooled data. Planner: cut-off = bedtime − 9 h for ≤ 100 mg, bedtime − 13 h for ≥ 200 mg; keep daily total ≤ 400 mg (EFSA guidance value, not fetched live). Users with hypertension/arrhythmia/anxiety/pregnancy → SUP-12. **strong** (Guest 2021 ISSN position stand, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:10.1186/s12970-020-00383-4&resultType=core&format=json; Gardiner 2023 Sleep Med Rev meta, https://pubmed.ncbi.nlm.nih.gov/36870101/).
- **SUP-03 Protein powder (whey/casein/plant blends).** A food, not an ergogenic: use 20–40 g servings to close the gap to NUT-01 when whole food is impractical (travel days). Plant proteins: use blends or a larger serving to reach ≈ 2.5–3 g leucine. **strong** for protein per se (Morton 2018; Jäger 2017 leucine 700–3,000 mg per dose).
- **SUP-04 Vitamin D (conditional).** Only when 25(OH)D is measured low or risk is high: deficiency < 20 ng/mL (50 nmol/L), insufficiency 21–29 ng/mL (Endocrine Society 2011 cut-offs); typical repletion 1,000–2,000 IU/day, upper limit 4,000 IU/day without supervision; toxicity risk at serum > 180 nmol/L. The 2024 Endocrine Society guideline recommends against routine 25(OH)D testing and against empiric supplementation above the DRI (600–800 IU) in healthy adults < 75. Planner: suggest testing only if ≥ 2 risk factors (little sun/indoor work, latitude > 40° in winter, darker skin, covering clothing, BMI > 30); otherwise diet/sun. **contested** (Holick 2011 J Clin Endocrinol Metab guideline, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Evaluation%2C%20treatment%2C%20and%20prevention%20of%20vitamin%20D%20deficiency%3A%20an%20Endocrine%20Society%20clinical%20practice%20guideline%22&resultType=core&format=json — thresholds via secondary; Demay 2024 Endocrine Society guideline, https://pubmed.ncbi.nlm.nih.gov/38828931/; Owens 2018 Sports Med, https://link.springer.com/article/10.1007/s40279-017-0841-9).
- **SUP-09 Electrolytes (conditional).** Only for sessions > 1–2 h, heat, or measured sweat loss > 1 L/h: sodium 300–600 mg per hour of exercise in fluid; otherwise normal salted food suffices. **moderate** (Sawka 2007; Thomas 2016).

### Tier B — optional, situational

- **SUP-06 Beta-alanine.** 4–6 g/day for ≥ 2–4 weeks in split doses of ≤ 1.6 g to limit paresthesia; benefits efforts of 1–4 min (rowing/HIIT intervals, high-rep sets); minimal relevance to heavy lifting. **strong** for the effect (Trexler 2015 ISSN position stand, https://pubmed.ncbi.nlm.nih.gov/26175657/); **weak** relevance for this user.
- **SUP-07 Citrulline malate.** 6–8 g (2:1 citrulline:malate) 60 min before training; small increase in repetitions to failure (ES ≈ 0.20). **moderate** for a small effect (Trexler 2019 meta summarized at https://www.strongerbyscience.com/research-spotlight-citrulline-malate/; Vårvik 2021 IJSNEM meta, https://journals.humankinetics.com/view/journals/ijsnem/31/4/article-p350.xml).
- **SUP-08 Melatonin (travel/jet lag).** 0.5–5 mg; dose–response peaks ≈ 4 mg; timing 30 min to 3 h before desired bedtime (earlier timing more effective); reduces sleep-onset latency by ≈ 7 min; short-term use for time-zone shifts/hotel nights only. Legal status varies (prescription-only in several EU countries at ≥ 2 mg; e.g., Germany) — app must show the SUP-12 legal caveat. **moderate** (Ferracioli-Oda 2013 PLoS One meta, https://pubmed.ncbi.nlm.nih.gov/23691095/; Cruz-Sanabria 2024 J Pineal Res dose–response meta, https://onlinelibrary.wiley.com/doi/10.1111/jpi.12985).
- **SUP-05 Omega-3 (EPA+DHA).** No support for lean mass, strength or function in healthy adults (overview of 33 reviews); general-health rationale only when fish intake < 2 servings/week (1–2 g/day EPA+DHA). **weak** for training outcomes (Nunes 2025 Clin Nutr ESPEN overview, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Lack%20of%20evidence%20for%20Omega-3%20fatty%20acid%20supplementation%20in%20enhancing%20lean%20mass%22&resultType=core&format=json).
- **SUP-10 Multivitamin.** No performance effect; a low-dose daily multivitamin is reasonable only during energy restriction or restricted diets (vegan, travel with poor food access). **moderate** (Kerksick 2018 review: low-dose multivitamin during heavy training with inadequate intake).

### Tier C — do-not-recommend list (SUP-11)

| Item | Reason | Evidence |
|---|---|---|
| BCAA (alone) | Cannot stimulate MPS without the other EAAs; redundant when protein target is met | **strong** (Wolfe 2017 JISSN, https://pubmed.ncbi.nlm.nih.gov/28852372/) |
| HMB (trained adults) | No meaningful effect on lean mass, fat mass or strength in trained young adults | **moderate** (Jakubowski 2020 SR/MA, https://www.researchgate.net/publication/341619379) |
| Glutamine | No hypertrophy/strength effect in healthy people | **moderate** (Kerksick 2018 "little to no evidence" category — table not verified live) |
| "Testosterone boosters" (tribulus, fenugreek blends, D-aspartic acid) | Ingredients mostly unstudied or ineffective; some reduce testosterone; doses above tolerable limits | **strong** (Clemesha 2020 World J Mens Health, https://wjmh.org/DOIx.php?id=10.5534%2Fwjmh.190043) |
| Stimulant "fat burners", DMAA/DMHA, ephedra, high-dose yohimbine | Cardiovascular risk, contamination, banned substances | **strong** (Maughan 2018 IOC; regulatory actions — not fetched) |
| SARMs, prohormones, "legal steroids" | Unregulated, hepatotoxic/endocrine harm, doping-listed | **strong** (Maughan 2018) |
| Arginine, ornithine, CLA, D-ribose, chromium, "detox"/"cleanse", collagen for muscle, raspberry ketones, "test/estrogen blockers" | Little-to-no evidence | **moderate** (Kerksick 2018 framework) |
| High-dose vitamin D without a blood test | No benefit if replete; toxicity risk | **moderate** (Owens 2018; Demay 2024) |

- **SUP-12 Legal and medical caveats (always displayed with any supplement mention).** (1) Not medical advice; check with a physician/pharmacist if pregnant, breastfeeding, under 18, or taking medication or having heart, kidney, liver, thyroid or psychiatric conditions. (2) Prefer third-party-tested products (Informed Sport, NSF Certified for Sport, Kölner Liste); contamination with prohibited substances is a known risk. (3) Legal status differs by country (melatonin, high-dose caffeine products, some stimulants); the app shows a country note when a locale is set. (4) Supplements are optional; the ranked order of impact is: total protein, calories, sleep, training consistency, then creatine and caffeine. **strong** as policy (Maughan 2018).

---

## 8. Sleep, steps/NEAT, stress (SLP, STP, STR)

- **SLP-01 Target.** 7–9 h per night for adults 18–64 (7–8 h for ≥ 65); ≥ 7 h is the health floor. **strong** (Hirshkowitz 2015 Sleep Health, https://www.sleephealthjournal.org/article/s2352-7218(15)00015-7/fulltext; Watson 2015 AASM/SRS consensus, https://aasm.org/resources/pdf/pressroom/adult-sleep-duration-methods.pdf).
- **SLP-02 Effects to encode.** ≤ 6 h sleep → mean −7.6 % exercise performance, morning sessions largely unaffected, afternoon/evening consistently impaired; one night of total sleep loss → −18 % post-meal muscle protein synthesis, cortisol +21 %, testosterone −24 %; consecutive nights of restriction reduce multi-joint (not single-joint) force; adolescents sleeping < 8 h had 1.7× injury odds. **moderate** (Craven 2022; Lamon 2021 Physiol Rep, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7785053/; Knowles 2018; Milewski 2014 J Pediatr Orthop — adolescents, transfer to adults **weak**).
- **SLP-03 Planner modifiers.** 7-day average < 7 h → weekly sets −10 to −20 % and RIR +1 on heavy compounds until average ≥ 7 h; last night < 6 h → RDY-02 Amber minimum and prefer morning training; two consecutive nights < 6 h → no `max strength` zone work (LOAD-04). Sleep hygiene items the coach may raise: consistent wake time, caffeine cut-off (SUP-02), alcohol (NUT-13), dark/cool room, ≤ 20-min nap before 15:00 on travel days. **moderate** for direction; **weak** for the exact percentages.
- **STP-01 Daily steps.** Health inflection ≈ 5,000–7,000 steps/day; 7,000/day vs 2,000 → all-cause mortality HR 0.53; mortality benefit plateaus at 8,000–10,000 (< 60 y) and 6,000–8,000 (≥ 60 y). Planner default target: 7,000–8,000 (< 60 y), 6,000–7,000 (≥ 60 y). **strong** (Ding 2025 Lancet Public Health dose–response meta, https://www.thelancet.com/journals/lanpub/article/PIIS2468-2667(25)00164-1/fulltext; Paluch 2022 Lancet Public Health, https://www.thelancet.com/journals/lanpub/article/PIIS2468-2667(21)00302-9/fulltext).
- **STP-02 Fat-loss NEAT rule.** In `fat_loss`, target = max(STP-01 target, user's 2-week baseline + 2,000) up to 12,000; steps count toward CAR-07 minutes only at brisk pace (talk-test moderate). **weak** (convention; NEAT contribution to energy expenditure is well established but a step-target RCT for fat loss was not fetched).
- **STR-01 Stress effects.** High chronic stress delays recovery of strength, energy and soreness over 96 h and roughly halves 12-week strength gains → apply RDY-05. **moderate** (Stults-Kolehmainen 2014; Bartholomew 2008).
- **STR-02 Stress-relief prescriptions the planner may offer.** Zone-2 walks 20–30 min (CAR-02), keep training but at Amber modifiers, avoid adding HIIT during high-stress weeks. **weak** (convention).

---

## 9. Age and special cases

### 9.1 Masters (AGE)

- **AGE-01 Age 40–59.** Same volume/intensity rules as younger adults; add WU-06 warm-up extension; minimum 48 h between heavy sessions for the same muscle (SPLIT-02); deload cadence at the shorter end of DLD-01 when volume is at the VOL-04 upper bound; protein at the upper half of NUT-01. **weak** (aging effects on recovery are plausible but confounded by activity level, Fell & Williams 2008); ISSN/ACSM contain no separate 40–59 prescriptions.
- **AGE-02 Age ≥ 60 (healthy).** 2–3 sessions/week; 2–3 sets per exercise; 7–9 reps at 70–79 % 1RM produce the largest strength effects; 60 s rest between sets was associated with the largest effects in that meta (use 60–120 s); start beginners with single sets; 8–10 exercises covering major muscle groups; include power-style sets (≈ 40–60 % 1RM, 6–10 reps, fast concentric) 1–2×/week; balance/neuromotor work 2–3 days/week; protein ≥ 1.2 g/kg/day (≥ 1.6 when training for hypertrophy); expect faster detraining (> 65 y lose more with cessation) → avoid gaps > 2 weeks (LAY-01). **moderate** (Borde 2015 Sports Med dose–response meta, https://pmc.ncbi.nlm.nih.gov/articles/PMC4656698/; Fragala 2019 NSCA position statement, J Strength Cond Res 33(8):2019–2052 — frequency/sets/exercise count verified via secondary summaries, %1RM and power-load numbers not verified live, see Section 12; Garber 2011 neuromotor 2–3 d/wk; Bauer 2013; Bosquet 2013).
- **AGE-03 Age ≥ 60 warm-up and joints.** WU-06 applies; prefer machines/dumbbells for pressing if shoulder ROM limited (INJ-02); avoid `max strength` zone (≤ 3 RM) unless experienced. **weak** (convention; Fragala 2019 emphasizes instruction and technique).

### 9.2 Return from layoff (LAY)

- **LAY-01 Load regression after a break (no training of that pattern).**

| Break length | Resume loads at | Sets | RIR week 1 | Rebuild rate |
|---|---|---|---|---|
| 5–13 days | 95 % of last loads | −1 set/exercise | target + 1 | normal next week |
| 2–4 weeks | 85–90 % | −30 % | target + 2 | +5 % load per session on compounds until previous loads |
| 4–8 weeks | 75–80 % | −50 % | target + 2 | +5 % per session; expect previous loads in 3–5 weeks |
| > 8 weeks | 60–70 %, or PROG-05 novice start | VOL-04 lower bound | 3–4 | strength/size regained in ≈ 5 weeks of retraining (muscle memory) |
| Age ≥ 65 | one row more conservative than the table | | | |

**moderate** for the underlying pattern (Halonen 2024 Scand J Med Sci Sports: 10-week break, losses regained within 5 weeks of retraining, final outcomes equal to continuous training, https://onlinelibrary.wiley.com/doi/full/10.1111/sms.14739; Ogasawara 2013 Eur J Appl Physiol: 3-week breaks every 6 weeks ended equal to continuous training at 24 weeks, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Comparison%20of%20muscle%20hypertrophy%20following%206-month%20of%20continuous%20and%20periodic%20strength%20training%22&resultType=core&format=json; Bosquet 2013 Scand J Med Sci Sports meta: losses scale with duration, larger in > 65 y and inactive people, https://onlinelibrary.wiley.com/doi/abs/10.1111/sms.12047); **weak** for the exact percentages (convention).
- **LAY-02 After illness.** Fever or systemic symptoms → no training until 24 h symptom-free without medication; then use the 2–4-week row of LAY-01 for the first week regardless of the actual break length; cardio at zone 2 only for 3 days. **weak** (convention; RED-01).
- **LAY-03 Reassurance text.** The coach should state that occasional short breaks (≤ 2 weeks) do not compromise long-term results. **moderate** (Halonen 2024 conclusion).

### 9.3 Travel / hotel gym (TRV)

- **TRV-01 Minimum-effective travel week.** 2 full-body sessions of 25–40 min, 4–6 exercises covering MIX-02 patterns as far as equipment allows, 2–3 working sets each, 6–15 reps (up to 15–30 reps to 0–1 RIR when only light dumbbells/bodyweight exist), 0–2 RIR, rest 60–90 s or antagonist supersets. This meets VOL-01 (≥ 4 sets/muscle/week) and the strength floor (VOL-02). **moderate** (Iversen 2021; Androulakis-Korakakis 2020; LOAD-01 for light loads to failure).
- **TRV-02 Equipment templates.**

| Available | Session template (A/B alternate) |
|---|---|
| Dumbbells only (≤ 25 kg) | A: goblet/DB front squat 3×10–15, DB RDL 3×10–12, DB floor/bench press 3×8–12, 1-arm row 3×10–12 per side, lateral raise 2×15, push-up AMRAP; B: Bulgarian split squat 3×8–12 per leg, DB hip hinge 3×12, DB shoulder press 3×8–12, chest-supported/2-arm row 3×10–12, curl/extension superset 2×12 |
| Bodyweight + band | A: pistol-box/skater squat 3×8–12 per leg, band RDL/hip hinge 3×15, push-up variant 3×AMRAP (feet elevated if > 20), band/inverted row 3×AMRAP, band lateral raise 2×20; B: split squat 3×12 per leg, glute bridge march 3×15, pike/decline push-up 3×AMRAP, band pull-apart 3×20, hollow hold 3×30 s |
| Hotel gym (DBs + cables + 1–2 machines) | apply the standard full-body template with PROG-02 `dumbbell`/`machine` increments; if the machine set is unfamiliar, use rep-based progression only |

**weak** (convention consistent with TRV-01).
- **TRV-03 Maintenance volume.** With loads/effort maintained, roughly one-third of accustomed weekly volume maintains strength and size for several weeks in younger adults. **weak** — commonly cited from Bickel 2011 (Med Sci Sports Exerc), not fetched live (Section 12).
- **TRV-04 Gym presets.** Each equipment set (home, hotel, client-site gym) maps to exercise substitutions at the pattern level (MIX-02) with the same progression state carried per exercise, not per pattern; unfamiliar machines start at a RIR-based load find (3 sets, PROG-04). **weak** (project convention).

### 9.4 Time-constrained sessions (TIME)

- **TIME-01 Trim order when session minutes shrink (apply in order until SESS-01 budget fits).** (1) WU-05 warm-up trim; (2) drop isolation sets for muscles already at VOL-01 floor from compounds; (3) convert non-competing exercises into antagonist supersets (REST-03); (4) cut rest on `machine`/`isolation` to 60 s; (5) drop to one exercise per pattern; (6) cut sets per exercise to 2, never below 1 working set on the two priority lifts; (7) if still over budget, move the lowest-priority pattern to another day. Keep the weekly total ≥ VOL-01 for every muscle. **moderate** (Iversen 2021: supersets/drop sets/rest-pause halve time at equal volume; exercise-specific warm-up only; bilateral multi-joint priority).
- **TIME-02 Budget presets.** 30 min: 2 compounds × 3 sets + 1 superset pair × 2 (≈ 10 sets); 45 min: 3 compounds × 3 + 2 accessories × 2–3 (≈ 14–16); 60 min: 4 compounds × 3 + 2–3 accessories × 3 (≈ 20–22); 90 min: full split session up to SESS-02 cap. **weak** (derived from SESS-01).
- **TIME-03 Advanced techniques allowed for time saving.** Drop sets and rest-pause on `machine`/`isolation`/`cable` only; not on barbell compounds. **moderate** (Iversen 2021: better for hypertrophy than strength; safety convention).

### 9.5 Injury-limited substitutions (INJ) — pattern level, no diagnosis

- **INJ-00 Universal rules.** Pain ≤ 3/10 during a set and not worse the next morning is acceptable; otherwise regress (range, load, or pattern). Persisting > 7–10 days → RED-02. Never prescribe "push through". **weak** (clinical convention).
- **INJ-01 Knee (anterior knee pain / meniscal-type symptoms reported by user).** Patellofemoral and tibiofemoral forces rise progressively with knee flexion and peak near full flexion; 0–50° flexion is the low-force range. Substitutions: box or partial-depth squat to pain-free depth; hip-dominant patterns (RDL, hip thrust, back extension); leg press with limited depth; step-ups; reverse lunges over forward lunges; leg curl; terminal-range leg extensions if tolerated; isometric wall sits/Spanish squats 3 × 30–45 s; cycling instead of running/jump rope; avoid deep squat, plyometrics, jump-rope HIIT. **moderate** for the biomechanics (Escamilla 2001, Med Sci Sports Exerc, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Knee%20biomechanics%20of%20the%20dynamic%20squat%20exercise%22&resultType=core&format=json); **weak** for the substitution list.
- **INJ-02 Shoulder (impingement-type/anterior pain).** Up to 36 % of RT injuries occur at the shoulder; risk trends: muscle/joint imbalance and poor technique. Substitutions: neutral-grip dumbbell or landmine press instead of barbell bench/overhead press; floor press or limited-ROM (pin/board) press; avoid behind-the-neck presses/pulldowns, deep dips, very wide-grip bench, upright rows; add external rotation 2 × 15, face pulls 3 × 15, scapular control work; keep pressing within a pain-free arc; pulling patterns usually tolerated (rows, neutral-grip pulldown). **moderate** for epidemiology (Kolber 2010); **weak** for substitutions.
- **INJ-03 Low back (non-specific, no red flags).** Keep training: resistance and stabilization/motor-control exercise rank highest for function, resistance and aerobic exercise for mental health; stretching alone and McKenzie showed no advantage over control. Substitutions: hip hinge with neutral spine and lighter load (trap-bar or block-pull deadlift), hip thrust, belt squat/leg press, split squats, chest-supported rows and machine pulls instead of bent-over rows, avoid loaded spinal flexion and heavy axial loading during flare-ups; core: bird-dog, side plank, dead bug 2–3 × 30–45 s; walking (STP-01) as daily baseline. **moderate** (Owen 2020 BJSM network meta, https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Which%20specific%20modes%20of%20exercise%20training%20are%20most%20effective%20for%20treating%20low%20back%20pain%22&resultType=core&format=json); **weak** for the specific list.
- **INJ-04 Elbow/wrist (tendinopathy-type).** Reduce curl/pressdown volume 50 %, use neutral grips and thicker handles, slow eccentrics 3 s, keep loads at ≤ 3/10 pain; heavy slow resistance is a recognized tendinopathy approach. **weak** (convention; tendinopathy loading literature not fetched).

---

## 10. Planner rule table (all numeric rules by ID)

| ID | Statement (numeric/decision) | Confidence | Key source |
|---|---|---|---|
| POL-01 | Logged numbers are never altered; derived values show formula ID; auto-adjustments show rule + trigger | policy | REQUIREMENTS.md |
| POL-02 | No diagnosis; RED rules hand off to professionals | policy | Riebe 2015 |
| DEF-01 | novice < 6 mo (or ≥ 12 mo layoff); intermediate 6–24 mo; advanced > 24 mo | moderate | ACSM 2009 |
| DEF-02 | RPE 10/9/8/7/6 = 0/1/2/3/4 RIR | strong | Zourdos 2016 |
| DEF-03 | heavy ≤ 8 RM (≥ 80 %); moderate 9–15 RM; light 16–30 RM | moderate | Lopez 2021 |
| DEF-04 | Working set = ≤ 4 RIR; warm-ups excluded | moderate | Robinson 2024 |
| DEF-05 | Prime mover 1.0 set credit, synergist 0.5 | moderate | Pelland 2025 |
| VOL-01 | ≥ 4 working sets/muscle/week minimum (6–15 RM) | strong | Iversen 2021 |
| VOL-02 | Strength MED: 1 set × 6–12 @ 70–85 % 1RM, 2–3×/wk, near failure | moderate | Androulakis-Korakakis 2020 |
| VOL-03 | More sets → more growth/strength, diminishing returns (stronger for strength) | strong | Pelland 2025; Schoenfeld 2017 |
| VOL-04 | Hypertrophy sets/muscle/week: novice 8–12, intermediate 12–18, advanced 15–25 | moderate/weak | Schoenfeld 2017; Pelland 2025 |
| VOL-05 | Strength: 4–10 sets/lift/week over ≥ 2 sessions | moderate | Pelland 2025 |
| VOL-06 | 6–10 sets/muscle/session default; hard cap 12 → add a session | weak | secondary summaries of Pelland/Remmert |
| VOL-07 | Even distribution; ≥ 2 sets per muscle per appearance | weak | convention |
| VOL-08 | +1 (max +2) set/muscle/week when RIR at target and soreness < 3/5; stop at cap; then deload | weak | Enes 2024 |
| VOL-09 | Deficit: keep 60–100 % of accustomed sets, never < VOL-01 | weak | convention |
| FRQ-01 | Each muscle ≥ 2×/week when ≥ 10 sets/week; frequency neutral when volume equated | strong | Schoenfeld 2016/2019; Pelland 2025 |
| FRQ-02 | Main lifts ≥ 2×/week for strength | moderate | Pelland 2025; Grgic 2018 |
| FRQ-03 | Split valid only if ≥ 2×/muscle, per-session sets ≤ SESS-01, per-muscle ≤ VOL-06 | moderate | derived |
| LOAD-01 | Hypertrophy equal across ~30–85 % 1RM (5–30 reps) near failure | strong | Schoenfeld 2017; Lopez 2021; Currier 2023 |
| LOAD-02 | Max strength: ≥ 80 % 1RM | strong | Currier 2023 |
| LOAD-03 | Rep-range table by goal × class (barbell 3–10, machine/DB 6–15, isolation 10–20, BW 5–20) | moderate/weak | derived; Halperin 2022 |
| LOAD-04 | Zones: 85–95 % ×1–5; 78–85 % ×5–8; 65–78 % ×8–15; 50–65 % ×15–30; power 40–60 % fast | moderate | Lopez 2021; ACSM 2009 |
| RIR-01 | Hypertrophy 0–3 RIR; benefit tapers past 2 RIR; failure ES ≈ 0.19 | strong | Robinson 2024; Refalo 2023/2024 |
| RIR-02 | Strength 2–5 RIR sufficient | strong | Robinson 2024 |
| RIR-03 | Class RIR: lower_barbell 2–3, upper_barbell 1–3, DB/machine/cable 1–2, isolation 0–2, BW 0–2 | moderate | derived |
| RIR-04 | Mesocycle: wk1 target+1, mid target, last target−1, deload 4–5 | weak | convention |
| RIR-05 | Under-prediction ≈ 1 rep; > 12-rep sets treat reported RIR as +1; AMRAP calibration every 2–3 wk | moderate | Halperin 2022 |
| REST-01 | Hypertrophy ≥ 60–90 s min, default 90–120 s; 3 min > 1 min in trained | moderate | Singer 2024; Schoenfeld 2016 |
| REST-02 | Heavy compounds 2–5 min (3–5 for ≤ 6 RM) | strong | ACSM 2009; Grgic 2018 |
| REST-03 | Isolation/machines 60–90 s; antagonist supersets allowed | moderate | Iversen 2021 |
| REST-04 | Trim rest on isolation before sets; main-lift rest never < 2 min | weak | derived |
| ORD-01 | Priority exercise first; largest strength gains in first exercise | strong | Nunes 2021 |
| ORD-02 | Order irrelevant for hypertrophy (ES 0.03); compounds first by default | strong | Nunes 2021 |
| ORD-03 | Same-session cardio after lifting (+6.9 % dynamic strength) | moderate | Eddens 2018 |
| MIX-01 | Isolation adds nothing for muscles compounds already train | strong | Gentil 2015; Rosa 2022 |
| MIX-02 | Weekly: knee-dominant, hinge, horizontal push, vertical/incline push, horizontal pull, vertical pull | moderate | Iversen 2021 |
| MIX-03 | 60–75 % of sets from compounds | weak | convention |
| UNI-01 | Unilateral = bilateral hypertrophy; strength mode-specific (ES 0.56 / −0.65) | strong | Kassiano 2025; Liao 2022 |
| UNI-02 | Unilateral set = 1 set; time ×1.7 | weak | convention |
| TEMPO-01 | 0.5–8 s reps equal; > 10 s inferior; cue 1–3 s eccentric | strong | Schoenfeld 2015; Wilk 2021 |
| TEMPO-02 | Tempo is not a progression variable | moderate | same |
| ROM-01 | Full ROM default (strength ES 0.56; lower-limb hypertrophy ES 0.88) | strong | Pallarés 2021 |
| ROM-02 | Lengthened partials ≥ full for hypertrophy; shortened partials inferior | moderate | Wolf 2023; Kassiano 2023; Pedrosa 2022 |
| ROM-03 | Use lengthened partials for pain/equipment/intensity; never shortened | moderate | derived |
| SESS-01 | Min per set: heavy lower 4.0, heavy upper 3.5, moderate compound 2.5, isolation 1.75; 30/45/60/90 min ≈ 8–10/14–18/20–26/30–36 sets | weak | derived |
| SESS-02 | Session cap 25 sets (novice 20, advanced 32) | weak | convention |
| SPLIT-01 | 2 d FB×2; 3 d FB×3; 4 d U/L×2; 5 d U/L/P/P/L; 6 d PPL×2 or U/L×3 | moderate/weak | FRQ-01; VOL-06 |
| SPLIT-02 | ≥ 48 h between heavy sessions of the same muscle | weak | convention |
| PROG-01 | Double progression: all sets at `hi` reps and ≤ target RIR → +increment, reps → `lo` | strong | ACSM 2009 |
| PROG-02 | Increments: lower BB +2.5–5 kg; upper BB +1.25–2.5 kg; DB next pair ≤ 10 %; machine/cable +1 plate 5–10 % (if > 10 %, extend reps first); BW +reps then +2.5–5 kg | moderate | ACSM 2009 (2–10 %) |
| PROG-03 | Cadence: novice per session (linear), intermediate weekly, advanced per mesocycle undulating | moderate | ACSM 2009 |
| PROG-04 | RIR ≥ target+2 all sets → increment next session; first-set RIR ≤ target−2 → −5 % for remaining sets | moderate | Graham & Cleather 2021; Helms 2018 |
| PROG-05 | Novice first 2 weeks at 3–4 RIR | weak | convention |
| PLAT-01 | Plateau = no e1RM/reps gain in ≥ 3 sessions with readiness ≥ Amber | weak | convention |
| PLAT-02 | Ladder: audit sleep/nutrition → shift rep zone → variation swap → +1–2 sets → deload | weak | convention |
| PLAT-03 | e1RM −5 % on 2 sessions without cause → reactive deload | weak | Bell 2025 |
| DLD-01 | Deload every 4–8 wk (practice 5.6 ± 2.3 wk, 6.4 ± 1.7 d); novice reactive only; intermediate 6–8; advanced 4–6 | moderate | Bell 2025; Rogerson 2024; Coleman 2024 |
| DLD-02 | Deload week: sets −50 %, load −10–20 % (or hold), RIR 4–5, frequency unchanged | moderate | Rogerson 2024 |
| DLD-03 | Reactive triggers: ≥ 2 of {e1RM −5 %×2, Red ≥ 3/7 d, soreness ≥ 4 > 72 h, sleep ≤ 2 ×4, motivation ≤ 2 ×4, joint ache ≥ 3/10} | weak | Bell 2025 |
| DLD-04 | Re-entry at 95 % loads, lower-bound sets, RIR+1 for 1 week | weak | convention |
| E1RM-01 | Epley w(1+r/30); Brzycki w·36/(37−r); mean of both | strong | LeSuer 1997 |
| E1RM-02 | Valid for r ≤ 10 (best 2–6); > 10 low-confidence only | strong | LeSuer 1997; Mayhew 2008 |
| E1RM-03 | r_eff = r + RIR (cap 10); %1RM 1:100, 2:94, 3:91, 4:88, 5:86, 6:83, 8:79, 10:75, 12:71, 15:67 | moderate | derived |
| E1RM-04 | Deadlift under-estimated; machines: track load×reps; 14-day rolling best | moderate | LeSuer 1997 |
| RDY-01 | Readiness 0–100 from sleep h/quality, soreness, stress, energy, motivation, optional RHR | moderate/weak | Hooper 1995; Buchheit 2014 |
| RDY-02 | Green ≥ 70 as planned; Amber 45–69 +1 RIR, −1 set after first two; Red < 45 −40 % sets, +2 RIR, heavy → moderate variants; sleep < 6 h ≥ Amber | moderate/weak | Craven 2022; Knowles 2018 |
| RDY-03 | RHR ≥ +5 bpm ×2 d → Amber; ≥ +10 or illness → Red/skip | weak | Buchheit 2014 |
| RDY-04 | Soreness ≥ 4/5 → move that muscle 24–48 h | weak | Stults-Kolehmainen 2014 |
| RDY-05 | Stress ≥ 4/5 for 2 weeks → sets −20 %, RIR +1, hold volume progression | moderate | Stults-Kolehmainen 2014; Bartholomew 2008 |
| RDY-06 | sRPE × minutes weekly load; > 150 % of 4-wk mean = spike (advice only) | weak/contested | Foster 2001 |
| CHK-01 | 15-item weekly bank with triggers (Q1–Q15) | weak | derived |
| RED-01 | Stop-and-refer symptom list (chest pain, dyspnea, syncope, neuro deficits, cauda equina signs, hot swollen joint, pop, DVT signs, dark urine, fever) | strong (policy) | Riebe 2015 |
| RED-02 | Pain > 3/10 persisting > 7–10 d or night pain → professional | weak | convention |
| RED-03 | Known disease/pregnancy/meds → clearance before HIIT, max lifts, supplements | strong | Riebe 2015 |
| WU-01 | RAMP 8–12 min: raise 3–5, activate/mobilise 3–5, ramp sets | strong/moderate | Fradkin 2010; Abad 2011; Jeffreys 2007 |
| WU-02 | Ramp: heavy 40 %×8, 55 %×5, 70 %×3, 80–85 %×1–2; moderate 50 %×8, 70 %×4–5; light/machine 60 %×8; isolation 1×8–10 @ 50–60 % | moderate | Abad 2011; Ribeiro 2020 |
| WU-03 | Ramp sets ≠ working volume | strong | volume literature |
| WU-04 | No static stretch ≥ 60 s pre-lift (−4.6 %); ≤ 30 s harmless; dynamic +1.3 % | strong | Behm 2016; Kay & Blazevich 2012 |
| WU-05 | ≤ 45 min sessions: specific ramp sets only (+2–3 min drills) | moderate | Iversen 2021 |
| WU-06 | ≥ 40 y, < 15 °C, or < 60 min after waking: raise 8–10 min + 1 extra ramp set | weak | convention |
| MOB-01 | Pre-session dynamic 2×8–10 per drill, 3–5 drills | moderate | Behm 2016 |
| MOB-02 | Chronic ROM: ≥ 5 min/muscle/week over ≥ 5 d; ceiling 4 min/session, 10 min/week | strong | Thomas 2018; Ingram 2025 |
| MOB-03 | Full-ROM strength work counts as mobility (1 set ≈ 30 s credit) | strong/weak | Afonso 2021 |
| MOB-04 | Static stretching after session or separately | strong | Behm 2016 |
| MOB-05 | Foam rolling optional 30–60 s/muscle | weak | Wiewelhove 2019 |
| MOB-06 | Cool-down optional 5–10 min | strong | Van Hooren 2018 |
| MOB-07 | Joint drill library (hips, T-spine, shoulders, ankles, hamstrings, wrists) | weak | derived |
| CAR-01 | 150–300 min moderate or 75–150 vigorous/week + RT ≥ 2 d | strong | WHO 2020; Garber 2011 |
| CAR-02 | Zone 2 = talk test + 60–70 % HRmax / 40–59 % HRR + RPE 11–13 (2 of 3) | moderate | Garber 2011; Persinger 2004 |
| CAR-03 | HRmax = 208 − 0.7·age; Karvonen HRR | strong | Tanaka 2001 |
| CAR-04 | Vigorous 77–95 % HRmax / 60–89 % HRR / RPE 14–17 | moderate | Garber 2011 |
| CAR-05 | 4×4 @ 90–95 % HRmax/3 min @ 70 %; 10×1 min @ 90 %; SIT 4–6×30 s/4 min; HIIT +1.2 mL/kg/min vs MICT | strong | Helgerud 2007; Little 2010; Gibala 2006; Milanović 2015 |
| CAR-06 | Hypertrophy/strength not compromised; explosive −0.28; trained same-session 1RM −0.66 vs −0.10 separated; running > cycling; separate ≥ 6 h (24 h better); lift first; cap ≤ 3 sessions/≤ 150 min extra for hypertrophy goals | strong/moderate | Schumann 2022; Petré 2021; Wilson 2012; Robineau 2016; Eddens 2018 |
| CAR-07 | fitness: 2×30–45 Z2 + 1 HIIT; hypertrophy: 2×20–30 Z2; fat loss: 150–250 → > 250 min/wk; HIIT not superior for weight | strong | Donnelly 2009; Jakicic 2024 |
| CAR-08 | Modality table with leg-day adjacency | weak | Wilson 2012 |
| CAR-09 | +10 %/week duration; HIIT after 4 weeks base; add intervals before intensity | weak | Garber 2011 |
| NUT-01 | Protein 1.6–2.2 g/kg; deficit 2.3–3.1 g/kg FFM; ≥ 65 y ≥ 1.2; recomp ≥ 2.2 | strong | Morton 2018; Jäger 2017; Helms 2014; Bauer 2013 |
| NUT-02 | 0.4 (0.4–0.55) g/kg/meal × ≥ 4, every 3–4 h; 30–40 g casein pre-sleep optional | moderate | Schoenfeld & Aragon 2018; Jäger 2017; Snijders 2019 |
| NUT-03 | Meal frequency free (3–5) | moderate | Schoenfeld 2015 |
| NUT-04 | Mifflin–St Jeor REE; activity 1.2/1.375/1.55/1.725/1.9; 14-day adaptive TDEE using 7,700 kcal/kg, clamp ±150 | strong/weak/moderate | Mifflin 1990; Frankenfield 2005; Hall 2011 |
| NUT-05 | Deficit 300–500 kcal (10–20 %); 0.5–1.0 % BW/week; lean → 0.5–0.7 % | strong | Helms 2014; Garthe 2011; Aragon 2017; Ashtary-Larky 2020 |
| NUT-06 | Surplus 10–20 % (200–400 kcal); +0.25–0.5 % BW/week (advanced 0.1–0.25) | moderate | Iraki 2019; Slater 2019 |
| NUT-07 | Recomp: maintenance to −200 kcal, protein ≥ 2.2 g/kg for novice/returning/high-BF | moderate | Barakat 2020 |
| NUT-08 | Carbs ≥ 3 g/kg; 3–5 (~1 h/d); 5–7 (1–2 h/d); 6–10 endurance | moderate | Thomas 2016; Iraki 2019 |
| NUT-09 | Fat 20–35 % E or 0.5–1.5 g/kg; floor 0.5 g/kg | moderate | Iraki 2019; IOM AMDR |
| NUT-10 | Fibre 14 g/1,000 kcal; 38 g men / 25 g women | strong | IOM DRI |
| NUT-11 | Water AI 3.7 L men / 2.7 L women; < 2 % BM loss; sweat-rate formula; sodium > 1–2 h | strong/moderate | IOM DRI; Sawka 2007 |
| NUT-12 | Protein meal within ~2 h pre or post; fasted → 20–40 g + carbs within 1–2 h; pre = post | moderate | Schoenfeld 2017; Kerksick 2017 |
| NUT-13 | Alcohol ≤ 1–2 drinks training days, none < 4 h post, ≤ 7/week; 1.5 g/kg cuts MPS 24–37 % | moderate/weak | Parr 2014 |
| NUT-14 | Travel heuristics: protein anchor 30–40 g/meal, plate rule, kit, expect +0.5–1.5 kg after sodium/weekends | weak/moderate | Turicchi 2020 |
| NUT-15 | Daily AM weigh-in; 7-day MA (or EWMA α = 0.1); noise 0.4–0.6 kg TE, 0.35 % weekly cycle; ≥ 1 %/day = noise; ≥ 14 d before adjusting | moderate | Kutáč 2015; Turicchi 2020 |
| NUT-16 | Every 14 d: outside band → ±100–200 kcal | moderate | derived |
| NUT-17 | Deficit > 8 wk or hunger ≥ 4/5 ×2 wk → 1 week at maintenance every 6–8 wk | weak | Helms 2014 |
| SUP-01 | Creatine 3–5 g/d (0.1 g/kg); optional 20 g/d × 5–7 d; safe; refer if kidney disease | strong | Kreider 2017; Antonio 2021 |
| SUP-02 | Caffeine 3–6 mg/kg (start 2–3) 60 min pre; ≤ 9 mg/kg; cut-off bedtime −9 h (≤ 100 mg) / −13 h (≥ 200 mg); ≤ 400 mg/d | strong | Guest 2021; Gardiner 2023 |
| SUP-03 | Protein powder 20–40 g servings as food | strong | Morton 2018 |
| SUP-04 | Vitamin D only if < 20 ng/mL or ≥ 2 risk factors; 1,000–2,000 IU/d; UL 4,000; routine testing/supplementation not recommended < 75 y | contested | Holick 2011; Demay 2024; Owens 2018 |
| SUP-05 | Omega-3: no muscle benefit; 1–2 g EPA+DHA health only if low fish | weak | Nunes 2025 |
| SUP-06 | Beta-alanine 4–6 g/d ≥ 2–4 wk, doses ≤ 1.6 g; 1–4 min efforts | strong (effect)/weak (relevance) | Trexler 2015 |
| SUP-07 | Citrulline malate 6–8 g 60 min pre; ES ≈ 0.20 rep endurance | moderate | Trexler 2019; Vårvik 2021 |
| SUP-08 | Melatonin 0.5–5 mg (peak ≈ 4 mg), 30 min–3 h pre-bed, travel only; legal caveat | moderate | Ferracioli-Oda 2013; Cruz-Sanabria 2024 |
| SUP-09 | Sodium 300–600 mg/h only for > 1–2 h/heat | moderate | Sawka 2007 |
| SUP-10 | Multivitamin low-dose only in restriction | moderate | Kerksick 2018 |
| SUP-11 | Do-not list: BCAA, HMB (trained), glutamine, T-boosters, stimulant fat burners, SARMs/prohormones, misc. | strong–moderate | Wolfe 2017; Jakubowski 2020; Clemesha 2020; Maughan 2018 |
| SUP-12 | Legal/medical caveat text always shown; third-party testing; priority order protein → calories → sleep → consistency → creatine/caffeine | strong (policy) | Maughan 2018 |
| SLP-01 | 7–9 h (≥ 65 y 7–8); floor 7 h | strong | Hirshkowitz 2015; Watson 2015 |
| SLP-02 | ≤ 6 h → −7.6 % performance (PM worst); 1 night loss → MPS −18 %; multi-night → compound strength ↓ | moderate | Craven 2022; Lamon 2021; Knowles 2018 |
| SLP-03 | 7-d avg < 7 h → sets −10–20 %, RIR +1 heavy; < 6 h → Amber, prefer AM; 2 nights < 6 h → no max-strength zone | moderate/weak | derived |
| STP-01 | Steps 7,000–8,000 (< 60 y), 6,000–7,000 (≥ 60); inflection 5–7k; plateau 8–10k / 6–8k | strong | Ding 2025; Paluch 2022 |
| STP-02 | Fat loss: max(target, baseline + 2,000) ≤ 12,000 | weak | convention |
| STR-01 | High stress → RDY-05 | moderate | Stults-Kolehmainen 2014 |
| STR-02 | Stress weeks: Z2 walks, Amber modifiers, no new HIIT | weak | convention |
| AGE-01 | 40–59: standard rules + WU-06, ≥ 48 h same-muscle heavy, shorter deload cadence, upper-half protein | weak | Fell 2008 |
| AGE-02 | ≥ 60: 2–3 d/wk, 2–3 sets, 7–9 reps @ 70–79 %, 60–120 s rest, single-set start, 8–10 exercises, power 40–60 % 1–2×/wk, balance 2–3 d, protein ≥ 1.2 g/kg | moderate | Borde 2015; Fragala 2019; Garber 2011; Bauer 2013 |
| AGE-03 | ≥ 60: WU-06; machines/DB pressing if ROM limited; avoid ≤ 3 RM unless experienced | weak | convention |
| LAY-01 | Break 5–13 d: 95 % loads, −1 set; 2–4 wk: 85–90 %, −30 % sets; 4–8 wk: 75–80 %, −50 %; > 8 wk: 60–70 % or novice start; ≥ 65 y one row more conservative; regain ≈ 5 wk | moderate/weak | Halonen 2024; Ogasawara 2013; Bosquet 2013 |
| LAY-02 | Illness: 24 h symptom-free; then 2–4-wk row; Z2 only 3 d | weak | convention |
| LAY-03 | Short breaks ≤ 2 wk do not compromise long-term results (reassurance) | moderate | Halonen 2024 |
| TRV-01 | Travel: 2 × 25–40 min full body, 4–6 exercises, 2–3 sets, 6–15 (to 30) reps, 0–2 RIR | moderate | Iversen 2021; Androulakis-Korakakis 2020 |
| TRV-02 | DB-only / BW+band / hotel templates | weak | derived |
| TRV-03 | ≈ 1/3 accustomed volume maintains for weeks if effort kept | weak | Bickel 2011 (not fetched) |
| TRV-04 | Gym presets map at pattern level; progression state per exercise | weak | project convention |
| TIME-01 | Trim order: warm-up → isolation → supersets → rest 60 s → one exercise/pattern → 2 sets → move pattern; keep ≥ VOL-01 | moderate | Iversen 2021 |
| TIME-02 | 30/45/60/90 min presets ≈ 10/14–16/20–22/up to cap sets | weak | derived |
| TIME-03 | Drop sets/rest-pause only on machine/isolation/cable | moderate | Iversen 2021 |
| INJ-00 | Pain ≤ 3/10 and not worse next day; > 7–10 d → RED-02 | weak | convention |
| INJ-01 | Knee: 0–50° low-force range; box/partial squats, hinges, limited-depth press, step-ups, isometrics; cycle not run | moderate/weak | Escamilla 2001 |
| INJ-02 | Shoulder: neutral-grip/landmine/floor press; avoid BTN, deep dips, wide bench, upright rows; ER + face pulls | moderate/weak | Kolber 2010 |
| INJ-03 | Low back: keep training; resistance + motor control best; hinge light neutral, hip thrust, belt squat, supported rows; no loaded flexion in flare | moderate/weak | Owen 2020 |
| INJ-04 | Elbow/wrist: −50 % curl/pressdown volume, neutral grips, 3 s eccentrics | weak | convention |

---

## 11. References (author year — venue — URL fetched)

- Abad CC et al. 2011. Combination of general and specific warm-ups improves leg-press 1RM. J Strength Cond Res. https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Combination%20of%20general%20and%20specific%20warm-ups%20improves%20leg-press%20one%20repetition%20maximum%22&resultType=core&format=json
- ACSM (Ratamess NA et al.) 2009. Progression models in resistance training for healthy adults. Med Sci Sports Exerc. https://pubmed.ncbi.nlm.nih.gov/19204579/
- Afonso J et al. 2021. Strength training versus stretching for improving range of motion. Healthcare. https://pubmed.ncbi.nlm.nih.gov/33917036/
- Androulakis-Korakakis P et al. 2020. Minimum effective training dose for 1RM strength in resistance-trained men. Sports Med. https://pubmed.ncbi.nlm.nih.gov/31797219/
- Antonio J et al. 2021. Common questions and misconceptions about creatine supplementation. JISSN. DOI 10.1186/s12970-021-00412-w. https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Common%20questions%20and%20misconceptions%20about%20creatine%20supplementation%22&resultType=core&format=json
- Aragon AA et al. 2017. ISSN position stand: diets and body composition. JISSN. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5470183/
- Ashtary-Larky D et al. 2020. Gradual vs rapid weight loss: body composition and RMR. Br J Nutr. https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=AUTH:%22Ashtary-Larky%22%20AND%20TITLE:%22rapid%20weight%20loss%22&resultType=core&format=json
- Barakat C et al. 2020. Body recomposition in trained individuals. Strength Cond J. https://journals.lww.com/nsca-scj/Fulltext/2020/10000/Body_Recomposition__Can_Trained_Individuals_Build.3.aspx
- Bartholomew JB et al. 2008. Strength gains after resistance training: stressful life events. J Strength Cond Res. https://journals.lww.com/nsca-jscr/fulltext/2008/07000/strength_gains_after_resistance_training__the.26.aspx
- Bauer J et al. 2013. PROT-AGE protein recommendations for older people. JAMDA. https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Evidence-based%20recommendations%20for%20optimal%20dietary%20protein%20intake%20in%20older%20people%22&resultType=core&format=json
- Behm DG et al. 2016. Acute effects of muscle stretching on performance, ROM and injury. Appl Physiol Nutr Metab. https://cdnsciencepub.com/doi/10.1139/apnm-2015-0235
- Bell L et al. 2025. A practical approach to deloading. Strength Cond J. DOI 10.1519/SSC.0000000000000910. https://shura.shu.ac.uk/35313/
- Borde R et al. 2015. Dose–response relationships of resistance training in healthy old adults. Sports Med. https://pmc.ncbi.nlm.nih.gov/articles/PMC4656698/
- Bosquet L et al. 2013. Effect of training cessation on muscular performance: meta-analysis. Scand J Med Sci Sports. https://onlinelibrary.wiley.com/doi/abs/10.1111/sms.12047
- Buchheit M 2014. Monitoring training status with HR measures. Front Physiol. https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full
- Bull FC et al. 2020. WHO 2020 guidelines on physical activity and sedentary behaviour. Br J Sports Med 54:1451–1462. https://pubmed.ncbi.nlm.nih.gov/33239350/
- Clemesha CG et al. 2020. "Testosterone boosting" supplements composition and claims. World J Mens Health. https://wjmh.org/DOIx.php?id=10.5534%2Fwjmh.190043
- Coleman M et al. 2024. One-week deload during supervised resistance training. PeerJ. https://peerj.com/articles/16777/
- Craven J et al. 2022. Acute sleep loss and physical performance: meta-analysis. Sports Med. https://pubmed.ncbi.nlm.nih.gov/35708888/
- Cruz-Sanabria F et al. 2024. Optimizing time and dose of melatonin. J Pineal Res. https://onlinelibrary.wiley.com/doi/10.1111/jpi.12985
- Currier BS et al. 2023. Resistance training prescription: Bayesian network meta-analysis. Br J Sports Med 57:1211–1220. https://research-information.bris.ac.uk/en/publications/resistance-training-prescription-for-muscle-strength-and-hypertro/
- Demay MB et al. 2024. Vitamin D for the prevention of disease: Endocrine Society guideline. J Clin Endocrinol Metab. https://pubmed.ncbi.nlm.nih.gov/38828931/
- Ding D et al. 2025. Daily steps and health outcomes: dose–response meta-analysis. Lancet Public Health. https://www.thelancet.com/journals/lanpub/article/PIIS2468-2667(25)00164-1/fulltext
- Donnelly JE et al. 2009. ACSM position stand: physical activity for weight loss and prevention of regain. Med Sci Sports Exerc 41:459–471. https://pubmed.ncbi.nlm.nih.gov/19127177/
- Eddens L et al. 2018. Intra-session exercise sequence in the interference effect. Sports Med. https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22The%20Role%20of%20Intra-Session%20Exercise%20Sequence%20in%20the%20Interference%20Effect%22&resultType=core&format=json
- Escamilla RF 2001. Knee biomechanics of the dynamic squat exercise. Med Sci Sports Exerc. https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE:%22Knee%20biomechanics%20of%20the%20dynamic%20squat%20exercise%22&resultType=core&format=json
- Fell J, Williams D 2008. Aging and skeletal-muscle recovery from exercise. J Aging Phys Act. (Europe PMC, see WU-06)
- Ferracioli-Oda E et al. 2013. Melatonin for primary sleep disorders: meta-analysis. PLoS One. https://pubmed.ncbi.nlm.nih.gov/23691095/
- Foster C et al. 2001. A new approach to monitoring exercise training (session RPE). J Strength Cond Res. https://pubmed.ncbi.nlm.nih.gov/11708692/
- Fradkin AJ et al. 2010. Effects of warming-up on physical performance: meta-analysis. J Strength Cond Res. https://journals.lww.com/nsca-jscr/fulltext/2010/01000/effects_of_warming_up_on_physical_performance__a.21.aspx
- Fragala MS et al. 2019. Resistance training for older adults: NSCA position statement. J Strength Cond Res 33(8):2019–2052. https://journals.lww.com/nsca-jscr/fulltext/2019/08000/resistance_training_for_older_adults__position.1.aspx
- Frankenfield D et al. 2005. Comparison of predictive equations for RMR. J Am Diet Assoc. https://www.jandonline.org/article/S0002-8223(05)00149-5/abstract
- Garber CE et al. 2011. ACSM position stand: quantity and quality of exercise. Med Sci Sports Exerc. https://pubmed.ncbi.nlm.nih.gov/21694556/
- Gardiner C et al. 2023. The effect of caffeine on subsequent sleep: meta-analysis. Sleep Med Rev. https://pubmed.ncbi.nlm.nih.gov/36870101/
- Garthe I et al. 2011. Two weight-loss rates in elite athletes. Int J Sport Nutr Exerc Metab. https://pubmed.ncbi.nlm.nih.gov/21558571/
- Gentil P et al. 2015. Adding single-joint to multi-joint exercises, trained men. Appl Physiol Nutr Metab. https://cdnsciencepub.com/doi/abs/10.1139/apnm-2015-0109
- Gibala MJ et al. 2006. Short-term sprint interval vs traditional endurance training. J Physiol. https://pubmed.ncbi.nlm.nih.gov/16825308/
- Graham T, Cleather DJ 2021. Autoregulation by RIR vs fixed loading. J Strength Cond Res. https://research.stmarys.ac.uk/id/eprint/3067/3/Graham-Cleather-Autoregulation-Repetitions-in-Reserve.pdf
- Grgic J et al. 2018. Rest interval duration and muscular strength: systematic review. https://www.semanticscholar.org/paper/953a5adf43cc59b5793958ea6a1c26d0031d1587
- Grgic J et al. 2018. Resistance training frequency and strength: meta-analysis. Sports Med. https://pubmed.ncbi.nlm.nih.gov/29470825/
- Guest NS et al. 2021. ISSN position stand: caffeine and exercise performance. JISSN. https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:10.1186/s12970-020-00383-4&resultType=core&format=json
- Hall KD et al. 2011. Quantification of the effect of energy imbalance on bodyweight / dynamics of body weight change. Lancet; PLoS Comput Biol. https://journals.plos.org/ploscompbiol/article?id=10.1371%2Fjournal.pcbi.1000045 ; https://pubmed.ncbi.nlm.nih.gov/22257646/
- Halonen EJ et al. 2024. Continuous vs periodic resistance training (10-week break). Scand J Med Sci Sports. https://onlinelibrary.wiley.com/doi/full/10.1111/sms.14739
- Halperin I et al. 2022. Accuracy in predicting repetitions to task failure. Sports Med. (Europe PMC, see LOAD-03)
- Helgerud J et al. 2007. Aerobic high-intensity intervals improve VO2max more than moderate training. Med Sci Sports Exerc. (Europe PMC, see CAR-05)
- Helms ER et al. 2014. Natural bodybuilding contest preparation: nutrition and supplementation. JISSN. https://pmc.ncbi.nlm.nih.gov/articles/PMC4033492/
- Helms ER et al. 2014. Dietary protein during caloric restriction in resistance-trained lean athletes. Int J Sport Nutr Exerc Metab. (Europe PMC, see NUT-01)
- Helms ER et al. 2016. Application of the RIR-based RPE scale. Strength Cond J. https://pmc.ncbi.nlm.nih.gov/articles/PMC4961270/
- Helms ER et al. 2018. RPE vs percentage 1RM loading. Front Physiol. https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2018.00247/full
- Hirshkowitz M et al. 2015. National Sleep Foundation sleep duration recommendations. Sleep Health. https://www.sleephealthjournal.org/article/s2352-7218(15)00015-7/fulltext
- Holick MF et al. 2011. Endocrine Society vitamin D guideline. J Clin Endocrinol Metab. (Europe PMC, see SUP-04)
- Hooper SL et al. 1995. Markers for monitoring overtraining and recovery. Med Sci Sports Exerc. (Europe PMC, see RDY-01)
- Ingram LA et al. 2025. Optimising the dose of static stretching. Sports Med. https://pubmed.ncbi.nlm.nih.gov/39614059/
- Institute of Medicine 2005/2011. Dietary Reference Intakes summary table (water, fibre). https://www.ncbi.nlm.nih.gov/books/NBK56068/table/summarytables.t4/
- Iraki J et al. 2019. Nutrition recommendations for bodybuilders in the off-season. Sports. (Europe PMC, see NUT-06)
- Iversen VM et al. 2021. No time to lift? Time-efficient training programs. Sports Med. https://pubmed.ncbi.nlm.nih.gov/34125411/
- Jäger R et al. 2017. ISSN position stand: protein and exercise. JISSN. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5477153/
- Jakicic JM et al. 2024. ACSM consensus: physical activity and excess body weight. Med Sci Sports Exerc. https://pubmed.ncbi.nlm.nih.gov/39277776/
- Jakubowski JS et al. 2020. HMB and resistance training in young adults: meta-analysis. https://www.researchgate.net/publication/341619379
- Jeffreys I 2007. Warm-up revisited: the RAMP method. Prof Strength Cond 6:12–18. https://www.researchgate.net/publication/280945961
- Kassiano W et al. 2023. Gastrocnemius hypertrophy after partial ROM at long muscle lengths. J Strength Cond Res. https://pubmed.ncbi.nlm.nih.gov/37015016/
- Kassiano W et al. 2025. Unilateral vs bilateral resistance training: meta-analysis. Sports Med. (Europe PMC, see UNI-01)
- Kay AD, Blazevich AJ 2012. Acute static stretch and maximal muscle performance. Med Sci Sports Exerc. (Europe PMC, see WU-04)
- Kerksick CM et al. 2017. ISSN position stand: nutrient timing. JISSN. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5596471/
- Kerksick CM et al. 2018. ISSN exercise & sports nutrition review update. JISSN. https://link.springer.com/article/10.1186/s12970-018-0242-y
- Knowles OE et al. 2018. Inadequate sleep and muscle strength. J Sci Med Sport. https://pubmed.ncbi.nlm.nih.gov/29422383/
- Kolber MJ et al. 2010. Shoulder injuries attributed to resistance training. J Strength Cond Res. (Europe PMC, see LOAD-03)
- Kreider RB et al. 2017. ISSN position stand: creatine. JISSN. https://pubmed.ncbi.nlm.nih.gov/28615996/
- Kutáč P 2015. Inter-daily variability in body composition among young men. J Physiol Anthropol. https://pmc.ncbi.nlm.nih.gov/articles/PMC4580369/
- Lamon S et al. 2021. Acute sleep deprivation and muscle protein synthesis. Physiol Rep. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7785053/
- LeSuer DA et al. 1997. Accuracy of prediction equations for 1RM. J Strength Cond Res 11(4):211–213. (secondary: https://opensiuc.lib.siu.edu/cgi/viewcontent.cgi?article=1744&context=gs_rp)
- Liao KF et al. 2022. Unilateral vs bilateral resistance training: meta-analysis. Biol Sport. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9331349/
- Little JP et al. 2010. Practical model of low-volume HIIT. J Physiol. (secondary: https://www.gssiweb.org/sports-science-exchange/article/sse-139-physiological-adaptations-to-low-volume-high-intensity-interval-training)
- Lopez P et al. 2021. Resistance training load effects: network meta-analysis. Med Sci Sports Exerc. https://pubmed.ncbi.nlm.nih.gov/33433148/
- Maughan RJ et al. 2018. IOC consensus statement: dietary supplements. Br J Sports Med. (Europe PMC, see Section 7)
- Mifflin MD et al. 1990. A new predictive equation for resting energy expenditure. Am J Clin Nutr 51:241–247. https://mifflinstjeor.com/mifflin-st-jeor-equation/
- Milanović Z et al. 2015. HIIT vs continuous endurance training for VO2max. Sports Med. https://pubmed.ncbi.nlm.nih.gov/26243014/
- Milewski MD et al. 2014. Chronic lack of sleep and sports injuries in adolescent athletes. J Pediatr Orthop. (Europe PMC, see SLP-02)
- Morton RW et al. 2018. Protein supplementation and resistance training gains: meta-regression. Br J Sports Med 52:376–384. https://pubmed.ncbi.nlm.nih.gov/28698222/
- Nunes EA et al. 2025. Lack of evidence for omega-3 in lean mass/strength: overview of reviews. Clin Nutr ESPEN. (Europe PMC, see SUP-05)
- Nunes JP et al. 2021. Resistance exercise order: meta-analysis. Eur J Sport Sci. https://pubmed.ncbi.nlm.nih.gov/32077380/
- Ogasawara R et al. 2013. Continuous vs periodic strength training over 6 months. Eur J Appl Physiol. (Europe PMC, see LAY-01)
- Owen PJ et al. 2020. Exercise modes for low back pain: network meta-analysis. Br J Sports Med. (Europe PMC, see INJ-03)
- Owens DJ et al. 2018. Vitamin D and the athlete. Sports Med. https://link.springer.com/article/10.1007/s40279-017-0841-9
- Pallarés JG et al. 2021. Range of motion and resistance training adaptations: meta-analysis. Scand J Med Sci Sports. https://onlinelibrary.wiley.com/doi/full/10.1111/sms.14006
- Paluch AE et al. 2022. Daily steps and all-cause mortality: 15 cohorts. Lancet Public Health. https://www.thelancet.com/journals/lanpub/article/PIIS2468-2667(21)00302-9/fulltext
- Parr EB et al. 2014. Alcohol ingestion impairs post-exercise myofibrillar protein synthesis. PLoS One. https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0088384
- Pedrosa GF et al. 2022. Partial ROM at long muscle lengths. Eur J Sport Sci. https://onlinelibrary.wiley.com/doi/10.1080/17461391.2021.1927199
- Pelland JC et al. 2025. Resistance training dose response: meta-regressions of weekly volume and frequency. Sports Med. https://pubmed.ncbi.nlm.nih.gov/41343037/ ; preprint https://sportrxiv.org/index.php/server/preprint/view/460
- Persinger R et al. 2004. Consistency of the talk test for exercise prescription. Med Sci Sports Exerc. https://pubmed.ncbi.nlm.nih.gov/15354048/
- Petré H et al. 2021. Maximal dynamic strength during concurrent training. Sports Med Open. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8053170/
- Plews DJ et al. 2013. HRV in elite endurance athletes: effective monitoring. Sports Med. (Europe PMC, see RDY-01)
- Refalo MC et al. 2023. Proximity-to-failure and hypertrophy: meta-analysis. Sports Med. https://pubmed.ncbi.nlm.nih.gov/36334240/
- Refalo MC et al. 2024. Failure vs RIR in resistance-trained individuals. J Sports Sci. https://www.tandfonline.com/doi/full/10.1080/02640414.2024.2321021
- Ribeiro B et al. 2020. Specific warm-up during bench press and squat. J Hum Kinet. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7558980/
- Riebe D et al. 2015. Updating ACSM's exercise pre-participation health screening. Med Sci Sports Exerc. (Europe PMC, see RED-01)
- Robineau J et al. 2016. Concurrent training effects depend on recovery duration. J Strength Cond Res. (Europe PMC, see CAR-06)
- Robinson ZP et al. 2024. Proximity to failure, strength and hypertrophy: meta-regressions. Sports Med 54:2209–2231. https://pubmed.ncbi.nlm.nih.gov/38970765/
- Rogerson D et al. 2024. Deloading practices in strength and physique sports: survey. Sports Med Open. https://link.springer.com/article/10.1186/s40798-024-00691-y
- Rosa A et al. 2022. Single- vs multi-joint exercise hypertrophy: meta-analysis. https://www.researchgate.net/publication/359785878
- Sawka MN et al. 2007. ACSM position stand: exercise and fluid replacement. Med Sci Sports Exerc. https://pubmed.ncbi.nlm.nih.gov/17277604/
- Schoenfeld BJ et al. 2013. Protein timing meta-analysis. JISSN. https://link.springer.com/article/10.1186/1550-2783-10-53
- Schoenfeld BJ et al. 2015. Repetition duration and hypertrophy: meta-analysis. Sports Med. https://pubmed.ncbi.nlm.nih.gov/25601394/
- Schoenfeld BJ et al. 2015. Meal frequency meta-analysis. Nutr Rev. (Europe PMC, see NUT-03)
- Schoenfeld BJ et al. 2016. Training frequency and hypertrophy: meta-analysis. Sports Med. https://pubmed.ncbi.nlm.nih.gov/27102172/
- Schoenfeld BJ et al. 2016. Longer inter-set rest periods in resistance-trained men. J Strength Cond Res. https://journals.lww.com/nsca-jscr/fulltext/2016/07000/longer_interset_rest_periods_enhance_muscle.3.aspx
- Schoenfeld BJ et al. 2017. Dose–response weekly volume and muscle mass. J Sports Sci 35:1073–1082. https://pubmed.ncbi.nlm.nih.gov/27433992/
- Schoenfeld BJ et al. 2017. Low- vs high-load resistance training: meta-analysis. J Strength Cond Res. https://pubmed.ncbi.nlm.nih.gov/28834797/
- Schoenfeld BJ et al. 2017. Pre- vs post-exercise protein intake. PeerJ. (Europe PMC, see NUT-12)
- Schoenfeld BJ, Aragon AA 2018. How much protein in a single meal. JISSN. https://pubmed.ncbi.nlm.nih.gov/29497353/
- Schoenfeld BJ et al. 2019. Training frequency, volume-equated: meta-analysis. J Sports Sci. https://pubmed.ncbi.nlm.nih.gov/30558493/
- Schumann M et al. 2022. Compatibility of concurrent aerobic and strength training. Sports Med. (Europe PMC, see CAR-06)
- Singer A et al. 2024. Give it a rest: Bayesian meta-analysis of inter-set rest and hypertrophy. Front Sports Act Living. https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1429789/full
- Slater GJ et al. 2019. Is an energy surplus required to maximize hypertrophy? Front Nutr. (Europe PMC, see NUT-06)
- Snijders T et al. 2019. Pre-sleep protein ingestion: an update. Front Nutr. https://www.frontiersin.org/journals/nutrition/articles/10.3389/fnut.2019.00017/pdf
- Stults-Kolehmainen MA et al. 2014. Chronic psychological stress impairs recovery over 96 h. J Strength Cond Res. https://journals.lww.com/nsca-jscr/fulltext/2014/07000/chronic_psychological_stress_impairs_recovery_of.26.aspx
- Tanaka H et al. 2001. Age-predicted maximal heart rate revisited. J Am Coll Cardiol 37:153–156. https://www.jacc.org/doi/10.1016/S0735-1097(00)01054-8
- Thomas DT et al. 2016. AND/DC/ACSM position: nutrition and athletic performance. Med Sci Sports Exerc / J Acad Nutr Diet. https://www.jandonline.org/article/S2212-2672(15)01802-X/abstract
- Thomas E et al. 2018. Stretching typology and duration: effects on ROM. Int J Sports Med. (Europe PMC, see MOB-02)
- Trexler ET et al. 2015. ISSN position stand: beta-alanine. JISSN. https://pubmed.ncbi.nlm.nih.gov/26175657/
- Trexler ET et al. 2019. Citrulline malate meta-analysis (secondary: https://www.strongerbyscience.com/research-spotlight-citrulline-malate/); Vårvik FT et al. 2021. Int J Sport Nutr Exerc Metab. https://journals.humankinetics.com/view/journals/ijsnem/31/4/article-p350.xml
- Turicchi J et al. 2020. Weekly, seasonal and holiday body weight fluctuation. PLoS One. https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0232152
- Van Hooren B, Peake JM 2018. Do we need a cool-down after exercise? Sports Med. https://pubmed.ncbi.nlm.nih.gov/29663142/
- Watson NF et al. 2015. AASM/SRS consensus: recommended sleep for adults. J Clin Sleep Med. https://aasm.org/resources/pdf/pressroom/adult-sleep-duration-methods.pdf
- Wiewelhove T et al. 2019. Foam rolling meta-analysis. Front Physiol. https://www.frontiersin.org/articles/10.3389/fphys.2019.00376
- Wilk M et al. 2021. Movement tempo during resistance training: review. Sports Med. https://pubmed.ncbi.nlm.nih.gov/34043184/
- Wilson JM et al. 2012. Concurrent training interference: meta-analysis. J Strength Cond Res. https://pubmed.ncbi.nlm.nih.gov/22002517/
- Wolf M et al. 2023. Partial vs full ROM resistance training: meta-analysis. Int J Strength Cond. https://journal.iusca.org/index.php/Journal/article/download/182/250/
- Wolf M et al. 2025. Lengthened partials vs full ROM in trained individuals. PeerJ. https://peerj.com/articles/18904/
- Wolfe RR 2017. BCAAs and muscle protein synthesis: myth or reality? JISSN. https://pubmed.ncbi.nlm.nih.gov/28852372/
- Zourdos MC et al. 2016. Novel RT-specific RPE scale measuring RIR. J Strength Cond Res. https://pubmed.ncbi.nlm.nih.gov/26049792/

---

## 12. What could not be verified live (implementers: treat as flagged)

1. **Pelland 2025 per-session and inflection numbers.** The abstract (Europe PMC) and preprint page confirm direction (volume ↑ both outcomes with diminishing returns; frequency negligible for hypertrophy, positive for strength) but do not state numeric inflection points. The "≈ 11 fractional sets per session" and "> 11 weekly sets → ≥ 2 sessions" figures in VOL-06 come from secondary summaries (BarBend; search snippets) and are tagged **weak**. The Sports Medicine full text (paywalled) should be checked before hard-coding VOL-06.
2. **Fragala 2019 numeric prescriptions** (70–85 % 1RM ranges; power-training 40–60 % 1RM). The abstract and secondary summaries (2–3 d/wk, 2–3 sets, 8–10 exercises, single-set start for beginners) were verified; the %1RM and power values are from the statement as recalled and could not be fetched (LWW 402, NSCA PDF unreadable). AGE-02 relies on Borde 2015 (verified) for its core numbers.
3. **Kerksick 2018 supplement category table.** The three-category framework was verified; the exact membership lists were not (PMC recaptcha, T&F 403). Items placed in Tier C on the basis of that table (glutamine, arginine, CLA, D-ribose, chromium) are tagged accordingly.
4. **Garber 2011 intensity bands** (64–76 % HRmax moderate; 77–95 % vigorous; HRR 40–59 / 60–89; RPE 12–13 / 14–17) were verified only through a secondary quotation for the moderate band; the vigorous band values are as recalled from Table 1.
5. **Thomas 2016 carbohydrate mid-ranges** (5–7 g/kg for ~1 h/day moderate training; 6–10 g/kg for 1–3 h/day) — the position paper's low (3–5) and very-high (8–12) ends were verified; mid-ranges are as recalled.
6. **IOM AMDR for fat 20–35 %** — the DRI summary table fetched lists AIs for water and fibre but not AMDRs; the 20–35 % value is as recalled from IOM 2005.
7. **EFSA 400 mg/day caffeine guidance value** (SUP-02) — not fetched.
8. **Bickel 2011 maintenance-volume study** (TRV-03) — not fetched; rule tagged weak.
9. **Enes 2024 progressive-set study** (VOL-04/VOL-08) — described from a secondary summary only.
10. **Baz-Valle 2022** (12–20 sets/week band) — appeared only in a secondary page; not cited as a primary source above.
11. **Activity-factor multipliers** (NUT-04) — convention, no primary validation fetched.
12. **ACSM signs/symptoms list** underlying RED-01 — the Riebe 2015 abstract confirms the screening logic but the itemized list is as recalled from ACSM's Guidelines.
13. **Melatonin legal status by country** (SUP-08) — not verified; the app must carry a locale note.
14. All numeric thresholds tagged **weak** in Section 10 are planner defaults chosen for internal consistency, not values extracted from studies; expose them as user-adjustable constants in `:core`.
