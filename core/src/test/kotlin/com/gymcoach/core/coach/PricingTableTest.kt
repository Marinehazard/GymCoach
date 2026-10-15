package com.gymcoach.core.coach

import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.TokenUsage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class PricingTableTest {
    @Test
    fun goldenTurnCostsTwoPointOneSevenFiveCentsOnOpus5() {
        // §6.11: 3,500 cached + 2,000 input + 400 output on Opus 5 = $0.00175 + $0.01 + $0.01 = $0.02175
        val usage = TokenUsage(inputTokens = 2000, outputTokens = 400, cacheReadInputTokens = 3500)
        assertEquals(0.02175, PricingTable.costUsd("claude-opus-5", usage), 1e-12)
    }

    @Test
    fun sonnetPricesAndCacheWritesAndUnknownModels() {
        val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000, cacheReadInputTokens = 1_000_000, cacheCreationInputTokens = 1_000_000)
        assertEquals(2.0 + 10.0 + 0.20 + 2.50, PricingTable.costUsd("claude-sonnet-5", usage), 1e-9)
        assertEquals(5.0 + 25.0 + 0.50 + 6.25, PricingTable.costUsd("claude-opus-5", usage), 1e-9)
        assertEquals(PricingTable.costUsd("claude-opus-5", usage), PricingTable.costUsd("some-future-model", usage), 1e-12)
        assertEquals(10.0, PricingTable.OPUS_5.cacheWrite1hPerMTok)
        assertEquals(4.0, PricingTable.SONNET_5.cacheWrite1hPerMTok)
    }

    @Test
    fun tokenEstimatorIsAboutFourCharsPerToken() {
        assertEquals(0, TokenEstimator.estimate(""))
        assertEquals(1, TokenEstimator.estimate("abcd"))
        assertEquals(2, TokenEstimator.estimate("abcde"))
        assertEquals(250, TokenEstimator.estimate("x".repeat(1000)))
    }

    @Test
    fun spendMeterRecordsMonthToDateAndCacheHitRate() = runBlocking {
        val coach = FakeCoach()
        val clock = MutableClock(Instant.parse("2026-09-30T20:00:00Z"))
        val meter = SpendMeter(coach, clock)
        assertNull(meter.cacheHitRate(Instant.EPOCH))
        val r = meter.record("claude-opus-5", TokenUsage(inputTokens = 2000, outputTokens = 400, cacheReadInputTokens = 3500), BackendMode.DIRECT_API, "c1")
        assertEquals(0.02175, r.costUsd, 1e-12)
        val bridge = meter.record("claude-opus-5", TokenUsage(inputTokens = 2000, outputTokens = 400), BackendMode.HOME_BRIDGE, "c1")
        assertEquals(0.0, bridge.costUsd)
        assertEquals(0.02175, meter.monthToDate(), 1e-12)
        assertEquals(3500.0 / 7500.0, meter.cacheHitRate(Instant.EPOCH)!!, 1e-12)
        clock.instant = Instant.parse("2026-10-01T08:00:00Z")   // new month in Europe/Berlin
        assertEquals(0.0, meter.monthToDate(), 1e-12)
    }
}
