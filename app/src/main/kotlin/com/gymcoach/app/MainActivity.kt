package com.gymcoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.gymcoach.app.ui.nav.AppNavHost
import com.gymcoach.app.ui.theme.GymCoachTheme

/** Single-activity shell: theme + navigation host (§8.3). Portrait only (manifest). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GymCoachApp).container
        setContent {
            GymCoachTheme {
                val navController = rememberNavController()
                AppNavHost(container = container, navController = navController)
            }
        }
    }
}
