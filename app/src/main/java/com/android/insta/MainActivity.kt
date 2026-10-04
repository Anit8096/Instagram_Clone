package com.android.insta

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.navigation.DeepLinks
import com.android.insta.navigation.InstaRoot
import com.android.insta.navigation.PendingDeepLinks
import com.android.insta.ui.theme.InstaTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val sessionManager: SessionManager by inject()
    private val deepLinks: PendingDeepLinks by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { sessionManager.state.value == SessionState.Loading }
        super.onCreate(savedInstanceState)
        // Only a fresh launch carries a new link; after recreation the back stacks were already restored.
        if (savedInstanceState == null) handleDeepLink(intent)
        enableEdgeToEdge()
        // The bottom navigation bar draws its own background behind the gesture/3-button bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        setContent {
            InstaTheme {
                InstaRoot(sessionManager)
            }
        }
    }

    /** singleTop: a notification tapped while the app is open arrives here instead of starting a new task. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.dataString?.let(DeepLinks::parse)?.let(deepLinks::submit)
    }
}
