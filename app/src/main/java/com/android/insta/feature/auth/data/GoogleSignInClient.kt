package com.android.insta.feature.auth.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

sealed interface GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data object NoAccount : GoogleSignInResult
    data class Failure(val message: String?) : GoogleSignInResult
}

interface GoogleSignInClient {
    /** False when no OAuth client ID is configured; the UI hides the Google button. */
    val isConfigured: Boolean

    /** Shows the Google account picker. Needs an Activity [context] to host the bottom sheet. */
    suspend fun requestIdToken(context: Context): GoogleSignInResult

    /** Forgets the chosen account so the picker shows again after logout. */
    suspend fun clearState()
}

/** Credential Manager + "Sign in with Google" button flow; the ID token is verified by our server. */
class CredentialManagerGoogleSignInClient(
    private val appContext: Context,
    private val serverClientId: String,
) : GoogleSignInClient {

    override val isConfigured: Boolean get() = serverClientId.isNotBlank()

    override suspend fun requestIdToken(context: Context): GoogleSignInResult {
        if (!isConfigured) return GoogleSignInResult.Failure("Google sign-in is not configured")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleSignInResult.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleSignInResult.Failure("Unexpected credential type")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.NoAccount
        } catch (e: GetCredentialException) {
            Timber.w(e, "Google sign-in failed")
            GoogleSignInResult.Failure(e.message)
        } catch (e: GoogleIdTokenParsingException) {
            Timber.w(e, "Invalid Google ID token")
            GoogleSignInResult.Failure(e.message)
        }
    }

    override suspend fun clearState() {
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
            .onFailure { Timber.w(it, "Could not clear credential state") }
    }
}
