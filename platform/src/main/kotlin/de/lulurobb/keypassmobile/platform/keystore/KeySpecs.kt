package de.lulurobb.keypassmobile.platform.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import java.util.Date
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Key specifications and shared helpers for the Android Keystore. All key
 * material lives in the AndroidKeyStore (StrongBox when available); only
 * ciphertext is written to files in noBackupFilesDir (SE-08).
 */
internal object KeySpecs {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Key of the stored password hash (EN-03). One pair at a time, replaced at every password entry. */
    const val HASH_KEY_ALIAS = "kpm_password_hash_rsa"

    /** Key of the stored SMB password (DS-03). */
    const val SMB_KEY_ALIAS = "kpm_smb_password_aes"

    /** HMAC key of the clipboard marker: recognizes a copied value without storing a hash that could be guessed offline. */
    const val CLIPBOARD_KEY_ALIAS = "kpm_clipboard_marker"

    const val RSA_KEY_SIZE = 2048
    const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"
    const val AES_KEY_SIZE = 256
    const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128

    /**
     * OAEP parameters for both sides: SHA-256 as main digest, MGF1 with SHA-1,
     * because the Android Keystore only supports SHA-1 for MGF1 (section 9).
     * Software providers would default to other values, so they are always
     * passed explicitly.
     */
    fun oaep(): OAEPParameterSpec =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    /**
     * RSA key pair for the password hash. With [authRequired] the private key
     * can only decrypt inside a BiometricPrompt with CryptoObject, strong
     * biometrics only, once per authentication (EN-02, EN-03), and becomes
     * permanently invalid when a fingerprint is enrolled (EN-04). The private
     * key can no longer decrypt after [validityEnd] (EN-05). The public key is
     * exported and used in software, so encrypting needs no authentication.
     *
     * Tests pass authRequired = false to cover the same specification without
     * a fingerprint.
     */
    fun hashKeySpec(alias: String, validityEnd: Date, authRequired: Boolean, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(RSA_KEY_SIZE)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setKeyValidityForConsumptionEnd(validityEnd)
            .apply {
                if (authRequired) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    setInvalidatedByBiometricEnrollment(true)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()

    /**
     * AES-256-GCM key for the SMB password. Usable without user authentication,
     * so the background sync can connect while the app is locked (DS-03, SY-06).
     */
    fun smbKeySpec(alias: String, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(AES_KEY_SIZE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

    /**
     * HMAC-SHA256 key for the clipboard marker. Not exportable, usable without user
     * authentication (the marker is checked while locked, from the alarm).
     */
    fun clipboardMacSpec(alias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

    fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun deleteKeyQuietly(alias: String) {
        try {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (_: Exception) {
            // Nothing else to do; a stale key is harmless without its ciphertext.
        }
    }
}

/** Writes [bytes] to [file] via a temporary file and rename, so readers never see half a file. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
    if (!temp.renameTo(file)) {
        temp.delete()
        throw IOException("Rename failed")
    }
}
