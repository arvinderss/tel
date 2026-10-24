package app.tel

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The main scenario, driven through the real UI (photos need a camera or picker, so they are
 * covered by the repository tests instead).
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

    private fun waitForText(text: String, substring: Boolean = false) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
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

    private fun openForm() {
        compose.onAllNodesWithText("Add fill-up")[0].performScrollTo().performClick()
        waitForText("Tank full")
    }

    private fun save() {
        compose.onNodeWithText("Save").performScrollTo().performClick()
    }

    @Test fun onboardingThenTwoFillUpsThenSwitchUnits() {
        waitForText("Get started")
        compose.onNodeWithText("Get started").performScrollTo().performClick()
        waitForText("No fill-ups yet.")

        // First fill-up: amount + rate -> volume is calculated.
        openForm()
        enter("field_total_cost", "3000")
        enter("field_price_per_unit", "100")
        waitForText("Calculated from the other two. Type to override.")
        enter("field_odometer", "50000")
        save()
        waitForText("Total spend")
        waitForText("Shown after two full-tank fill-ups")

        // Second: the rate is prefilled from the first, so the amount alone gives the volume.
        openForm()
        waitForText("From your fill-up on", substring = true)
        enter("field_total_cost", "4000")
        enter("field_odometer", "50600")
        waitForText("600 km")
        save()
        // 600 km / 40 L
        waitForText("15.0 km/L")

        // A lower odometer than the previous fill-up is an error and blocks saving.
        openForm()
        enter("field_total_cost", "1000")
        enter("field_odometer", "50500")
        waitForText("Lower than the earlier fill-up", substring = true)
        save()
        compose.onNodeWithText("Tank full").assertExists() // still on the form
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Total spend")

        compose.onNodeWithText("Settings").performClick()
        waitForText("US (miles, US gallons, MPG)")
        compose.onNodeWithText("US (miles, US gallons, MPG)").performScrollTo().performClick()
        compose.onNodeWithText("Dashboard").performClick()
        // 15 km/L = 35.3 MPG
        waitForText("35.3 MPG")
    }
}
