package io.github.labushuya.emberlight.feature.space

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpacesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsHeadingAndMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            EmberlightTheme {
                SpacesScreen()
            }
        }

        composeRule
            .onNode(hasText(context.getString(R.string.feature_space_title)) and isHeading())
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.feature_space_text)).assertIsDisplayed()
    }
}
