package com.android.insta

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.navigation.InstaRoot
import com.android.insta.ui.theme.InstaTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val sessionManager: SessionManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { sessionManager.state.value == SessionState.Loading }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The bottom navigation bar draws its own background behind the gesture/3-button bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        setContent {
            InstaTheme {
                InstaRoot(sessionManager)
            }
        }
    }
}
