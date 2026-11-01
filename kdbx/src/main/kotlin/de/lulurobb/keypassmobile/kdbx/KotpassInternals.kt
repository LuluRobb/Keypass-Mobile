package de.lulurobb.keypassmobile.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.models.EntryValue
import java.lang.reflect.Field

/**
 * Reflective access to two kotpass 0.13.0 details that are not public API.
 * Both are covered by tests, so a kotpass update that changes them fails the
 * build instead of silently degrading. R8 keep rules for both are shipped in
 * META-INF/proguard/kdbx.pro inside this jar.
 */
internal object KotpassInternals {

    /**
     * kotpass' default KdfProvider (AES-KDF and pure-Kotlin Argon2). The class
     * and all KDF implementations are Kotlin-internal, so the singleton is
     * looked up by name.
     */
    val baseKdfProvider: KdfProvider by lazy {
        Class.forName("app.keemobile.kotpass.cryptography.format.BaseKdfProvider")
            .getField("INSTANCE")
            .get(null) as KdfProvider
    }

    /**
     * The private EncryptedValue inside [EntryValue.Encrypted]. kotpass only
     * exposes the decrypted value as String (content), which cannot be
     * wiped. Null if the field is not accessible; callers then fall back to
     * content (SE-04 only partly met in that case).
     */
    private val encryptedValueField: Field? by lazy {
        try {
            EntryValue.Encrypted::class.java.getDeclaredField("value")
                .takeIf { it.type == EncryptedValue::class.java }
                ?.apply { isAccessible = true }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            // SecurityException or InaccessibleObjectException
            null
        }
    }

    /** True if [encryptedValue] works with the kotpass version on the classpath. */
    val canReadEncryptedValue: Boolean get() = encryptedValueField != null

    /** Returns the protected value of [value] without decrypting it, or null. */
    fun encryptedValue(value: EntryValue.Encrypted): EncryptedValue? =
        encryptedValueField?.get(value) as? EncryptedValue
}
