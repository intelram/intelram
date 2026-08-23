package com.intelram.shield.auth

import android.app.Application
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.intelram.shield.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface AuthUiState {
    data object SignedOut : AuthUiState
    data object SigningIn : AuthUiState
    data class SignedIn(val email: String, val displayName: String?, val isDemo: Boolean) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

private const val PLACEHOLDER_CLIENT_ID = "YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID"

/**
 * Wraps Android's Credential Manager / Google Identity Services for real
 * "Sign in with Google". Falls back to a clearly-labeled offline demo mode
 * until a real OAuth Web Client ID is set in strings.xml — see the comment
 * there for how to obtain one.
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    val isConfiguredForRealSignIn: Boolean
        get() = getApplication<Application>().getString(R.string.google_web_client_id) != PLACEHOLDER_CLIENT_ID

    fun signIn(context: Context) {
        if (_state.value == AuthUiState.SigningIn) return
        _state.value = AuthUiState.SigningIn

        val webClientId = context.getString(R.string.google_web_client_id)
        if (webClientId == PLACEHOLDER_CLIENT_ID) {
            viewModelScope.launch {
                delay(900)
                _state.value = AuthUiState.SignedIn(
                    email = "demo.user@gmail.com",
                    displayName = "Demo User",
                    isDemo = true,
                )
            }
            return
        }

        viewModelScope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val option = GetSignInWithGoogleOption.Builder(webClientId)
                    .setNonce(UUID.randomUUID().toString())
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    _state.value = AuthUiState.SignedIn(
                        email = googleCredential.id,
                        displayName = googleCredential.displayName,
                        isDemo = false,
                    )
                } else {
                    _state.value = AuthUiState.Error("Unexpected credential type returned.")
                }
            } catch (e: GetCredentialException) {
                _state.value = AuthUiState.Error(e.message ?: "Sign-in was cancelled or failed.")
            }
        }
    }

    fun signOut() {
        _state.value = AuthUiState.SignedOut
    }
}
