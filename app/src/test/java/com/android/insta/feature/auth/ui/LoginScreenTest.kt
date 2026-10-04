package com.android.insta.feature.auth.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.testutil.FakeAuthRepository
import com.android.insta.ui.theme.InstaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Behavior tests for the login UI wired to a real LoginViewModel and a fake repository (Robolectric). */
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repository = FakeAuthRepository()
    private fun str(id: Int) = composeRule.activity.getString(id)

    private fun setContent(googleAvailable: Boolean = false, onRegister: () -> Unit = {}) {
        val viewModel = LoginViewModel(repository, googleAvailable)
        composeRule.setContent {
            InstaTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LoginContent(state, viewModel::onEvent, onGoogleClick = {}, onNavigateToRegister = onRegister)
            }
        }
    }

    @Test
    fun submittingEmptyFormShowsRequiredErrors() {
        setContent()
        composeRule.onNodeWithText(str(R.string.action_log_in)).performClick()
        assertEquals(2, composeRule.onAllNodesWithText(str(R.string.error_required)).fetchSemanticsNodes().size)
    }

    @Test
    fun wrongPasswordShowsServerMessage() {
        repository.loginResult = ApiResult.Failure(AppError.Api(401, "INVALID_CREDENTIALS", "Incorrect"))
        setContent()
        composeRule.onNodeWithText(str(R.string.field_login)).performTextInput("jane.doe")
        composeRule.onNodeWithText(str(R.string.field_password)).performTextInput("wrong-pass")
        composeRule.onNodeWithText(str(R.string.action_log_in)).performClick()

        composeRule.onNodeWithText(str(R.string.error_invalid_credentials)).assertIsDisplayed()
        assertEquals(listOf("login:jane.doe"), repository.calls)
    }

    @Test
    fun googleButtonOnlyShownWhenConfigured() {
        setContent(googleAvailable = false)
        composeRule.onAllNodesWithText(str(R.string.action_continue_with_google)).assertCountEquals(0)
    }

    @Test
    fun signUpLinkNavigates() {
        var navigated = false
        setContent(onRegister = { navigated = true })
        composeRule.onNodeWithText(str(R.string.action_sign_up)).performClick()
        assertEquals(true, navigated)
    }

    @Test
    fun passwordVisibilitySurvivesRecreation() {
        val restoration = StateRestorationTester(composeRule)
        val viewModel = LoginViewModel(repository, isGoogleAvailable = false)
        restoration.setContent {
            InstaTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LoginContent(state, viewModel::onEvent, {}, {})
            }
        }
        composeRule.onNodeWithText(str(R.string.action_show)).performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(str(R.string.action_hide)).assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(expected: Int) {
    assertEquals(expected, fetchSemanticsNodes().size)
}
