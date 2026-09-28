package com.swims.app.sync

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import kotlinx.coroutines.tasks.await

/**
 * Optional account layer.
 *
 * SWIMS works completely without an account — everything lives in the encrypted
 * local database. Signing in exists purely so a user who *wants* their history
 * to survive a lost phone or reinstall can have that, and so the same history
 * can appear on a second device.
 *
 * Design notes:
 *  • Sign-in is never required and never blocks any feature.
 *  • Cloud sync already runs under an anonymous Firebase user; when someone
 *    creates a real account we **link** that anonymous identity rather than
 *    replacing it, so data logged before signing up carries over intact.
 *  • Credentials go straight to Firebase Auth. SWIMS never stores the password,
 *    and it never touches the local encryption key.
 *
 * Inert unless a Firebase project is configured (see README).
 */
class AccountManager {

    data class AccountState(
        val configured: Boolean,
        val signedIn: Boolean,
        val email: String?,
    )

    sealed interface Result {
        data class Success(val email: String) : Result
        data class Failure(val message: String) : Result
    }

    fun isConfigured(): Boolean = try {
        FirebaseApp.getInstance(); true
    } catch (e: Exception) {
        false
    }

    private val auth get() = FirebaseAuth.getInstance()

    /** Current account state, for rendering the settings card. */
    fun state(): AccountState {
        if (!isConfigured()) return AccountState(false, false, null)
        val user = runCatching { auth.currentUser }.getOrNull()
        // An anonymous user is a sync identity, not a real account.
        val real = user != null && !user.isAnonymous && !user.email.isNullOrBlank()
        return AccountState(true, real, user?.email)
    }

    /**
     * Creates an account. If the device is already syncing anonymously, the
     * existing identity is upgraded in place so nothing already synced is lost.
     */
    suspend fun signUp(email: String, password: String): Result {
        if (!isConfigured()) return Result.Failure(NOT_CONFIGURED)
        val e = email.trim()
        validate(e, password)?.let { return Result.Failure(it) }

        return try {
            val current = auth.currentUser
            val credential = EmailAuthProvider.getCredential(e, password)
            val user = if (current != null && current.isAnonymous) {
                // Upgrade the anonymous identity → keeps previously synced data.
                current.linkWithCredential(credential).await().user
            } else {
                auth.createUserWithEmailAndPassword(e, password).await().user
            }
            Result.Success(user?.email ?: e)
        } catch (ex: FirebaseAuthWeakPasswordException) {
            Result.Failure("That password is too weak — use at least 6 characters.")
        } catch (ex: FirebaseAuthUserCollisionException) {
            Result.Failure("An account already exists for that email — try signing in instead.")
        } catch (ex: Exception) {
            Result.Failure(friendly(ex))
        }
    }

    suspend fun signIn(email: String, password: String): Result {
        if (!isConfigured()) return Result.Failure(NOT_CONFIGURED)
        val e = email.trim()
        validate(e, password)?.let { return Result.Failure(it) }

        return try {
            val user = auth.signInWithEmailAndPassword(e, password).await().user
            Result.Success(user?.email ?: e)
        } catch (ex: Exception) {
            Result.Failure(friendly(ex))
        }
    }

    /** Sends a password-reset email. */
    suspend fun resetPassword(email: String): Result {
        if (!isConfigured()) return Result.Failure(NOT_CONFIGURED)
        val e = email.trim()
        if (!e.contains("@")) return Result.Failure("Enter the email address for your account.")
        return try {
            auth.sendPasswordResetEmail(e).await()
            Result.Success(e)
        } catch (ex: Exception) {
            Result.Failure(friendly(ex))
        }
    }

    /**
     * Signs out. Local data is deliberately left untouched — signing out
     * should never feel like losing your history.
     */
    fun signOut() {
        if (isConfigured()) runCatching { auth.signOut() }
    }

    private fun validate(email: String, password: String): String? = when {
        !email.contains("@") || email.length < 5 -> "That doesn't look like a valid email address."
        password.length < 6 -> "Password must be at least 6 characters."
        else -> null
    }

    private fun friendly(ex: Exception): String {
        val msg = ex.message.orEmpty()
        return when {
            msg.contains("password is invalid", true) ||
                msg.contains("INVALID_LOGIN", true) ||
                msg.contains("credential is incorrect", true) ->
                "Wrong email or password."
            msg.contains("no user record", true) ->
                "No account found for that email."
            msg.contains("network", true) ->
                "No connection — sign in when you're back online. Your data is safe on this device."
            msg.contains("CONFIGURATION_NOT_FOUND", true) ||
                msg.contains("not enabled", true) ->
                "Email sign-in isn't enabled in the Firebase console yet."
            else -> "Couldn't complete that: ${msg.take(120)}"
        }
    }

    companion object {
        private const val NOT_CONFIGURED =
            "Accounts need a Firebase config in this build — see README. Your data is still saved on this device."
    }
}
