package com.velastudio.teltv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness for the mechanism the player-route restore fix relies on, without TDLib/playback:
 * a rememberSaveable value inside a navigation destination must survive the save/restore
 * cycle, while a plain remember value must NOT - the fix's transition flag depends on the
 * latter. StateRestorationTester drives the framework's saved-state path for the
 * composition (same bundle mechanism process death uses); it is still not a real OOM kill.
 */
@RunWith(AndroidJUnit4::class)
class SaveableRouteRestoreTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveableCurrentEpisodeSurvivesSaveRestore_plainTransitionFlagDoesNot() {
        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") { Text("home") }
                composable("player/{mediaId}") { entry ->
                    val arg = entry.arguments?.getString("mediaId") ?: "?"
                    // Mirrors MainActivity's player route after the fix.
                    var currentMediaId by rememberSaveable(arg) { mutableStateOf(arg) }
                    var arrivedViaTransition by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        // Simulates one autoplay advance of a live session. Gated on the
                        // saveable still holding the route argument so it does not re-fire
                        // after a restore.
                        if (currentMediaId == arg) {
                            arrivedViaTransition = true
                            currentMediaId = "tg:1:8"
                        }
                    }
                    Text("current=" + currentMediaId + ",transitioned=" + arrivedViaTransition)
                }
            }
            LaunchedEffect(Unit) {
                if (navController.currentDestination?.route == "home") {
                    navController.navigate("player/tg:1:5")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("current=tg:1:8,transitioned=true").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        // Restored chain position comes back; the transition flag resets so the route's
        // resume rule treats this as a restore and resumes at the stored position.
        compose.onNodeWithText("current=tg:1:8,transitioned=false").assertIsDisplayed()
    }
}
