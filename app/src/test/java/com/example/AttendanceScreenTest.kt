package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.example.ui.components.AttendanceTimeBadge
import com.example.ui.components.ScheduleMode
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttendanceScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lockedScheduleHasVisibleExplanation() {
        compose.setContent { MyApplicationTheme { AttendanceTimeBadge(ScheduleMode.FORCE_LOCKED) } }
        compose.onNodeWithText("JADWAL ABSENSI DIKUNCI ADMIN").assertIsDisplayed()
    }
}
