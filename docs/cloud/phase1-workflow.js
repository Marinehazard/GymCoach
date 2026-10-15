// GymCoach Phase 1 — Claude Code dynamic-workflow script (plain JavaScript, no TypeScript).
// Run from the repository root:  Workflow({scriptPath: "docs/cloud/phase1-workflow.js",
//   args: {gradle: "./gradlew -PgymcoachJdk=21", uiModel: "sonnet"}})
// args.gradle  — the Gradle command to use (default "./gradlew"; add -PgymcoachJdk=21 on a JDK-21-only VM)
// args.uiModel — optional model override for the UI packages and the :app consistency pass (e.g. "sonnet")
export const meta = {
  name: 'gymcoach-phase1',
  description: 'GymCoach Phase 1: WP-02..WP-08 in two waves plus a contract verifier, :core integration loop, :app static consistency pass',
  phases: [
    { title: 'Wave A', detail: 'WP-02 knowledge/equipment; WP-06 data/network; WP-07 training UI; WP-08 setup/coach UI; contract verifier' },
    { title: 'Wave B', detail: 'WP-03 planner; WP-04 progression/health; WP-05 coach core (after WP-02)' },
    { title: 'Integrate core', detail: 'gradle :core:test green across all packages' },
    { title: 'App consistency', detail: ':app sources checked against the compiled :core API and pinned Compose/AndroidX APIs' },
  ],
}

const GRADLE = (args && args.gradle) || './gradlew'
const UI_MODEL = (args && args.uiModel) || undefined
const uiOpts = (extra) => UI_MODEL ? Object.assign({ model: UI_MODEL }, extra) : extra

const COMMON = `
PROJECT ROOT = the git repository root of the current working directory (run "git rev-parse --show-toplevel"); every path below is relative to it.
Normative documents: CLAUDE.md (read first), docs/SPEC.md (v1.2, ~8,300 lines — section numbers are stable, line numbers are not: locate sections with grep -n "^### <number>" and read them IN FULL in offset/limit chunks), docs/REQUIREMENTS.md, docs/research/R1..R4 (SPEC wins where they differ). SPEC §0.5 is the changelog of the v1.1/v1.2 contract changes (WeekRollResult.states/ladderNotes, PlanInputs.deloadTriggersActive, PlanCoordinator.recordDeloadDecision/plannerProposals, strength split rows in templates.json, confidence-tag normalisation).
Phase 0 already landed: build files, every :core contract of SPEC §2 (+ §4.1 PlannerConstants, §4.10 E1rm), the :app shell (AppContainer, theme, nav, shared components, placeholder screens) and the six exercise_content batch files — READ THE ACTUAL FILES ON DISK for any type you use; the code is the truth for names and signatures of Phase-0 types (report drift from SPEC in your result instead of editing Phase-0 files).
House rules (CLAUDE.md, SPEC §1.6): compile-conservative Kotlin, explicit imports, @OptIn where Material3/Compose APIs are experimental, no reflection, no Android imports in :core, kotlinx.serialization annotations on everything persisted or transported. Ownership: write ONLY the files your package owns (SPEC §9 table) plus tests under your own test packages; never edit Phase-0 files or another package's files — describe needed changes in your result. Do not download the Android SDK or accept its licence. Do not commit.
BUILD: "${GRADLE} :core:compileKotlin :core:compileTestKotlin --console=plain 2>&1 | tail -120" type-checks; "${GRADLE} :core:test --console=plain 2>&1 | tail -120" tests; add --tests "com.gymcoach.core.<yourpackage>.*" to run only yours. Use long timeouts (600000 ms). Other agents edit other :core packages concurrently: compile errors in files you do not own are NOT yours to fix — note them, keep going, re-run later; on a Gradle lock timeout wait a minute and retry. :app cannot be compiled (no Android SDK): for :app files be doubly careful with signatures and produce a compile-risk list (every AndroidX/Compose/Room/OkHttp symbol you rely on, with the version and why you believe it exists).
Your final text is not shown to a human — return only the structured result.
`

const RESULT_SCHEMA = {
  type: 'object',
  properties: {
    files_written: { type: 'array', items: { type: 'string' } },
    build_status: { type: 'string', description: 'exact outcome of your last gradle run (or "not compilable here" for :app)' },
    tests: { type: 'string', description: 'tests written and their pass/fail status' },
    spec_drift: { type: 'array', items: { type: 'string' }, description: 'places where SPEC and the Phase-0 code disagree, or where you deviated from SPEC, with reason' },
    needs_from_others: { type: 'array', items: { type: 'string' }, description: 'changes you need in files you do not own (file, symbol, why)' },
    compile_risk_list: { type: 'array', items: { type: 'string' } },
    notes: { type: 'string' },
  },
  required: ['files_written', 'build_status', 'tests', 'spec_drift', 'needs_from_others', 'compile_risk_list', 'notes'],
}
const CRITIC_SCHEMA = {
  type: 'object',
  properties: {
    issues: { type: 'array', items: { type: 'object', properties: {
      severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] }, area: { type: 'string' }, location: { type: 'string' }, description: { type: 'string' }, fix: { type: 'string' },
    }, required: ['severity', 'area', 'location', 'description', 'fix'] } },
    verdict: { type: 'string', enum: ['ready', 'needs_revision'] },
    notes: { type: 'string' },
  },
  required: ['issues', 'verdict', 'notes'],
}

phase('Wave A')
const verifyP = agent(COMMON + `
TASK: Independent verification (no edits). (1) Drift check SPEC → code: for every Kotlin block in SPEC §2.1–§2.15, §4.1, §4.10 and the frozen :app signatures (§1.5, §8.3, §8.4), compare with the file on disk (core/src/main/kotlin/**, app/src/main/kotlin/com/gymcoach/app/{di,ui/nav,ui/components}/**): report every difference in type names, fields, defaults, @SerialName values, function signatures. (2) Confirm the §0.5 changelog items are present in both SPEC and code. (3) Run "${GRADLE} :core:test --console=plain 2>&1 | tail -60" once and report the outcome. Report only real issues; verdict "ready" only if zero critical/high.`,
  { label: 'verify:spec+contracts', phase: 'Wave A', schema: CRITIC_SCHEMA })

const wp02P = agent(COMMON + `
TASK: Implement WP-02 — knowledge assets + equipment (SPEC §9 row WP-02). Read §3 in full (§3.1–§3.9), §2.4 Knowledge.kt ON DISK, §2.15 signature contracts for the knowledge/equipment services, §4.1 PlannerConstants ON DISK (rules.json constants must equal its defaults), §5.4–§5.8 (cardio/supplement/mobility tables), docs/research/R4 §1–§6 in full (equipment taxonomy, muscle weights, all 156 exercise rows, JSON schemas), R3 §10 rule table (every rule id → rules.json), and docs/review/CONTENT_REVIEW_NOTES.md.
Deliverables: exercises.json (all 156 ids, every field transcribed exactly from R4), equipment.json (77 items, categories, DE/EN aliases, presets base/home/hotel/travel with session minutes), templates.json (§3.4 incl. the STRENGTH split rows as separate entries per v1.2), rules.json (every R3 §10 id with the §3.5 confidence normalisation rule, constants equal to PlannerConstants defaults), mobility_drills.json, checkin_questions.json (CHK-01 all 15), supplements.json, cardio_modalities.json, nutrition_rules.json, rp_landmarks.json, load_transfer.json (Appendix C); the Kotlin loader/validator/rule-book/presets/equipment resolver/increment resolver/load rounder classes exactly per §3.9 and §2.15; the tests in the WP-02 acceptance cell plus core/src/test/resources/knowledge/ids_v1.txt. The six exercise_content/batch_N.json files ALREADY EXIST — do NOT overwrite them; the loader merges them as content overlays (§3.2) and the validator checks their id coverage.
Review notes: docs/review/CONTENT_REVIEW_NOTES.md lists suspected R4 inconsistencies. Numbers come from R4; change one ONLY where a note identifies a clear safety/usability defect, and record every decision (applied / kept, with reason) in docs/review/WP02_DECISIONS.md; never change a number silently.
Build: "${GRADLE} :core:test --tests "com.gymcoach.core.knowledge.*" --tests "com.gymcoach.core.equipment.*" --console=plain" must be green; run the full :core:test once at the end and report.`,
  { label: 'WP-02:knowledge+equipment', phase: 'Wave A', schema: RESULT_SCHEMA })

const wp06P = agent(COMMON + `
TASK: Implement WP-06 — :app data, network, secure storage (SPEC §9 row WP-06). Read in full: §7 (Room schema §7.1 exactly, repositories/mappers §7.2, DataStore §7.3, KeystoreCipher §7.4, knowledge loading §7.5, PlanCoordinator §7.6 incl. recordDeloadDecision / plannerProposals / buildInputs), §6.2 request profile, §6.10 errors/retry, §6.13 bridge protocol, §1.5 AppContainer ON DISK (your classes must have the constructors it calls), §2.11 repository interfaces ON DISK, §2.15 signatures for Planner / ProgressionEngine / DeloadPolicy / KnowledgeBase that PlanCoordinator calls (written concurrently by other packages — code against the §2.15 signatures exactly), R1 §3 (API shapes) and R2 §6 (keystore design).
Deliverables: AppDatabase/Entities/Daos/Migrations (Room 2.8.4, KSP, schema export dir app/schemas), every Room*Repository + Mappers + EnumNames + PlanCoordinator, SettingsDataStore, KeystoreCipher + KeystoreApiKeyStore, AssetKnowledgeSource, network/claude/{DirectApiBackend,RetryPolicy,HttpErrorMapper,SseBodyReader} (OkHttp streaming → :core SseParser per §6.3 signatures), network/bridge/{BridgeBackend,NotConfiguredBackend}, the JVM unit tests listed (MappersTest, SettingsMappingTest, HttpErrorMapperTest, RetryPolicyTest — no Android needed), docs/bridge/PROTOCOL.md. Cross-check every :core symbol against the files on disk, keep Room entities primitive-only, never log the key, and produce a complete compile-risk list.`,
  { label: 'WP-06:data+network', phase: 'Wave A', schema: RESULT_SCHEMA })

const wp07P = agent(COMMON + `
TASK: Implement WP-07 — training UI (SPEC §9 row WP-07). Read in full: §8.1–§8.4 (the shared components exist ON DISK under app/.../ui/components with frozen signatures; use them), §8.6 Today (onAcceptDeload/onDismissDeload, plannerProposals from PlanCoordinator), §8.7 Readiness, §8.8 Session (one-thumb set logging, prefilled targets, steppers, plate calculator, rest bar, RIR chips, swap sheet ranked by available equipment, exercise info sheet, finish sheet, red-flag sheet, keypad), §8.9 Session summary, §8.10 Plan + Adjustment log, §8.11 Progress (LineChart/MuscleBandBars), §8.20 empty/error states, §1.5 AppContainer ON DISK, §2 models ON DISK, §2.15 service signatures (ProgressionEngine, SessionRealizer, SubstitutionEngine, ProgressSeries, ReadinessScorer — written concurrently; code against the signatures), §7.6 PlanCoordinator API, §1.8 threading/state.
Deliverables: every file in the WP-07 ownership list, app/src/test/.../fakes/FakeRepositories.kt (implements every core.repo interface in memory — WP-08 tests use it read-only), and the three ViewModel tests (TodayViewModelTest, SessionViewModelTest incl. "Log set writes exactly the displayed values" and corrections supersede, PlanViewModelTest) runnable on the JVM without Android (ViewModels import nothing from android.* except androidx.lifecycle.ViewModel; use kotlinx-coroutines-test). Overwrite the Phase-0 placeholder screen files keeping the frozen signatures. No androidx.room / okhttp3 imports under ui/. Each file ≤ 400 lines. Every displayed number the user logs is written exactly as displayed. Produce the compile-risk list.`,
  uiOpts({ label: 'WP-07:training-ui', phase: 'Wave A', schema: RESULT_SCHEMA }))

const wp08P = agent(COMMON + `
TASK: Implement WP-08 — setup, health, coach and settings UI (SPEC §9 row WP-08). Read in full: §8.1–§8.4 (components ON DISK, frozen), §8.5 Onboarding (6 pages, ends with a generated plan via PlanCoordinator.recompute), §8.12 Library + exercise detail (content overlay; "not written yet" fallback), §8.13 Gyms + gym editor (every R4 equipment param, presets, DE/EN alias search, live availability count, add-equipment-today one-off), §8.14 Profile (metric/imperial), §8.15 Weekly check-in, §8.16 Nutrition + Knowledge (RuleBook), §8.17 Coach (conversation list, streaming bubbles, proposal cards Apply/Reject wired to ProposalApplier, quick prompts, context sheet, offline banner + queued draft), §8.18 Settings (backend selection with the honest policy text from R1 §2.5 in strings_backend_policy.xml, API-key entry with the sk-ant-api prefix rule, "Test connection" 32-token request, bridge URL, model/effort, units, advanced constants editor writing overrides JSON), §8.19 Usage/More, §8.20 states, §6.1 backends/policy, §6.7–§6.9 turn orchestration/proposals (CoachTurnService, TurnEvent — WP-05, concurrent; code against §2.15/§6 signatures), §7.3 DataStore keys, §1.5 AppContainer ON DISK.
Deliverables: every file in the WP-08 ownership list, strings_backend_policy.xml, and the three ViewModel tests (GymEditViewModelTest, CoachViewModelTest with a fake backend replaying a fixture: card appears; Apply → next request carries "applied" first; Reject → "rejected"; read-only auto follow-up capped; OnboardingViewModelTest) on the JVM using WP-07's FakeRepositories (written concurrently at app/src/test/kotlin/com/gymcoach/app/fakes/FakeRepositories.kt — if it does not exist yet, code against the core.repo interfaces and note it). Overwrite the Phase-0 placeholder screens keeping the frozen signatures. Same file-size, import and compile-risk rules as WP-07.`,
  uiOpts({ label: 'WP-08:setup+coach-ui', phase: 'Wave A', schema: RESULT_SCHEMA }))

const wp02 = await wp02P
log(`WP-02: ${wp02 ? wp02.build_status : 'FAILED'}`)
const wp02Note = wp02 ? `WP-02 (knowledge/equipment) has landed: ${wp02.notes}\nWP-02 drift/needs: ${wp02.spec_drift.concat(wp02.needs_from_others).join(' | ')}` : 'WP-02 agent failed; read whatever exists under core/src/main/kotlin/com/gymcoach/core/{knowledge,equipment} and the JSON assets.'

phase('Wave B')
const waveB = await parallel([
  () => agent(COMMON + `
${wp02Note}
TASK: Implement WP-03 — planner, realizer, substitution, ledger, mutations (SPEC §9 row WP-03). Read in full: §4 intro, §4.1 (PlannerConstants ON DISK), §4.2–§4.8, §4.12–§4.17 (worked examples E1/E2/E3/G2/G5 are golden fixtures — reproduce the stated numbers), §5.7 WarmupGenerator, §5.8 MobilityPlanner, §3.4 templates and §3.9 knowledge API (use the WP-02 classes ON DISK), §2 models ON DISK (PlanInputs.deloadTriggersActive, WeekRollResult.states/ladderNotes), §2.15 signature contracts for every class you own, §4.16 PlanValidator invariants 1–18, R3 §10 for rule ids in explanations.
Deliverables: every file in the WP-03 ownership list with the exact §2.15 signatures; explanations carry rule ids; determinism (same inputs → byte-identical JSON); the tests in the acceptance cell (PlanGeneratorGoldenTest for E1/E2/E3/G2/G5, SessionRealizerTest, SubstitutionEngineTest, WeeklyLedgerTest, 12 golden cases under core/src/test/resources/golden, PlanInvariantPropertyTest with java.util.Random(42) over 500 profile×gym×goal combinations, DeterminismTest, MutationValidatorTest for every §4.15 row, WarmupGeneratorTest, TimeConcessionsTest, PlanValidatorTest incl. NOVICE+STRENGTH+3 days). A plan must never schedule an exercise whose equipment options are unsatisfied by the gym; user-logged numbers are never altered.
Build: "${GRADLE} :core:test --tests "com.gymcoach.core.planner.*" --tests "com.gymcoach.core.substitution.*" --tests "com.gymcoach.core.volume.*" --tests "com.gymcoach.core.mobility.*" --tests "com.gymcoach.core.mutation.*" --console=plain" green; report the full :core:test outcome at the end.`,
    { label: 'WP-03:planner', phase: 'Wave B', schema: RESULT_SCHEMA }),
  () => agent(COMMON + `
${wp02Note}
TASK: Implement WP-04 — progression, readiness, check-ins, cardio, nutrition, supplements (SPEC §9 row WP-04). Read in full: §4.9 ProgressionEngine (double progression, increments per load type via the WP-02 IncrementResolver ON DISK, RIR-05 correction, Amber blocks increments, every decision emits an Adjustment, input SetLogs provably unmodified), §4.10 E1rm (ON DISK, WP-01 — use it), §4.11 plateau ladder/deloads/regression (ladder advances return to the planner via WeekRollResult.states; you own PlateauDetector/DeloadPolicy per §2.15), §5.1 ReadinessScorer (G2 numbers), §5.2 CheckinBank/CheckinEvaluator/RedFlags, §5.3, §5.4 CardioPlanner/HeartRateZones/HiitProtocols (G3), §5.5 EnergyEstimator/TdeeAdapter/WeightTrend/MacroTargets (G4), §5.6 SupplementAdvisor, §2 models ON DISK, §2.15 signatures for every class you own, §3.9 knowledge API ON DISK, R3 §10 rule ids.
Deliverables: every file in the WP-04 ownership list; ProgressSeries producing chart rows (§2.14 Views ON DISK) for weight MA7, e1RM, volume, readiness; the tests in the acceptance cell reproducing G1, G2 (58/50/32/56/100/0; band edges 44/45/69/70), G3, G4 (1,780 / 2,759 / 3,035 / 151 / 84 / 419 / 42 / 3.7; −150 → 2,609; cut-offs 10:00 / 14:00; creatine 5 g), G6; E1rmTest rows are WP-01's — do not duplicate; ProgressionEngineTest, DeloadPolicyTest (≥ 2 triggers AND ≥ 5 days), CheckinEvaluatorTest, RedFlagsTest.
Build: "${GRADLE} :core:test --tests "com.gymcoach.core.progression.*" --tests "com.gymcoach.core.readiness.*" --tests "com.gymcoach.core.checkin.*" --tests "com.gymcoach.core.cardio.*" --tests "com.gymcoach.core.nutrition.*" --console=plain" green; report the full :core:test outcome.`,
    { label: 'WP-04:progression+health', phase: 'Wave B', schema: RESULT_SCHEMA }),
  () => agent(COMMON + `
${wp02Note}
TASK: Implement WP-05 — coach core (SPEC §9 row WP-05). Read in full: §6 (§6.1–§6.13; the system prompt §6.4 ships byte-stable as core/src/main/resources/gymcoach/coach/system_prompt_v1.txt with a SHA-256 fixture; §6.5 context assembly incl. the gym fallback; §6.6 the 20 strict tools; §6.7 CoachTurnService; §6.8 ProposalApplier — never touches logged sets; §6.9 compaction; §6.11 PricingTable/SpendMeter/TokenEstimator; §6.12 RedFlagScanner/SupplementCaveat), §2.10 Coach.kt and §2.11 ClaudeBackend/BackendError ON DISK, §2.15 signatures for every class you own, R1 §3 (exact Messages API shapes, SSE event JSON, tool_use/tool_result, stop reasons, error JSON) and R1 §3.6 fixtures → core/src/test/resources/sse/*.sse, §3.9 knowledge API ON DISK (rule digest, exercise vocabulary for the prompt).
Deliverables: coach/wire/{WireTypes,RequestBuilder,SseParser,StreamAccumulator}.kt (pure Kotlin, kotlinx.serialization; SSE parser tolerant to split-at-any-offset), coach/{SystemPrompt,CoachContextBuilder,ToolSchemas,ToolContract,ToolExecutor,ProposalApplier,TranscriptCompactor,CoachTurnService,TurnEvent,PricingTable,SpendMeter,TokenEstimator,RedFlagScanner,SupplementCaveat}.kt, the two prompt resources, and every test in the acceptance cell (SseParserTest + fuzz, StreamAccumulatorTest, RequestBuilderTest — tool_result first, echo unchanged, no trailing assistant, cache_control present, no sampling params —, SystemPromptTest with SHA-256 and rule-id coverage, CoachContextBuilderTest, ProposalSchemasTest — 20 schemas, additionalProperties:false, required ⊆ properties —, ToolContractTest, ProposalApplierTest, CoachTurnServiceTest with a fake backend replaying fixtures, PricingTableTest $0.02175, RedFlagScannerTest, SupplementCaveatTest). Request profile per SPEC §6.2: model claude-opus-5, thinking {type: adaptive}, output_config.effort, tool_choice auto, strict tools, cache_control on system+tools and top-level for history, max_tokens per spec, never temperature/top_p/top_k, never assistant prefill.
Build: "${GRADLE} :core:test --tests "com.gymcoach.core.coach.*" --console=plain" green; report the full :core:test outcome.`,
    { label: 'WP-05:coach-core', phase: 'Wave B', schema: RESULT_SCHEMA }),
])

const [wp06, wp07, wp08, verify] = await Promise.all([wp06P, wp07P, wp08P, verifyP])
const waveBOk = waveB.filter(Boolean)
log(`Wave A/B done: WP-06 ${wp06 ? 'ok' : 'FAIL'}, WP-07 ${wp07 ? 'ok' : 'FAIL'}, WP-08 ${wp08 ? 'ok' : 'FAIL'}, Wave B ${waveBOk.length}/3; verifier: ${verify ? verify.verdict + ' (' + verify.issues.length + ' issues)' : 'FAIL'}`)

const allResults = [['WP-02', wp02], ['WP-03', waveB[0]], ['WP-04', waveB[1]], ['WP-05', waveB[2]], ['WP-06', wp06], ['WP-07', wp07], ['WP-08', wp08]]
const needs = allResults.flatMap(([k, r]) => r ? r.needs_from_others.map(n => `${k}: ${n}`) : [`${k}: AGENT FAILED — inspect its files`])
const drift = allResults.flatMap(([k, r]) => r ? r.spec_drift.map(n => `${k}: ${n}`) : [])
const verifyIssues = verify ? verify.issues.filter(i => i.severity !== 'low').map(i => `[${i.severity}] ${i.location}: ${i.description} FIX: ${i.fix}`) : []

phase('Integrate core')
const INTEGRATE_SCHEMA = { type: 'object', properties: { build_status: { type: 'string' }, test_summary: { type: 'string' }, fixes: { type: 'array', items: { type: 'string' } }, remaining_failures: { type: 'array', items: { type: 'string' } }, green: { type: 'boolean' } }, required: ['build_status', 'test_summary', 'fixes', 'remaining_failures', 'green'] }
let integ = null
let prev = ''
for (let r = 1; r <= 3; r++) {
  integ = await agent(COMMON + `
TASK: :core integration round ${r}. Ownership is lifted for this task: you may edit ANY file under core/ (main and test) and docs/SPEC.md. Goal: "${GRADLE} :core:test --console=plain" ends BUILD SUCCESSFUL with zero failures, and the tests stay faithful to SPEC (fix implementations, not expectations, unless a test contradicts SPEC or a golden number is arithmetically wrong — then fix the test AND note it).
Inputs from the package agents — cross-package needs: ${needs.join(' | ') || 'none'}. Spec drift reported: ${drift.join(' | ') || 'none'}. Verifier's open issues (apply to SPEC and code where still relevant): ${verifyIssues.join(' | ') || 'none'}. ${prev}
Method: compile first, group errors by package, fix each group with minimal signature-preserving changes (prefer adapting the caller to the callee's §2.15 signature), re-run, then run tests and fix failures. Also run the checks: no "import android" under core/src/main; every RuleIds constant referenced somewhere in core main (RuleCoverageTest); knowledge validation passes with the batch overlays. Return the exact final gradle outcome and a list of every fix.`,
    { label: `integrate:core-r${r}`, phase: 'Integrate core', schema: INTEGRATE_SCHEMA })
  if (!integ) break
  log(`Core integration round ${r}: ${integ.build_status}; green=${integ.green}`)
  if (integ.green) break
  prev = `Previous round ended with: ${integ.build_status}. Remaining failures: ${integ.remaining_failures.join(' | ')}. Continue from the current state of the files.`
}

phase('App consistency')
const APP_SCHEMA = { type: 'object', properties: { files_checked: { type: 'number' }, fixes: { type: 'array', items: { type: 'string' } }, unresolved: { type: 'array', items: { type: 'string' } }, compile_risk_list: { type: 'array', items: { type: 'string' } } }, required: ['files_checked', 'fixes', 'unresolved', 'compile_risk_list'] }
const APP_GROUPS = [
  { key: 'data+network+di', globs: 'app/src/main/kotlin/com/gymcoach/app/{GymCoachApp,MainActivity}.kt, app/.../di/**, app/.../data/**, app/.../network/**, app/src/test/kotlin/com/gymcoach/app/{data,network}/**' },
  { key: 'training-ui', globs: 'app/.../ui/{today,readiness,session,plan,progress}/**, app/.../ui/{theme,nav,components}/** (read-only unless a frozen signature is provably broken), app/src/test/.../ui/{Today,Session,Plan}ViewModelTest.kt, app/src/test/.../fakes/**' },
  { key: 'setup+coach-ui', globs: 'app/.../ui/{onboarding,profile,gyms,library,checkin,nutrition,knowledge,coach,settings,usage,more}/**, app/src/main/res/**, app/src/test/.../ui/{GymEdit,Coach,Onboarding}ViewModelTest.kt' },
]
const appPass = (await parallel(APP_GROUPS.map(g => () => agent(COMMON + `
TASK: :app static consistency pass for the group "${g.key}" — files: ${g.globs}. The :core module now compiles and its tests pass: its public API ON DISK (core/src/main/kotlin/**) is the truth. :app cannot be compiled here, so act as the compiler: for every file in your group, check every import resolves (a :core class/function with that exact name, package and signature exists; every AndroidX/Compose/Room/OkHttp/kotlinx symbol exists in the pinned versions of gradle/libs.versions.toml — verify against docs/research/R2 and, when unsure, against the library's API docs via WebFetch), every call site matches the callee's parameter names/types/nullability, every when over a sealed class/enum is exhaustive, every suspend call is inside a coroutine, every composable is called from a composable, @OptIn present where needed, Room entities/DAOs are annotation-valid, no androidx.room/okhttp3 import under ui/, resources referenced (R.string.*) exist in res/values. Fix what is wrong in your group's files (you may edit them; do not edit :core; do not edit files outside your group except to add a missing string resource under res/values in the setup group). Return files_checked, every fix, anything you could not resolve, and a consolidated compile-risk list for your group.`,
  uiOpts({ label: `app-check:${g.key}`, phase: 'App consistency', schema: APP_SCHEMA }))))).filter(Boolean)

return {
  verifier: verify ? { verdict: verify.verdict, issues: verify.issues.filter(i => i.severity !== 'low') } : null,
  packages: allResults.map(([k, r]) => ({ wp: k, ok: !!r, build: r ? r.build_status : null, tests: r ? r.tests : null, needs: r ? r.needs_from_others : null, notes: r ? r.notes.slice(0, 600) : null })),
  core_integration: integ,
  app_pass: appPass.map((a, i) => ({ group: APP_GROUPS[i] ? APP_GROUPS[i].key : i, files_checked: a.files_checked, fixes: a.fixes.length, unresolved: a.unresolved, risks: a.compile_risk_list.length })),
}
