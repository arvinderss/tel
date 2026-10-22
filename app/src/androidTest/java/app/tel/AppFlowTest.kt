package app.tel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The acceptance scenario, driven through the real UI with manual entry
 * (OCR is exercised by the parser unit tests; photos need a camera or picker).
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun clean() {
        val app = ApplicationProvider.getApplicationContext<TelApp>()
        runBlocking { app.container.repository.deleteAll() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() { scenario?.close() }

    private fun waitForText(text: String) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (t: Throwable) {
            val screen = runCatching {
                compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
                    fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                        (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.map { it.text } ?: emptyList()) +
                            n.children.flatMap { texts(it) }
                    texts(root)
                }.joinToString(" | ")
            }.getOrDefault("?")
            throw AssertionError("TELTEST timed out waiting for '$text'. Screen: ${screen.take(900)}")
        }
    }

    private fun enter(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
    }

    private fun addRefuel(odo: String, litres: String, price: String) {
        compose.onAllNodesWithText("Add refuelling")[0].performScrollTo().performClick()
        waitForText("Enter values manually")
        compose.onNodeWithText("Enter values manually").performScrollTo().performClick()
        waitForText("Review refuelling")
        enter("field_odometer_before", odo)
        enter("field_fuel_added", litres)
        enter("field_price_per_unit", price)
        compose.onNodeWithText("Save refuelling").performScrollTo().performClick()
        waitForText("Total spend")
    }

    @Test fun onboardingThenTwoRefuelsThenSwitchUnits() {
        waitForText("Get started")
        compose.onNodeWithText("Get started").performScrollTo().performClick()
        waitForText("No refuelling records yet.")

        addRefuel("50000", "30", "100")
        compose.onNodeWithText("Available after the next refuelling").assertIsDisplayed()

        addRefuel("50600", "40", "100")
        // 600 km / 40 L = 15.0 km/L; full-tank flag off -> partial but still shown.
        waitForText("15.0 km/L")

        compose.onNodeWithText("Settings").performClick()
        waitForText("US (miles, US gallons, MPG)")
        compose.onNodeWithText("US (miles, US gallons, MPG)").performScrollTo().performClick()
        compose.onNodeWithText("Dashboard").performClick()
        // 15 km/L = 35.3 MPG
        waitForText("35.3 MPG")
    }
}
