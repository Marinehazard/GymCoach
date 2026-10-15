package com.gymcoach.app

import android.app.Application
import com.gymcoach.app.di.AppContainer

/** Holds the single [AppContainer] (§1.4). Composables reach it via `(LocalContext.current.applicationContext as GymCoachApp).container`. */
class GymCoachApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Nothing eager: every AppContainer member is `by lazy` (§1.5) so start-up stays cheap.
    }
}
