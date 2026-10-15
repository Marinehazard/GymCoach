package com.gymcoach.app.network.bridge

import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.repo.BackendException
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.StreamFrame
import com.gymcoach.core.repo.TestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/** Backend for `BackendMode.NONE`: every call fails with [BackendError.NotConfigured] ("Add an API key in Settings → Backend", §6.10). */
class NotConfiguredBackend : ClaudeBackend {
    override val mode: BackendMode = BackendMode.NONE

    override fun stream(body: JsonObject): Flow<StreamFrame> = flow {
        throw BackendException(BackendError.NotConfigured)
    }

    override suspend fun complete(body: JsonObject): StreamFrame = throw BackendException(BackendError.NotConfigured)

    override suspend fun test(): TestResult = TestResult(error = BackendError.NotConfigured, requestId = null)
}
