package de.lulurobb.keypassmobile.platform.kdbx

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import de.lulurobb.keypassmobile.kdbx.Argon2Engine
import de.lulurobb.keypassmobile.kdbx.Argon2Params
import de.lulurobb.keypassmobile.kdbx.Argon2Variant
import java.nio.ByteBuffer

/**
 * Native Argon2 (argon2kt 1.6.0, reference implementation via JNI) for the
 * KDBX key derivation. About 9x faster than kotpass' pure Kotlin on the A55
 * (section 13), which keeps the unlock below 2 seconds.
 *
 * argon2kt copies the password into a direct buffer and wipes it; the native
 * result buffers (raw and encoded hash) are wiped here after copying the raw
 * hash, because they hold the derived key.
 */
internal class NativeArgon2Engine : Argon2Engine {

    private val argon2 by lazy { Argon2Kt() }

    override fun hash(password: ByteArray, params: Argon2Params): ByteArray {
        val result = argon2.hash(
            mode = when (params.variant) {
                Argon2Variant.Argon2d -> Argon2Mode.ARGON2_D
                Argon2Variant.Argon2id -> Argon2Mode.ARGON2_ID
            },
            password = password,
            salt = params.salt,
            tCostInIterations = Math.toIntExact(params.iterations),
            mCostInKibibyte = Math.toIntExact(params.memoryKiB),
            parallelism = params.parallelism,
            hashLengthInBytes = Argon2Params.HASH_LENGTH,
            version = when (params.version) {
                0x10 -> Argon2Version.V10
                0x13 -> Argon2Version.V13
                else -> throw IllegalArgumentException("Unsupported Argon2 version")
            },
        )
        try {
            return result.rawHashAsByteArray()
        } finally {
            wipe(result.rawHash)
            wipe(result.encodedOutput)
        }
    }

    private fun wipe(buffer: ByteBuffer) {
        try {
            val b = buffer.duplicate()
            b.clear()
            while (b.hasRemaining()) b.put(0)
        } catch (_: Exception) {
            // Read-only or already released buffer: nothing more to do.
        }
    }
}
