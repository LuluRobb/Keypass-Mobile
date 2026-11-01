package de.lulurobb.keypassmobile.platform.biometric

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import de.lulurobb.keypassmobile.platform.R
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

/** Whether a fingerprint (strong biometric) can be used at all, for the settings (UI-03). */
enum class FingerprintAvailability {
    AVAILABLE,

    /** Sensor present, but no fingerprint enrolled in the system. */
    NONE_ENROLLED,

    /** No strong biometric sensor, or it is unavailable. */
    NO_HARDWARE,
}

/** Outcome of [FingerprintPrompt.authenticate]. */
internal sealed interface PromptResult {
    /** The user authenticated; [cipher] is now unlocked for one decryption. */
    class Authenticated(val cipher: Cipher) : PromptResult

    /** "Passwort eingeben", back, or the prompt was dismissed or interrupted (EN-02). */
    data object Cancelled : PromptResult

    /** Too many attempts or a sensor error; [lockout] after too many failures. */
    data class Error(val lockout: Boolean) : PromptResult
}

/**
 * The framework BiometricPrompt (works with any Activity from API 30, no
 * androidx.biometric needed). Strong biometrics only (class 3, the A55 face
 * unlock does not count), no device credential, negative button
 * "Passwort eingeben" (EN-02). Always bound to a CryptoObject (EN-03).
 */
internal object FingerprintPrompt {

    fun availability(context: Context): FingerprintAvailability {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return FingerprintAvailability.NO_HARDWARE
        return when (manager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> FingerprintAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> FingerprintAvailability.NONE_ENROLLED
            else -> FingerprintAvailability.NO_HARDWARE
        }
    }

    /**
     * Shows the prompt over [activity] and suspends until it closes. Must be
     * called on the main thread. Cancelling the coroutine closes the prompt.
     */
    suspend fun authenticate(activity: Activity, cipher: Cipher): PromptResult =
        suspendCancellableCoroutine { continuation ->
            val executor = activity.mainExecutor
            val signal = CancellationSignal()
            fun finish(result: PromptResult) {
                if (continuation.isActive) continuation.resume(result)
            }
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.platform_biometric_title))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .setNegativeButton(activity.getString(R.string.platform_biometric_negative), executor) { _, _ ->
                    finish(PromptResult.Cancelled)
                }
                .build()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher
                    finish(if (unlocked != null) PromptResult.Authenticated(unlocked) else PromptResult.Error(lockout = false))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    finish(mapError(errorCode))
                }

                // onAuthenticationFailed: one finger not recognized; the prompt stays open.
            }
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                prompt.authenticate(BiometricPrompt.CryptoObject(cipher), signal, executor, callback)
            } catch (e: Exception) {
                finish(PromptResult.Error(lockout = false))
            }
        }

    fun mapError(errorCode: Int): PromptResult = when (errorCode) {
        // The framework reports the negative button through its own listener, not as error.
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
        -> PromptResult.Cancelled
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
        -> PromptResult.Error(lockout = true)
        else -> PromptResult.Error(lockout = false)
    }
}
