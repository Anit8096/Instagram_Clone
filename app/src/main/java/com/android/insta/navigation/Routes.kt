package com.android.insta.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Destinations shown while signed out (Google first; phone + code for existing accounts). */
@Serializable
sealed interface AuthRoute : NavKey {
    @Serializable data object Welcome : AuthRoute
    @Serializable data object PhoneSignIn : AuthRoute

    /** New Google user finishing sign-up; the token is short-lived (15 min) proof of the Google sign-in. */
    @Serializable data class Onboarding(val onboardingToken: String, val suggestedUsername: String, val displayName: String) : AuthRoute
}

/** Top-level tabs of the signed-in app; each keeps its own back stack. */
@Serializable
sealed interface MainRoute : NavKey {
    @Serializable data object Feed : MainRoute
    @Serializable data object Explore : MainRoute
    @Serializable data object Create : MainRoute
    @Serializable data object Notifications : MainRoute
    @Serializable data object Profile : MainRoute
}

/** Screens pushed onto whichever tab's back stack is current. */
@Serializable
sealed interface DetailRoute : NavKey {
    @Serializable data class PostDetail(val postId: String) : DetailRoute
    @Serializable data class UserProfile(val username: String) : DetailRoute
    @Serializable data object EditProfile : DetailRoute
    @Serializable data class FollowList(val username: String, val followers: Boolean) : DetailRoute
    @Serializable data class Comments(val postId: String) : DetailRoute
    @Serializable data object Inbox : DetailRoute
    @Serializable data class Thread(val username: String) : DetailRoute
    @Serializable data object Settings : DetailRoute
    @Serializable data object ChangePhone : DetailRoute
}
