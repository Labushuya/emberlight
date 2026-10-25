package io.github.labushuya.emberlight.netlab.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import io.github.labushuya.emberlight.netlab.NetlabUiState
import io.github.labushuya.emberlight.netlab.R
import io.github.labushuya.emberlight.netlab.report.Role
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Bedienung der Hauptbildschirme per Touch-Injection. Getestet wird bei Telefon- und Faltdisplay-Größe. Goldens gibt es
 * bewusst nicht (Vorgabe Paket 4.0).
 */
@RunWith(RobolectricTestRunner::class)
class NetlabScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Composable
    private fun Screen(
        state: NetlabUiState,
        onRole: (Role) -> Unit,
    ) {
        MaterialTheme {
            NetlabScreens(
                state = state,
                onRefresh = {},
                onRole = onRole,
                onStartHost = {},
                onStopHost = {},
                onAcceptJoin = {},
                onConnectGuest = {},
                onRunGuestTests = {},
                onDisconnectGuest = {},
                onBuildReport = {},
                onCloseReport = {},
            )
        }
    }

    @Test
    @Config(qualifiers = "w673dp-h728dp")
    fun homeRoleButtonsRespondToTouchOnFoldable() {
        assertRoleButtonsTouchable()
    }

    @Test
    fun homeRoleButtonsRespondToTouchOnPhone() {
        assertRoleButtonsTouchable()
    }

    @Test
    @Config(qualifiers = "w673dp-h728dp")
    fun hostStartButtonIsEnabledAndTouchableOnFoldable() {
        rule.setContent {
            Screen(state = NetlabUiState(role = Role.HOST), onRole = {})
        }
        rule.onNodeWithText(context.getString(R.string.host_start)).assertIsEnabled()
        rule.onNodeWithText(context.getString(R.string.host_start)).performTouchInput { click() }
    }

    @Test
    fun homeShowsLoadingWhileCapabilitiesAreMissing() {
        rule.setContent {
            Screen(state = NetlabUiState(role = Role.NONE, capabilities = null), onRole = {})
        }
        rule.onNodeWithText(context.getString(R.string.home_loading)).assertIsDisplayed()
    }

    /** Ein Setzen des Inhalts je Test (Compose-Testregel), beide Knöpfe nacheinander per Touch. */
    private fun assertRoleButtonsTouchable() {
        val selected = mutableListOf<Role>()
        rule.setContent { Screen(state = NetlabUiState(), onRole = { selected += it }) }
        rule.onNodeWithText(context.getString(R.string.home_role_host)).performTouchInput { click() }
        rule.onNodeWithText(context.getString(R.string.home_role_guest)).performTouchInput { click() }
        assertEquals(listOf(Role.HOST, Role.GUEST), selected)
    }
}
