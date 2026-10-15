// core/src/main/kotlin/com/gymcoach/core/repo/NetworkMonitor.kt
package com.gymcoach.core.repo

import kotlinx.coroutines.flow.Flow

/**
 * Connectivity as :core sees it. :app implements it with ConnectivityManager
 * (AndroidNetworkMonitor, §6.10: a network counts only with NET_CAPABILITY_VALIDATED);
 * tests use FakeNetworkMonitor. CoachTurnService checks isOnline() before building a request;
 * CoachViewModel collects observe() to flush the one queued draft (§8.17).
 */
interface NetworkMonitor {
    fun isOnline(): Boolean
    fun observe(): Flow<Boolean>          // emits the current value first, then every change
}
