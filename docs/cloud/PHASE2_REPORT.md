# Phase 2 report: the app fits Joe (unattended cloud run, 2026-10-16)

The work is on branch **`phase2/profile`** (pushed). It started from `phase1/all` with `origin/main` merged in, which
brought in `docs/USER_PROFILE.md`. `main` and `phase1/all` are untouched, and nothing was force-pushed.

## 1. Bottom line
- `./gradlew :core:test -PgymcoachJdk=21 --rerun` → **BUILD SUCCESSFUL, 943 tests, 0 failures, 0 errors, 0 skipped**.
  Phase 2 started from 921; there are 22 new tests.
- `:core` builds **without Google Maven** now. The `dl.google.com` problem from Phase 1 is fixed in the build files
  (§3).
- Joe's routine is **data, not code**:
  - a seed profile `core/src/main/resources/gymcoach/profiles/joe_v1.json`;
  - five session templates and a split row `joe_5day` in `templates.json`;
  - 12 new drills;
  - three nutrition notes.

  The planner gained the small, generic mechanisms these files need (SPEC §3.4.1, §3.11).
- The 12 generic golden plans are **byte-identical**. I regenerated them together with the new case, and git saw no
  diff. Every contract extension is optional, nullable or defaulted, and nulls are not written, so input hashes are
  unchanged.
- `:app` still has not been compiled (no SDK, by design). The new onboarding ViewModel code ran on a plain JVM
  against the real `:core` jar: **9/9 OnboardingViewModelTest** (6 old + 3 new). The Compose page edits were not
  compiled; they are listed in `docs/build/COMPILE_RISK_LIST.md` (Phase 2 section).

## 2. Commits (in order)
| Commit | What |
|---|---|
| `c90b20b` Build | AGP is resolved only when `:app` is included; `google()` is content-filtered; the CRLF SSE fixture is marked `-text` |
| `c4782a1` Split template + drills | §3.4.1 contract extension, planner/realizer/validator support, templates `JOE_A`…`JOE_E` + row `joe_5day`, 12 drills, LoadTransfer fix |
| `03060aa` Seed profile + gyms | `SeedProfile`/`SeedProfiles`, `joe_v1.json` (profile, Home gym, Bigger gym), reverse pec-deck mode gate |
| `d48e7de` History | the logged sessions as SessionRecord/SetLog (RPE → RIR), progression seeds from §7, e1RM test |
| `747dce1` Golden | `joe_home_5day` byte-compared + 8 structural assertions |
| `e2e4763` Coach context | profile facts in the §6.5 snapshot (system prompt untouched) |
| `f77103c` Nutrition | peri-workout notes (weak), protein-band test |
| `4beb46b` Onboarding (`:app`) | prefill from the seed, gyms + history on first run, generic path kept |
| `ee40ca3` Build doc | `docs/build/FIRST_BUILD_CHECKLIST.md`. `*/build/` is gitignored, so the first commit silently missed it; it is force-added now |
| `2606360` SPEC | §0.7 changelog, §3.4.1, §3.11 |

## 3. Build-file fix (first task)
- The root `build.gradle.kts` no longer declares `com.android.application`. A `buildscript {}` block puts
  `libs.android.gradle.plugin` (new catalog library, `version.ref = "agp"`) on the **root** classpath, and only when
  settings includes `:app` (`gradle.extra["gymcoachAppIncluded"]`). The block runs before the Kotlin plugins.
  - Why not just move the alias into `app/build.gradle.kts`? That would load AGP, and the KGP 2.2.10 it brings along,
    in a second classloader. R2 gotcha G1 warns against exactly that.
  - With this layout, AGP and KGP 2.4.10 still share one root classloader, and conflict resolution still picks 2.4.10.
- `app/build.gradle.kts` applies `id("com.android.application")` without a version, because the plugin is already on
  the classpath.
- Both `google()` declarations are content-filtered to `com.android*`, `com.google.android*`,
  `com.google.testing.platform*` and `androidx*`.
- Verified here:
  - `:core:test` passes with no request to dl.google.com.
  - `ANDROID_HOME=/nonexistent ./gradlew help` tries to resolve `com.android.tools.build:gradle:9.4.0` from
    dl.google.com, so the `:app` branch is wired.
- On your PC: if the first sync complains about the plugin classpath, `FIRST_BUILD_CHECKLIST.md` §2 has the two-line
  revert.
- Maven Central still answered 429s and spurious 404s here. I used a local retrying mirror again, wired through
  `~/.gradle/init.d` outside the repo, and **never committed** it.
- Bonus fix: `.gitattributes` had `*.sse eol=lf`, which normalised `crlf_comments.sse`. The CRLF parser test was
  therefore running against LF input. The file is now `-text`, the CRLF bytes are restored, and the test is green.

## 4. What was wired in

### 4.1 Seed profile `joe_v1` (`SeedProfiles.classpath()`)
- **Profile**:
  - male, born 1978, metric (kg/cm), 120 consistent months (DEF-01 → ADVANCED), hypertrophy, 5 days on
    Mon/Tue/Thu/Fri/Sat, 75 min (the longest template);
  - `split_id` `joe_5day`, physical job;
  - strength targets OHP 60 / bench 100 / squat 140 / deadlift 180;
  - `barbell_rir_floor` 2 (RPE 8 cap on barbell/Smith compounds);
  - priority muscles biceps, triceps, side delts;
  - known loads = the §7 next-session targets, plus BSS 12 kg and incline DB 2×24 kg;
  - eight coach notes, which carry the work-shift rule (4 days in heavy work weeks, arms day dropped first), the
    progression rules, the overshooting habit, the test-week and target-lock rules, logging, the grip/elbow stretch
    and recovery.
- **Limitations** (pain 0):
  - left knee valgus (`knee`, `left`), with the fix in `notes`;
  - hips rise first (`other`);
  - overshooting (`other`).
- **Gyms**:
  - *Home gym* (default): smallest plate 2.5 kg, so every barbell jump is 5 kg. The fly machine is
    `pec_deck_machine` with `has_rear_delt_mode: false`.
  - *Bigger gym*: the home set plus the nine machines of §11, 1.25 kg plates, and a fly machine with rear-delt mode.
- **History**: 11 sessions, 45 sets, every number verbatim. RPE 8 is stored as RIR 2, and the original RPE text is kept
  in `SetLog.note`.
- **Progression seeds** (§7 next targets):
  - squat 90 (top) / 80 (back-off), bench 80 / 70, deadlift 140, press 45 (4×6), close-grip 65.
  - With these, the engine starts from reality, not from find-load.
- The logged history reproduces §2's estimated maxes (test tolerance ±3 %):

  | Lift | e1RM from the log | §2 estimate |
  |---|---|---|
  | OHP | 53.1 | ~52 |
  | Bench | 91.5 | ~92 |
  | Squat | 103.1 | 102–105 |
  | Deadlift | 167.6 | ~170 |

### 4.2 Split `joe_5day` (templates `JOE_A`…`JOE_E`)
- **Order**: Mon A squat, Tue B bench, Thu C deadlift/pull, Fri D press, Sat E arms. The heavy squat and the heavy
  deadlift are 3 or more days apart, and this is checked.
- **4-day weeks**: a `ChangeSchedule(daysPerWeek = 4)` override (for example from the coach) drops E first, and the
  week shows a note.
- **Main lifts**:
  - Squat, bench and deadlift: one top **triple at RIR 2** (slot `s1`, `heavy`), then back-offs on their own track
    `#backoff` (slot `s1b`): 5–7 reps at RIR 2, rest 180 s.
    - Squat and bench have 3 back-offs.
    - Deadlift has 2 back-offs at 0.85 × the top load while the track has no state yet.
    - Bench back-offs carry the paused cue.
  - Press: 4×6 at RIR 2, straight sets.
- **Warm-up ladders** from §8:
  - squat 90 kg: bar, 40, 60, 70, 80 (goblet squat 2×8 as a cue);
  - bench 80: bar, 40, 50, 60, 70;
  - press 45: bar, 30, 40.
- **Accessories**: Joe's exercises, sets, reps and rest, as preference lists that fall back to what the gym has. At
  home: hack squat → Smith squat, leg extension → Spanish squat, abductor → banded lateral walks, lying leg curl →
  slider curl, shoulder press machine → seated DB press, lateral raise machine → DB, reverse pec-deck → cable rear-delt
  fly.
- **Every session**: 5 min easy cardio first (bike on lower days A/C, rower on upper days B/D/E), then the day's
  mobility block with Joe's doses (A ends with banded walks, +5 toward the left, right before the bar), and Joe's
  stretch list as the cool-down.
- **Left knee on day A**: banded walks, Bulgarian split squat ("left leg first", 12 kg until the knee tracks clean),
  abductor work, hip-thrust machine.
- **Cycle**: 6 accumulation weeks, then week 7 is a **test week** (deload-dosed). Its note names the lift closest to
  its target: currently the deadlift at 93 % ("first to fall").
- His routine is never trimmed in advance. Where it exceeds the generic guides, the plan says so in week notes. The
  estimates are SESS-01 set costs, not a stopwatch:
  - A about 82 min vs ~75;
  - C about 89 vs ~70, with lats 13 sets > VOL-06's 12 and 34 sets > SESS-02's 32;
  - D about 82 vs ~60;
  - E about 49 vs ~40.

  If you enter a shorter time for a day, the session is cut in TIME-01 order.

### 4.3 Coach (item 7)
- The context snapshot carries `split`, `strength_targets` (target, current e1RM, %), `barbell_rir_floor` /
  `barbell_rpe_cap` 8, `physical_job`, `notes` (the standing coach notes) and each limitation's `notes` (issue +
  fix).
- These keys appear only when set, so generic profiles keep the pinned key order.
- The system prompt and its SHA-256 are untouched.

### 4.4 Nutrition (item 8)
- MacroTargets gives Joe **1.9 g/kg**: AGE-01, the upper half of NUT-01 from age 40 on. That is inside his 1.6–2.0 band
  for any plausible body weight.
- Coffee 30–45 min before, carbs 60–90 min before, and protein within 1–2 h after are `user_*` tips in
  `nutrition_rules.json` (NUT-12, weak, cited to USER_PROFILE §12).

### 4.5 Onboarding (`:app`, item 5)
- On the first run, every page is prefilled from the seed. The welcome page has a "Starting from your profile: Joe"
  card with "Start from scratch instead", which is the generic path and is remembered.
- Page 2 lists what the profile does not contain and blocks until a body weight is entered.
- The goal page has a "Use my own split (joe_5day)" switch and a 75-min chip.
- The gym page has a "Your gyms" card.
- Building the plan stores both gyms (Home is the default), imports the 11 sessions, 45 sets and 7 progression states
  idempotently (existing rows are never touched), then plans.
- `DraftRules` carries the new profile fields both ways, so the Profile screen never drops them.

## 5. Interpretations of USER_PROFILE.md I had to make
1. **Height (180 cm) is a placeholder** and **body weight is unknown**; neither is in the profile. The seed lists both
   under `placeholders`, and onboarding asks for them.
2. **Age**: born Dec 1978. `Profile.ageOn` is `year − birthYear` (SPEC), so the app says 48 for 2026 (real age 47).
   No rule threshold (40, 60, 65) is affected.
3. **Training age**: "experienced, 25+ years of manual work" → 120 consistent months (any value > 24 gives ADVANCED).
4. **Priority muscles** (max 3): biceps, triceps, side delts ("arms, shoulders"). Abs get the pump circuits anyway.
5. **Known issues**:
   - The knee is a `knee/left` limitation with pain 0. INJ-00 then adds a "keep pain ≤ 3/10" note to knee-sensitive
     blocks, and the generic selector penalises them; the personal templates are not affected.
   - "Hips rise first" and "overshooting" are `other` limitations (notes only).
   - "Tight grip/elbows after arm work" is a coach note, NOT an elbow/wrist limitation: INJ-04 would halve his curl and
     pushdown sets.
6. **Gym equipment** beyond §11's list, taken from the §8 routine: resistance bands, foam roller, **rower and
   stationary bike** (§8: "every session starts with 5 min on the rower/bike"), and a rope attachment.
   - Home "cable station" = `cable_single`; "bench with safety arms" = `adjustable_bench` + `power_rack.has_safeties`;
     "chin-up bar" = `pull_up_bar`; "fly machine" = `pec_deck_machine` without rear-delt mode.
   - The **oblique machine has no catalogue entry**: the template uses a cable woodchop and says so.
7. **Exercises missing from the catalogue**, mapped to the closest entry, with the cue in the slot note:
   - reverse crunch → lying leg raise;
   - bicycle crunch and oblique machine → cable woodchop;
   - single-arm cable row → seated cable row;
   - hanging knee raise → hanging leg raise;
   - pause squat → back squat (log note);
   - EZ-bar curl → barbell curl. The resolver takes the barbell option first; the cue says EZ bar.
8. **Dates and RPE**:
   - Deadlift 160×2 "Sept" has no date; 15 Sep 2026 is a flagged placeholder.
   - "RPE 8–8.5" is logged as RIR 1 (the harder end, which gives the lower e1RM).
   - Sets without a logged RPE have RIR null.
   - The bench "95 missed" is a 95 kg × 0 row with a note.
   - BSS 12 kg and incline DB 24 kg have no logged reps, so reps are null.
   - Sessions from 6 Oct on are attributed to the bigger gym.
9. **Back-off target loads** are the §7 next-session targets; the deadlift back-off has none, so 0.85 × top is used.
   The top-set track's rep band is 3–3 and the back-off track's is 5–7. With those bands, PROG double progression
   reproduces "5 → 6 → 7, then +5 kg".
10. **Weekdays**: "lower/upper/lower/upper" with squat and deadlift ≥ 2–3 days apart → Mon/Tue/Thu/Fri, with the
    optional arms day on Saturday.
11. **Session minutes**: `Profile.sessionMinutes` = 75 (the longest). Each template carries its own 75/70/70/60/40.
12. **Mobility holds of 30–45 s before lifting** are kept as his routine. WU-04 only advises against ≥ 60 s.

## 6. Open decisions for Johannes
1. **Deadlift after the September break.** LAY-01 row 3 (30 days beyond the weekly cadence) scales the seeded ~140 kg
   triple to **110 kg** (× 0.775) and halves the back-off sets, with a visible LAY-01 note. Your §7 note already
   allowed for the break, and the September date is a placeholder. Options:
   (a) keep the rule;
   (b) give me the real date;
   (c) treat seeded loads as "already post-break" (one flag on the progression seed).
2. **Top-set increments.** Your rule is "+5 kg only when the triple feels like RPE 7". The engine adds load when the
   set is RIR ≥ 4 (PROG-04 easy offset 2), or after **two** consecutive triples at RIR ≤ 2 (advanced two-session rule).
   The second path is looser than your rule. Should it become "RPE 7 = increment, RPE 8 = hold" for top-set tracks? A
   per-slot flag would do it.
3. **Back-off progression** needs two sessions at 7 reps before +5 kg (advanced two-session rule), not one. Keep it?
4. **Squat increments at the bigger gym**: PROG-02 keeps 5 kg for a lower-body barbell lift ≤ 100 kg even with 1.25 kg
   plates. Bench, press and deadlift (> 100 kg) get 2.5 kg there. Should "use 1.25s when available" override PROG-02?
5. **Session lengths**: by the time model, C (~89 min) and D (~82 min) are much longer than your ~70/~60. Trim C's
   straight-arm pulldown or the abs circuit (lats 13 sets > VOL-06), or accept?
6. **Extra zone-2 cardio**: CAR-07 adds 2 × 20 min bike on Wed/Sun for a hypertrophy goal. Your routine does not
   mention it. Keep it (it is explained), or remove it with the RemoveCardio override?
7. **Catalogue gaps**: add exercise rows for reverse crunch, bicycle crunch, single-arm cable row, oblique-machine
   crunch (plus an `oblique_machine` equipment id) and pause squat? That needs `ids_v2.txt`, content batches and count
   tests, so it was out of scope here.
8. **Equipment assumptions** (§5 item 6): does the home gym have a rower, a bike, bands and a foam roller? If not,
   untick them in the Gyms screen. Rower/bike missing at home means calisthenics as the raise.
9. **Under-target cards** (informational, H23): side delts 6.5/15, hamstrings 6.75/15, calves 3/9, chest 11.5/15,
   lats 13/15, quads 12.5/15 and glutes 13/15 sets per week. Raise anything?
10. **`:app` follow-ups**: a mid-session swap or a free block of a back-off slot uses the main track key. The first
    `:app` build is still yours (`tools/setup-android-sdk.ps1 -Build`); see the checklist.
11. The Phase-1 open decisions (`PHASE1_REPORT.md` §7) are unchanged.

## 7. Tests: counts and changed expectations
- Phase 2 started at 921 and ends at **943**. The 22 new tests:

  | Test class | Tests | Covers |
  |---|---|---|
  | `SeedProfileTest` | 7 | load/validate, facts, gyms, increments, history, e1RM, states |
  | `JoeGoldenTest` | 8 | the golden case |
  | `PersonalTemplateValidationTest` | 3 | |
  | `CoachProfileContextTest` | 2 | |
  | `JoeNutritionTest` | 2 | |

  `OnboardingViewModelTest` gained 3 tests (`:app`, run on the JVM harness).
- **Changed expectations**, all for new rows or values, not weakened:
  - `KnowledgeLoaderTest` splits 13 → 14;
  - `SessionTemplatesTest` split order + `CUSTOM`;
  - `EnumSerialNamesTest` SplitKind 9 → 10.
- **Behaviour changes in generic code**, all tests green and the 12 golden plans unchanged:
  - INV-13 counts per exercise per session;
  - the user's own routine is explained rather than failed for INV-2/SESS-02/VOL-06;
  - LoadTransfer no longer crosses the machine/sled scale. Before, a 90 kg back squat seeded a 180 kg Smith squat;
  - the reverse pec-deck needs the rear-delt mode.

## 8. How to reproduce
```
git fetch && git checkout phase2/profile
./gradlew :core:test                      # JDK 17 on the PC; -PgymcoachJdk=21 on a JDK-21-only VM
GYMCOACH_REGENERATE_GOLDEN=true ./gradlew :core:test --tests '*Golden*'   # regenerate goldens (git diff must be explained)
```
