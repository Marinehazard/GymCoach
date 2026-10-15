// core/src/main/kotlin/com/gymcoach/core/coach/ProposalApplier.kt
package com.gymcoach.core.coach

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanDiff
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import java.time.LocalDate
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * §6.8: turns a confirmed proposal into an appended [PlanOverride] (never edits logged sets, logs or earlier
 * overrides) and records the decision. The ViewModel then recomputes the plan, diffs it and calls [finalize]; it
 * records the [Adjustment] built by [adjustmentFor] through its WorkoutRepository.
 */
class ProposalApplier(private val plans: PlanRepository, private val coach: CoachRepository, private val clock: Clock) {
    companion object {
        /** resultSummary stored until [finalize] replaces it with the real PlanDiff JSON. */
        const val PROVISIONAL_SUMMARY: String = "{\"note\":\"applied; plan recompute pending\"}"
        const val REJECTED_SUMMARY: String = "rejected by user"
        const val DEFERRED_SUMMARY: String = "the user has not decided yet"
        private val RULE_ID = Regex("\\b[A-Z][A-Z0-9]*-[0-9]{2}\\b")
    }

    /** §6.8: appends the PlanOverride (expiresOn = today for TODAY-scope), marks the proposal APPLIED (provisional summary), returns the override. */
    suspend fun apply(proposal: Proposal, today: LocalDate): PlanOverride {
        val mutation = requireNotNull(proposal.mutation) { "proposal ${proposal.id} has no valid mutation (status ${proposal.status})" }
        check(proposal.status == ProposalStatus.PENDING || proposal.status == ProposalStatus.DEFERRED) {
            "proposal ${proposal.id} is ${proposal.status}, not PENDING/DEFERRED"
        }
        val override = PlanOverride(
            id = IdGen.newId(),
            createdAt = clock.now(),
            source = OverrideSource.CHAT,
            proposalId = proposal.id,
            mutation = mutation,
            expiresOn = if (scopeOf(mutation) == MutationScope.TODAY) today else null,
        )
        plans.addOverride(override)
        coach.decideProposal(proposal.id, ProposalStatus.APPLIED, clock.now(), PROVISIONAL_SUMMARY, override.id)
        return override
    }

    suspend fun reject(proposal: Proposal, reason: String?) {
        val summary = reason?.trim()?.takeIf { it.isNotEmpty() }?.let { "$REJECTED_SUMMARY: $it" } ?: REJECTED_SUMMARY
        coach.decideProposal(proposal.id, ProposalStatus.REJECTED, clock.now(), summary, null)
    }

    suspend fun defer(proposal: Proposal) {
        coach.decideProposal(proposal.id, ProposalStatus.DEFERRED, clock.now(), DEFERRED_SUMMARY, null)
    }

    /** decideProposal(APPLIED, resultSummary = PlanDiff JSON). */
    suspend fun finalize(proposalId: String, diff: PlanDiff, overrideId: String) {
        val json = CoreJson.stored.encodeToString(PlanDiff.serializer(), diff)
        coach.decideProposal(proposalId, ProposalStatus.APPLIED, clock.now(), json, overrideId)
    }

    suspend fun markFailed(proposalId: String, message: String) {
        coach.decideProposal(proposalId, ProposalStatus.FAILED, clock.now(), message, null)
    }

    /**
     * The §6.8 `Adjustment(kind = OVERRIDE, source = CHAT, proposalId, overrideId, before/after = the mutated fields)`
     * the ViewModel records after the recompute. Rule ids: OVERRIDE plus every known rule id the rationale cites.
     */
    fun adjustmentFor(proposal: Proposal, override: PlanOverride, diff: PlanDiff?): Adjustment {
        val known = RuleIds.ALL.toSet()
        val cited = RULE_ID.findAll(proposal.rationale).map { it.value }.filter { it in known }.distinct().toList()
        val before = diff?.let { CoreJson.stored.encodeToString(MapSerializer(Muscle.serializer(), Double.serializer()), it.weeklySetsBefore) } ?: "{}"
        val after = CoreJson.stored.encodeToString(PlanMutation.serializer(), override.mutation)
        val summary = "Coach proposal applied: ${proposal.toolName.removePrefix("propose_").replace('_', ' ')}" +
            (if (diff != null && diff.sessionsChanged.isNotEmpty()) " (" + diff.sessionsChanged.joinToString(", ") + ")" else "")
        return Adjustment(
            id = IdGen.newId(),
            kind = AdjustmentKind.OVERRIDE,
            ruleIds = listOf(RuleIds.OVERRIDE) + cited,
            summary = summary,
            inputs = linkedMapOf("tool" to proposal.toolName, "rationale" to proposal.rationale),
            beforeJson = before,
            afterJson = after,
            source = OverrideSource.CHAT,
            proposalId = proposal.id,
            overrideId = override.id,
            createdAt = clock.now(),
        )
    }

    private fun scopeOf(m: PlanMutation): MutationScope? = when (m) {
        is PlanMutation.SwapExercise -> m.scope
        is PlanMutation.ChangeVolume -> m.scope
        is PlanMutation.ChangeRepRange -> m.scope
        else -> null
    }
}
