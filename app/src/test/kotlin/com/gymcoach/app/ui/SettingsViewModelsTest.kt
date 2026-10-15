package com.gymcoach.app.ui

import com.gymcoach.app.fakes.FakeRepositories
import com.gymcoach.app.ui.knowledge.ConstantsModel
import com.gymcoach.app.ui.settings.AdvancedConstantsViewModel
import com.gymcoach.app.ui.settings.BackendCopy
import com.gymcoach.app.ui.settings.BackendSettingsViewModel
import com.gymcoach.app.ui.settings.KeyError
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.Effort
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.StreamFrame
import com.gymcoach.core.repo.TestResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Key prefix rule, Test connection copy (never the key), model change closes the conversation, nested overrides JSON. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelsTest {

    private val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private class TestBackend(var result: TestResult) : ClaudeBackend {
        override val mode: BackendMode = BackendMode.DIRECT_API
        override fun stream(body: JsonObject): Flow<StreamFrame> = emptyFlow()
        override suspend fun complete(body: JsonObject): StreamFrame = error("not used")
        override suspend fun test(): TestResult = result
    }

    private fun TestScope.backendVm(repos: FakeRepositories, backend: TestBackend): BackendSettingsViewModel =
        BackendSettingsViewModel(repos.settings, repos.apiKeys, { backend }, repos.coach, repos.clock, scope())

    @Test fun keyRule_acceptsOnlyConsoleKeys() {
        assertNull(KeyError.validate("sk-ant-api03-abcdefghijklmnop"))
        assertEquals(KeyError.REJECTED, KeyError.validate("sk-ant-oat01-abcdefghijklmnop"))
        assertEquals(KeyError.REJECTED, KeyError.validate("eyJhbGciOi.login.token"))
        assertEquals(KeyError.REJECTED, KeyError.validate("sk-ant-api03-abc def-ghijkl"))
        assertEquals(KeyError.EMPTY, KeyError.validate("   "))
    }

    @Test fun savingKeys_rejectsTokensStoresKeysAndSelectsTheApiBackend() = runTest {
        val repos = FakeRepositories()
        val vm = backendVm(repos, TestBackend(TestResult(null, "req_0")))
        vm.onKeyDraft("sk-ant-oat01-notallowedtoken")
        vm.onSaveKey()
        assertEquals(KeyError.REJECTED, vm.state.value.keyError)
        assertTrue(!repos.apiKeys.hasKey())

        vm.onKeyDraft("sk-ant-api03-validvalidvalid")
        vm.onSaveKey()
        assertTrue(repos.apiKeys.hasKey())
        assertTrue(vm.state.value.hasKey)
        assertEquals("", vm.state.value.keyDraft)
        assertEquals(BackendMode.DIRECT_API, repos.settings.get().backendMode)

        vm.onRemoveKey()
        assertTrue(!repos.apiKeys.hasKey())
        assertEquals(BackendMode.NONE, repos.settings.get().backendMode)
    }

    @Test fun testConnection_showsRequestIdAndNeverTheKey() = runTest {
        val repos = FakeRepositories()
        val backend = TestBackend(TestResult(null, "req_ok"))
        val vm = backendVm(repos, backend)
        vm.onKeyDraft("sk-ant-api03-supersecretsecret")
        vm.onSaveKey()
        vm.onTest()
        assertEquals("OK · request-id req_ok", vm.state.value.testResult)

        backend.result = TestResult(BackendError.Authentication, "req_401")
        vm.onTest()
        val line = assertNotNull(vm.state.value.testResult)
        assertTrue(line.contains("req_401"))
        assertTrue(line.contains(BackendCopy.of(BackendError.Authentication)))
        assertTrue(!line.contains("supersecret"))
    }

    @Test fun changingModelOrEffort_closesTheOpenConversation() = runTest {
        val repos = FakeRepositories()
        val conv = ChatConversation(
            id = "c1", startedAt = repos.clock.now(), backend = BackendMode.DIRECT_API, model = "claude-opus-5", effort = Effort.LOW,
            systemPromptHash = "h", toolContractVersion = 1, status = ConversationStatus.OPEN,
        )
        repos.coach.createConversation(conv)
        val vm = backendVm(repos, TestBackend(TestResult(null, null)))
        assertTrue(vm.state.value.openConversationWillClose)
        vm.onModel("claude-sonnet-5")
        assertEquals("claude-sonnet-5", repos.settings.get().modelId)
        assertEquals(ConversationStatus.CLOSED, repos.coach.conversation("c1")?.status)
        assertNull(repos.coach.openConversation())
    }

    @Test fun advancedEditor_writesNestedOverridesAndResetRemovesThem() = runTest {
        val repos = FakeRepositories()
        val vm = AdvancedConstantsViewModel(repos.settings, kb.rules, scope())
        val rows = vm.state.value.rows
        assertEquals(PlannerConstants.leafFields().size, rows.size, "one row per PlannerConstants leaf")
        val novice = rows.first { it.field == "vol04Novice" }
        assertTrue(novice.adjustable)
        assertEquals("band", novice.kind)
        assertEquals("8–12", novice.default)

        vm.onEdit("vol", "vol04Novice", "9,13")
        assertTrue(vm.state.value.dirty)
        vm.onSave()
        val json = CoreJson.stored.parseToJsonElement(repos.settings.get().plannerOverridesJson).jsonObject
        assertEquals(listOf("9", "13"), json.getValue("vol").jsonObject.getValue("vol04Novice").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Band(9, 13), PlannerConstants.withOverrides(PlannerConstants.DEFAULT, repos.settings.get().plannerOverridesJson, CoreJson.stored).vol.vol04Novice)
        assertEquals("9–13", vm.state.value.rows.first { it.field == "vol04Novice" }.current)

        vm.onReset("vol", "vol04Novice")
        assertEquals("{}", repos.settings.get().plannerOverridesJson)
        assertEquals("8–12", vm.state.value.rows.first { it.field == "vol04Novice" }.current)
    }

    @Test fun advancedEditor_ignoresFixedRulesAndRejectsBadValues() = runTest {
        val repos = FakeRepositories()
        val vm = AdvancedConstantsViewModel(repos.settings, kb.rules, scope())
        val fixed = vm.state.value.rows.first { !it.adjustable }
        vm.onEdit(fixed.group, fixed.field, "1")
        assertTrue(!vm.state.value.dirty)

        vm.onEdit("vol", "vol04Novice", "twelve")
        vm.onSave()
        assertNotNull(vm.state.value.error)
        assertEquals("{}", repos.settings.get().plannerOverridesJson)
        vm.onResetAll()
        assertEquals("{}", repos.settings.get().plannerOverridesJson)
    }

    @Test fun constantsModel_formatsAndParsesEveryKind() {
        val kindsSeen = PlannerConstants.leafFields().size
        assertTrue(kindsSeen > 100)
        val leaves = ConstantsModel.leaves(PlannerConstants.DEFAULT, PlannerConstants.DEFAULT, kb.rules)
        assertEquals(kindsSeen, leaves.size)
        for (leaf in leaves) {
            val text = ConstantsModel.format(leaf.kind, leaf.defaultJson).replace("–", ",")
            val parsed = ConstantsModel.parse(leaf.kind, text, leaf.defaultJson)
            assertNotNull(parsed, "${leaf.id} (${leaf.kind}) round trip of '$text'")
            assertEquals(ConstantsModel.numbers(leaf.defaultJson), ConstantsModel.numbers(parsed), leaf.id)
        }
    }
}
