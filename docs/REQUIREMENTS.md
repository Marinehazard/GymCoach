# GymCoach — requirements (from the user, 2026-09-04)

## Who the user is
- Johannes: service technician who travels for work and trains at many different, often new, gyms.
  Has a regular home gym for when he is home. Mechanical engineer by training; technically literate.
- Uses a Claude Max subscription and Claude Code. Wants the app's coach chat to be powered by Claude.

## What the app must do (user's own list, expanded)
1. Give training plans and track progress over time.
2. Short explanation per exercise plus tips on what to watch out for (form cues, common mistakes, safety).
3. Let the user select which training infrastructure (equipment) is available on a given day and plan the
   workout from that. There is a base set (common machines, bench, power rack, dumbbells, cables, ...) and
   the option to add other equipment when at a different gym.
4. Gym presets: create a named "gym set" (e.g. "Harry's gym", "Home gym", "Hotel gym") that remembers the
   equipment, so the user just picks the gym instead of re-specifying machines each time.
5. Track weight, height, age (and other profile data: sex, training experience, days per week, session
   length, injuries/limitations) and training goals; the plan adapts to these.
6. A chat window with Claude (the user's Max subscription is the preferred funding source; see CONSTRAINTS)
   so the plan can be adapted flexibly along the way in natural language.
7. Sometimes prescribe cardio; give nutrition tips; supplementation advice; ask specific questions
   (check-ins) to find where nutrition or other habits (sleep, stress, steps) can be optimised, and adapt.
8. Warm-up and mobility are part of every session.
9. Everything according to the latest scientific evidence. Goal: replace a personal trainer and be better.

## Constraints and decisions already made
- Platform: native Android, Kotlin, Jetpack Compose, Material 3. minSdk 26, target latest stable.
- Offline-first: the whole training system (plans, logging, library, progression) must work without network.
  Only the Claude chat needs connectivity. Local storage (Room + DataStore). No custom backend server.
- Two Gradle modules: `:core` (pure Kotlin/JVM, all domain logic + planner + knowledge + unit tests, no
  Android imports) and `:app` (Android UI, persistence, chat client).
- The developer machine currently has NO JDK / Android SDK / Android Studio. Code must therefore be
  written compile-conservatively: stable, well-known library APIs only, explicit imports, no exotic
  features, versions pinned to verified-existing artifacts. `:core` will be compiled and unit-tested
  with a JDK + Gradle; `:app` will first be compiled by the user in Android Studio.
- Chat backend must be pluggable: (a) direct Anthropic API with an API key stored in Android
  Keystore-backed encrypted storage; (b) optional "home bridge" URL (a small server the user may run on
  his PC) if research shows that is the legitimate way to use a Max subscription. The app must make the
  policy situation honest and explicit in its settings UI.
- The coach chat must be able to ACT, not just talk: it gets the profile, gyms, current plan and recent
  logs as context, and can propose structured changes (swap exercise, change volume, add cardio day,
  change goal, set a reminder question) that the app applies after the user confirms.
- Units: metric default (kg, cm), imperial toggle. UI language: English (German later).
- Science: cite sources in the knowledge base (author/year or position-stand name) so the user can check.
- Sacred-content discipline (same as the user's other projects): numbers the user logs are never
  silently altered; any automatic adjustment is shown with its reason.
