package com.threadprotection.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.threadprotection.app.BuildConfig
import com.threadprotection.app.data.Account

/**
 * Real Google Sign-In via Credential Manager (README: "Use the official Google Sign-In button
 * and Credential Manager on Android"). Requires a *web* OAuth client ID
 * (BuildConfig.GOOGLE_WEB_CLIENT_ID, set in app/build.gradle.kts) from the Google Cloud
 * Console — the demo sign-in button on the Sign in screen is used whenever that's blank.
 */
object GoogleAuthClient {

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    suspend fun signIn(context: Context): Result<Account> {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            return Result.failure(IllegalStateException("Google sign-in could not load here."))
        }
        return try {
            val option = GetSignInWithGoogleOption.Builder(clientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credentialManager = CredentialManager.create(context)
            val response = credentialManager.getCredential(context, request)
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val idTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val name = idTokenCredential.displayName ?: idTokenCredential.id
                Result.success(
                    Account(
                        name = name,
                        email = idTokenCredential.id,
                        initial = (idTokenCredential.givenName?.take(1) ?: name.take(1)).uppercase(),
                        picture = idTokenCredential.profilePictureUri?.toString(),
                    ),
                )
            } else {
                Result.failure(IllegalStateException("Could not read the Google response."))
            }
        } catch (e: GetCredentialException) {
            Result.failure(IllegalStateException("Google rejected this client ID or origin.", e))
        }
    }
}
