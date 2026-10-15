# Gym-floor script (manual release gate) — SPEC.md Appendix B

Run on a device after `gradlew :app:assembleDebug` succeeds (docs/build/FIRST_BUILD_CHECKLIST.md
step 12). Every step is pass/fail; note the stopwatch times.

1. Fresh install → onboarding with the hotel preset → "Start" available: stopwatch ≤ 3:00.
2. Today → gym chip → new gym from "Commercial gym" → untick 3 machines, set dumbbells max 40 →
   Save: ≤ 60 s; availability count changed live.
3. At a gym whose rack has no safeties (untick `has_safeties`) with "spotter" off: bench is
   substituted (dumbbell bench, TRV-04 chip visible) and the back squat block shows the "stop at
   RIR 2 — no safeties" note; toggle Spotter on → bench returns to the plan.
4. Log 3 sets with one thumb: targets prefilled; one set via stepper; one via keypad; correct one
   set → strike-through visible; the summary's progression line uses the correction.
5. (v1.1) Lock the phone during a 3-min rest → notification countdown → rest-over alert within 2 s.
6. (v1.1) Kill the app from Recents mid-session → reopen → session restored with the draft values.
   (v1: reopen restores the session and draft without the timer.)
7. Swap a busy exercise → 3 taps; reason logged; summary shows the swap.
8. Finish → summary → progression lines with rule tags; Plan → Adjustment log has every change;
   Undo one within 5 minutes.
9. Airplane mode → everything above still works; Coach shows the offline banner and queues one
   draft; back online → the draft is sent.
10. Coach: "Hotel gym Tue–Thu" → proposals appear → Apply one → Today re-realized → Undo.
11. Settings → Backend: enter an invalid key → Test shows 401 and no key text in the message;
    paste a `claude` OAuth-style token → rejected with `backend_key_rejected`.
12. Quick readiness with 6–7 h / feel 3 / sore 2 → "Amber 56"; expand → fill quality 3 and stress
    3 → "Amber 58".
